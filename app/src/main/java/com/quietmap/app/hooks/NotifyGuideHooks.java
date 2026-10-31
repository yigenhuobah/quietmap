package com.quietmap.app.hooks;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * notify_guide 通知权限引导净化（只拦通知权限，定位/相机等引导保持原样）。
 *
 * dexsql 实测（高德 17.02.0.2010；17.01.0.2012 挂载点同构，仅桥接类混淆名
 * Lso2; → Lpp2;，方法体字符串集合完全一致）：
 *
 * 1) com.alipay.android.phone.mobilesdk.permission.guide.PermissionGuideFloatShow
 *    #showNotificationGuide(PermissionGuideRequest)V
 *    底部通知权限浮条。private static 方法，先 setAccessible 再 skipVoid：
 *    不展示浮条。
 *
 * 2) com.alipay.android.phone.mobilesdk.permission.guide.sys.SysPermissionGuideFloatMode
 *    #showFloatWithSysCore()V
 *    系统浮条主路径。字段 guidePermissionStr / permissionList 声明在父类
 *    SysPermissionGuideMode 上，反射读取；只有命中通知权限时才跳过，
 *    其余权限引导原样 proceed。
 *
 * 3) pp2#i(Activity, JSONObject, String)V（17.01 为 so2#i）
 *    权限请求参数桥。只有同时满足以下条件才把 permissionType 改写为 "location"：
 *    - permissionType 是**单一**通知权限（不含逗号/分号/竖线/空白，不含其他权限）；
 *    - 目标 Activity 的定位权限已经授予（fine + coarse 都已授权）。
 *    改写用 chain.proceed(Object[]) 传新参数（libxposed 102 的 proceedWith(Object)
 *    改的是 this，不是参数）。不满足条件时原样放行并限流记录
 *    "notify guide pass (unsafe rewrite skipped)"，避免新增定位弹窗或丢掉复合
 *    权限串里的相机/存储等权限。
 *
 * isNotificationPermission：只认已知通知权限的精确名字（大小写不敏感、忽略
 * 首尾空白）：POST_NOTIFICATIONS，以及旧的 NOTIFICATION / NOTIFICATION_*
 * 常量；ACCESS_NOTIFICATION_POLICY 虽然名字含 NOTIFICATION，但属于勿扰策略
 * 访问权限，显式排除。复合权限串（逗号/分号/竖线/内部空白分隔）一律不命中，
 * 避免把多权限引导当成单一通知权限。入参支持 String / String[] /
 * Iterable（List、Set），数组/集合中任一元素命中即命中；未知类型按 fail-open
 * 返回 false（不改变原引导）。
 */
final class NotifyGuideHooks {

    private static final String FLOAT_SHOW =
            "com.alipay.android.phone.mobilesdk.permission.guide.PermissionGuideFloatShow";
    private static final String FLOAT_REQUEST =
            "com.alipay.android.phone.mobilesdk.permission.guide.PermissionGuideRequest";
    private static final String SYS_FLOAT_MODE =
            "com.alipay.android.phone.mobilesdk.permission.guide.sys.SysPermissionGuideFloatMode";
    private static final String SYS_GUIDE_MODE =
            "com.alipay.android.phone.mobilesdk.permission.guide.sys.SysPermissionGuideMode";

    private static final String PERMISSION_BRIDGE = "pp2";
    private static final String FIELD_PERM_TYPE = "permissionType";
    private static final String PROXY_PERMISSION_TYPE = "location";
    private static final String FIELD_PERMISSION = "guidePermissionStr";
    private static final String FIELD_PERMISSION_LIST = "permissionList";

    /** Android 13+ 通知运行时权限。 */
    private static final String POST_NOTIFICATIONS = "POST_NOTIFICATIONS";
    /** 旧权限引导库使用的通知权限名（如 android.permission.NOTIFICATION）。 */
    private static final String LEGACY_NOTIFICATION = "NOTIFICATION";
    /** 旧 NOTIFICATION_* 常量族（如 NOTIFICATION_LISTENER）。 */
    private static final String LEGACY_NOTIFICATION_PREFIX = "NOTIFICATION_";
    /** 勿扰访问权限：名字含 NOTIFICATION，但不是通知发布权限，必须排除。 */
    private static final String ACCESS_NOTIFICATION_POLICY =
            "android.permission.ACCESS_NOTIFICATION_POLICY";

    /** 真机诊断日志限流窗口：同一 key 10 秒内最多一条。 */
    private static final long LOG_INTERVAL_MS = 10_000L;
    private static final int MAX_LOG_KEYS = 64;
    private static final Object LOG_LOCK = new Object();
    private static final ConcurrentMap<String, Long> LAST_LOG_MS = new ConcurrentHashMap<>();

    private NotifyGuideHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.NOTIFY_GUIDE;
        try {
            installBottomFloat(module, f, cl);
            installSysFloat(module, f, cl);
            installPermissionBridge(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /** 底部通知权限浮条：不展示。 */
    private static void installBottomFloat(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> show = HookKit.findClass(module, f, cl, FLOAT_SHOW);
        Class<?> request = HookKit.findClass(module, f, cl, FLOAT_REQUEST);
        if (show == null || request == null) {
            return;
        }
        Method guide = HookKit.findMethod(module, f, show,
                "showNotificationGuide", request);
        setAccessibleQuiet(guide);
        HookKit.install(module, f, "PermissionGuideFloatShow#showNotificationGuide", guide,
                skipVoidLogged(module, f,
                        "PermissionGuideFloatShow#showNotificationGuide"));
    }

    /** 系统浮条：只在通知权限引导时跳过，其余权限原样放行。 */
    private static void installSysFloat(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> mode = HookKit.findClass(module, f, cl, SYS_FLOAT_MODE);
        Class<?> base = HookKit.findClass(module, f, cl, SYS_GUIDE_MODE);
        if (mode == null || base == null) {
            return;
        }
        Field permission = fieldQuiet(base, FIELD_PERMISSION);
        Field permissionList = fieldQuiet(base, FIELD_PERMISSION_LIST);
        if (permission == null && permissionList == null) {
            HookKit.warn(module, f.id
                    + " skip: SysPermissionGuideMode permission fields not found");
            return;
        }
        Method core = HookKit.findMethod(module, f, mode, "showFloatWithSysCore");
        setAccessibleQuiet(core);
        HookKit.install(module, f, "SysPermissionGuideFloatMode#showFloatWithSysCore", core,
                notificationOnlySkip(module, f, permission, permissionList));
    }

    /** 权限请求参数桥：通知权限改写为定位权限。 */
    private static void installPermissionBridge(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> bridge = HookKit.findClass(module, f, cl, PERMISSION_BRIDGE);
        if (bridge == null) {
            return;
        }
        Method query = HookKit.findMethod(module, f, bridge, "i",
                Activity.class, JSONObject.class, String.class);
        HookKit.install(module, f, "pp2#i", query,
                permissionTypeRewrite(module, f));
    }

    /**
     * pp2#i 参数重写：仅 3 参且第 2 参为 JSONObject 时生效；
     * permissionType 命中通知权限才复制 JSON 并改写，否则原样 proceed。
     */
    private static XposedInterface.Hooker permissionTypeRewrite(final XposedModule module,
                                                                final Feature f) {
        final String detail = "pp2#i";
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                boolean proceeded = false;
                try {
                    HookState.entry(f);
                    List<Object> args = chain.getArgs();
                    if (args != null && args.size() == 3
                            && args.get(1) instanceof JSONObject) {
                        JSONObject request = (JSONObject) args.get(1);
                        String type = request.optString(FIELD_PERM_TYPE, "");
                        if (shouldRewritePermission(type,
                                hasLocationPermission(args.get(0)))) {
                            JSONObject patched = rewriteNotificationPermission(request);
                            Object[] newArgs = new Object[]{args.get(0), patched, args.get(2)};
                            logThrottled("rewrite:" + type,
                                    "notify guide rewrite permissionType=" + type
                                            + " -> location");
                            HookKit.hit(module, f, logLine);
                            proceeded = true;
                            // proceedWith(Object) 改的是 this；改参数必须用 proceed(Object[])。
                            return chain.proceed(newArgs);
                        }
                        logThrottled("pass-unsafe:" + type,
                                "notify guide pass (unsafe rewrite skipped)"
                                        + " permissionType=" + type);
                    } else {
                        logThrottled("pass:<no-json>",
                                "notify guide pass permissionType=<no-json>");
                    }
                    proceeded = true;
                    return chain.proceed();
                } catch (Throwable t) {
                    if (proceeded) {
                        throw t;
                    }
                    HookKit.error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * showFloatWithSysCore 的条件跳过 Hooker：读取父类字段判定是否通知权限引导，
     * 命中则不调用原方法；否则 proceed 恰好一次。
     */
    private static XposedInterface.Hooker notificationOnlySkip(final XposedModule module,
                                                               final Feature f,
                                                               final Field permission,
                                                               final Field permissionList) {
        final String detail = "SysPermissionGuideFloatMode#showFloatWithSysCore";
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                boolean proceeded = false;
                try {
                    Object self = chain.getThisObject();
                    boolean notification = isNotificationGuide(self, permission, permissionList);
                    logThrottled("fallback:" + detail,
                            "notify guide fallback " + detail
                                    + " invoked notification=" + notification);
                    if (notification) {
                        HookKit.hit(module, f, logLine);
                        return null;
                    }
                    proceeded = true;
                    return chain.proceed();
                } catch (Throwable t) {
                    if (proceeded) {
                        throw t;
                    }
                    HookKit.error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /** guidePermissionStr 或 permissionList 中任一权限命中通知权限即视为通知引导。 */
    private static boolean isNotificationGuide(Object self, Field permission,
                                               Field permissionList) {
        if (self == null) {
            return false;
        }
        if (permission != null) {
            try {
                if (isNotificationPermission(permission.get(self))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 字段读不到：继续检查 permissionList
            }
        }
        if (permissionList != null) {
            try {
                if (isNotificationPermission(permissionList.get(self))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 字段读不到：按非通知引导处理
            }
        }
        return false;
    }

    /**
     * 是否是已知通知权限。String 走精确单值匹配；String[]/Iterable（List、Set）
     * 中任一元素命中即命中；其他未知类型按 fail-open 返回 false，不拦截原引导。
     */
    static boolean isNotificationPermission(Object permission) {
        if (permission == null) {
            return false;
        }
        if (permission instanceof String) {
            return isNotificationPermissionName((String) permission);
        }
        if (permission instanceof String[]) {
            for (String item : (String[]) permission) {
                if (isNotificationPermissionName(item)) {
                    return true;
                }
            }
            return false;
        }
        if (permission instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) permission) {
                if (item instanceof String
                        && isNotificationPermissionName((String) item)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * 单个权限名精确匹配：忽略首尾空白与大小写；复合串直接不命中。
     * 只认 POST_NOTIFICATIONS 与旧的 NOTIFICATION / NOTIFICATION_* 常量，
     * 并显式排除 ACCESS_NOTIFICATION_POLICY。
     */
    private static boolean isNotificationPermissionName(String permission) {
        if (permission == null) {
            return false;
        }
        String trimmed = permission.trim();
        if (trimmed.isEmpty() || isCompositePermission(trimmed)) {
            return false;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (ACCESS_NOTIFICATION_POLICY.equals(upper)) {
            return false;
        }
        // 旧常量可能带包名前缀（android.permission.NOTIFICATION），
        // 也可能只有简单名（NOTIFICATION）；按最后一段比较。
        String leaf = upper.substring(upper.lastIndexOf('.') + 1);
        return POST_NOTIFICATIONS.equals(leaf)
                || LEGACY_NOTIFICATION.equals(leaf)
                || leaf.startsWith(LEGACY_NOTIFICATION_PREFIX);
    }

    /** 复合权限串：含逗号/分号/竖线或内部空白（调用前已 trim）。 */
    private static boolean isCompositePermission(String permission) {
        if (permission == null) {
            return true;
        }
        for (int i = 0; i < permission.length(); i++) {
            char c = permission.charAt(i);
            if (c == ',' || c == ';' || c == '|' || Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单一通知权限：复合串（如 "POST_NOTIFICATIONS,CAMERA"）绝不能整体改写成
     * location，否则会丢掉相机/存储等权限，或把未知权限串变成另一次弹窗。
     * 与 isNotificationPermission 共用同一套精确匹配规则。
     */
    static boolean isSingleNotificationPermission(String permission) {
        return isNotificationPermission(permission);
    }

    /**
     * 只有“单一通知权限 + 定位已授权”才允许改写。
     * 定位未授权时改成 location 会新增定位权限弹窗，宁可不改写。
     */
    static boolean shouldRewritePermission(String permissionType, boolean locationGranted) {
        return isSingleNotificationPermission(permissionType) && locationGranted;
    }

    /**
     * 定位权限是否已经完整授予：fine 与 coarse 都已授权。
     * Android 12+ 只给 approximate 时 coarse 为 true、fine 为 false，
     * 此时再请求 location 仍可能弹“精确位置”升级框，因此按未授权处理。
     */
    private static boolean hasLocationPermission(Object activityLike) {
        if (!(activityLike instanceof Activity)) {
            return false;
        }
        try {
            Activity activity = (Activity) activityLike;
            return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    && activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 复制 JSON 并把 permissionType 改写为 location；返回新对象，不改入参。
     * 纯函数，hooker 与单测共用。
     */
    static JSONObject rewriteNotificationPermission(JSONObject request) throws JSONException {
        if (request == null) {
            return null;
        }
        JSONObject patched = new JSONObject(request.toString());
        patched.put(FIELD_PERM_TYPE, PROXY_PERMISSION_TYPE);
        return patched;
    }

    /**
     * 底部浮条兜底挂载点：行为与 HookKit.skipVoid 一致（不调用原方法），
     * 额外打一条限流日志，便于真机判断到底走了哪条路径。
     */
    private static XposedInterface.Hooker skipVoidLogged(final XposedModule module,
                                                          final Feature f,
                                                          final String detail) {
        final String logLine = f.id + " hit " + detail;
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    logThrottled("fallback:" + detail,
                            "notify guide fallback " + detail + " invoked");
                    HookKit.hit(module, f, logLine);
                    return null;
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error " + detail, t);
                    return chain.proceed();
                }
            }
        };
    }

    /**
     * 真机诊断日志限流：同一 key 10 秒内最多一条。
     * 日志/时钟异常一律吞掉，绝不影响 hook 行为。
     */
    private static void logThrottled(String key, String message) {
        try {
            long now = SystemClock.elapsedRealtime();
            synchronized (LOG_LOCK) {
                Long last = LAST_LOG_MS.get(key);
                if (last != null && now - last < LOG_INTERVAL_MS) {
                    return;
                }
                if (LAST_LOG_MS.size() >= MAX_LOG_KEYS) {
                    LAST_LOG_MS.clear();
                }
                LAST_LOG_MS.put(key, now);
            }
            Log.i(HookKit.TAG, message);
        } catch (Throwable ignored) {
            // 日志失败不影响 hook 行为
        }
    }

    private static Field fieldQuiet(Class<?> cls, String name) {
        try {
            Field field = cls.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setAccessibleQuiet(Method method) {
        if (method == null) {
            return;
        }
        try {
            method.setAccessible(true);
        } catch (Throwable ignored) {
            // 个别 ROM 拒绝 setAccessible；仍按原方法尝试 hook
        }
    }
}
