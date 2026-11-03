package com.quietmap.app.dexkit;

import android.util.Log;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DexKit 动态扫描定位引擎（模式参考 cloudmusictool 的 dexkit 包，API 按 dexkit 2.3.0 实测签名写）。
 * 懒加载 DexKitBridge 并按 ClassLoader 缓存；当类名/方法名被混淆改名时按方法体字符串锚点定位。
 * 严格禁止引用任何 androidx 类型。
 *
 * 失败语义：任何一步失败都返回 null（调用方 fail-open skip），
 * 绝不向外抛——DexKit native 加载失败不得影响宿主进程。
 * 失败同时打 WARN（带查询/类名）并写入 {@link HookState} 的 DexKit 明细，
 * 避免「零挂载 + 只剩一句锚定失败」的诊断信息丢失。
 */
public final class DexKitLocator {

    private static final String TAG = "QuietMap";

    private static final Map<ClassLoader, DexKitBridge> sBridgeCache = new WeakHashMap<>();
    /** 扫描失败负缓存：key 含查询身份与 anchor 集合，按 ClassLoader 隔离。 */
    private static final Map<ClassLoader, Map<String, Boolean>> sMissCache = new WeakHashMap<>();
    /**
     * native 加载/初始化最近一次失败的时间戳（elapsedRealtime，0=未失败）。
     * 不再永久短路：60 秒重试窗口内仍短路，过窗放行重试并刷新时间戳——
     * 一次瞬时失败（如启动早期低内存 dlopen 失败）不应在进程内永久禁用 DexKit。
     */
    private static volatile long sNativeInitFailedAtMs;
    /** native 初始化失败后的重试窗口时长（毫秒）。包内可见供单测。 */
    static final long NATIVE_INIT_RETRY_WINDOW_MS = 60_000L;
    private static volatile String sModuleNativeDir = null;
    /** 非主进程（:locationservice/:widgetProvider）关闭 DexKit，避免 native 全 dex 扫描开销。 */
    private static volatile boolean sEnabled = true;

    private DexKitLocator() {
    }

    /** 模块 APK 的 nativeLibraryDir（libdexkit.so 解压位置），onModuleLoaded 时注入。 */
    public static void setModuleNativeDir(String dir) {
        sModuleNativeDir = dir;
    }

    /**
     * 由 {@code Hooks.installAll} 在安装前按进程设置：主进程 true，其余进程 false。
     * 必须在 {@code app.hooks} 包可见，故为 public。
     */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /**
     * native 初始化失败重试窗口判定：窗口内仍短路，过窗（含恰好到窗）放行重试。
     * failedAtMs=0 表示从未失败，永不短路。时间由调用方传入（生产端用
     * SystemClock.elapsedRealtime 单调时钟），保持纯 JVM 可测。包内可见供单测。
     */
    static boolean shouldShortCircuitNativeInit(long failedAtMs, long nowMs) {
        return failedAtMs != 0 && nowMs - failedAtMs < NATIVE_INIT_RETRY_WINDOW_MS;
    }

    public static synchronized DexKitBridge getBridge(ClassLoader cl) {
        return getBridge(cl, null, "getBridge");
    }

    /**
     * 带查询上下文的 bridge 获取：native 初始化失败时除 WARN 外写入
     * {@link HookState}（feature 为 null 时只打日志，公共 {@link #getBridge(ClassLoader)}
     * 路径没有功能归属）。仍保持 fail-open：任何异常都只返回 null。
     */
    private static synchronized DexKitBridge getBridge(ClassLoader cl, Feature feature, String query) {
        if (cl == null) {
            return null;
        }
        DexKitBridge bridge = sBridgeCache.get(cl);
        if (bridge == null) {
            if (shouldShortCircuitNativeInit(sNativeInitFailedAtMs,
                    elapsedRealtimeMs())) {
                // native 上次初始化失败且仍在 60 秒重试窗口内：短路，
                // 避免每次兜底查询重复 load/create；过窗后放行重试。
                warnFailure(feature, query, "native init failed within retry window");
                return null;
            }
            try {
                boolean loaded = false;
                if (sModuleNativeDir != null && !sModuleNativeDir.isEmpty()) {
                    try {
                        java.io.File soFile = new java.io.File(sModuleNativeDir, "libdexkit.so");
                        if (soFile.exists()) {
                            System.load(soFile.getAbsolutePath());
                            loaded = true;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "DexKit load from moduleNativeDir failed: " + describe(t));
                    }
                }
                if (!loaded) {
                    try {
                        System.loadLibrary("dexkit");
                    } catch (Throwable t) {
                        Log.w(TAG, "DexKit loadLibrary failed: " + describe(t));
                    }
                }
                bridge = DexKitBridge.create(cl, false);
                if (bridge == null) {
                    // create 返回 null 与抛异常同属初始化失败：同样进入重试窗口。
                    sNativeInitFailedAtMs = elapsedRealtimeMs();
                    warnFailure(feature, query, "bridge create returned null");
                    return null;
                }
                sBridgeCache.put(cl, bridge);
                Log.i(TAG, "DexKitBridge initialized for ClassLoader: " + cl);
            } catch (Throwable t) {
                // 记录失败时间戳：窗口内仍短路，过窗放行重试并刷新时间戳
                // （getBridge 已 synchronized，重试成本可控）。so 与宿主 ClassLoader
                // 确实不变时重试依旧失败，只多付一次窗口间隔的 load/create 开销。
                sNativeInitFailedAtMs = elapsedRealtimeMs();
                warnFailure(feature, query, "native init failed: " + describe(t));
            }
        }
        return bridge;
    }

    /**
     * 失败收口：WARN 带查询身份，并写入 HookState 的 DexKit 明细。
     * feature 为 null（无功能上下文）时只打日志。日志出口自身抛错也不能破坏
     * fail-open（JVM 单测的 Log 桩正是这种情况）。包内可见供单测验证记账。
     */
    static void warnFailure(Feature feature, String query, String detail) {
        String reason = failureReason(query, detail);
        try {
            Log.w(TAG, reason);
        } catch (Throwable ignored) {
            // 日志不可用不改变 fail-open 语义。
        }
        recordFailure(feature, query, detail);
    }

    /** 只记账不写日志：包内可见，供单测直接验证 HookState 明细。 */
    static void recordFailure(Feature feature, String query, String detail) {
        if (feature == null) {
            return;
        }
        HookState.markDexKitFailure(feature, failureReason(query, detail));
    }

    /**
     * 撤销一条已记录的 DexKit 失败：attempt1 抛异常、attempt2 成功兜底时调用。
     *
     * attempt1 的异常只是第一种查询方式失败，最终已由 attempt2 定位成功；
     * 留在 HookState 会让诊断页继续展示假 skip。只删除完全匹配的明细，
     * 同功能其他查询/native 初始化失败不受影响。包内可见供单测。
     */
    static void clearFailure(Feature feature, String query, String detail) {
        if (feature == null || detail == null) {
            return;
        }
        HookState.clearDexKitFailure(feature, failureReason(query, detail));
    }

    /** 失败明细的统一文本格式；记账与撤销必须用同一格式才能精确匹配。 */
    private static String failureReason(String query, String detail) {
        return "DexKit " + query + ": " + detail;
    }

    /**
     * 查询身份文本：类名#方法名 + 完整签名（参数/返回）+ anchor 集合。
     *
     * 失败记账按该文本精确匹配撤销（{@link #clearFailure}），因此身份必须包含
     * 参与定位的全部维度：同一个 class#method 的不同签名（或不同 anchor 集合）
     * 是不同查询，只按 class#method 生成文本会让它们共享失败串——其中一个
     * attempt2 成功时会把另一个仍然有效的失败一起清掉，skipReason 变 null 后
     * {@code Hooks.markCriticalIfNoMount} 不再标记 critical。
     * 两者都缺时退化为 anchor 集合（仍带签名），保证 WARN 与 HookState 明细
     * 始终能定位到具体查询。包内可见供单测。
     */
    static String queryLabel(String classHint, String methodName, Class<?> returnType,
                             Class<?>[] paramTypes, String... anchors) {
        StringBuilder sb = new StringBuilder();
        if (classHint != null && !classHint.isEmpty()) {
            sb.append(classHint);
        }
        if (methodName != null && !methodName.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('#');
            }
            sb.append(methodName);
        }
        String params = paramSignature(paramTypes);
        // 参数为空数组与 null 语义相同（都不限制参数），归一化为 ()。
        if (sb.length() > 0 || returnType != null || !params.isEmpty()) {
            sb.append('(').append(params).append(')');
            if (returnType != null) {
                sb.append(':').append(returnType.getName());
            }
        }
        String key = anchorKey(anchors);
        if (key != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("[anchors=").append(key).append(']');
        }
        if (sb.length() == 0) {
            sb.append("<empty query>");
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /**
     * 单调时钟；取不到时返回 0（0 表示“从未失败”，只会放宽重试，不会永久短路）。
     * 保证 getBridge 的失败路径在任何环境下都不向外抛。
     */
    private static long elapsedRealtimeMs() {
        try {
            return android.os.SystemClock.elapsedRealtime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 按方法体字符串锚点定位方法。两次尝试：
     *  第一次：declaredClass(classHint) + name(methodName) + anchors；
     *  第二次仅在第一次查询抛异常时执行：只按 anchors 查询，不限制类名/方法名，
     *    但对候选做 returnType + paramTypes 强校验（锚点串可能被多个方法共用，
     *    例如 "不发送请求" 同时出现在 Lzg5;->run()V，必须靠签名消歧）。
     *  第一次正常结束但未选出方法（0 结果 / 签名不符 / 候选歧义）时不再执行第二次：
     *  无名字约束的全量重扫只可能多找出名字对不上 methodName 的候选（目标被改名的
     *  场景），该自愈路径每次 miss 都要多付一次启动期全量扫描，改由调用方的类名
     *  列表（check-mounts 体检出新名后追加）兜底；失败结果进负缓存，同一查询
     *  不再重复扫描。候选通过签名校验后还要求唯一（见 selectMatchingMethod）：
     *  多个同签名候选一律拒绝猜测，按未命中处理，由调用方走可见的 markNotFound。
     *  第一次不限制类名（classHint 为空），但仍会按 methodName + anchors 查询。
     *
     * @param feature 失败记账归属的功能；为 null 时只打 WARN 不写 HookState
     *                （保留无功能上下文的调用方式）。
     */
    public static Method findMethod(ClassLoader cl, Feature feature, String classHint,
                                    String methodName, Class<?> returnType,
                                    Class<?>[] paramTypes, String... anchors) {
        if (!sEnabled) {
            // 非主进程直接跳过：调用方此前已尝试类名/方法名直取，DexKit 只是最后的兜底。
            Log.w(TAG, "dexkit disabled in non-main process");
            return null;
        }
        String query = queryLabel(classHint, methodName, returnType, paramTypes, anchors);
        if (cl == null || anchors == null || anchors.length == 0) {
            return null;
        }
        boolean hasValidAnchor = false;
        for (String a : anchors) {
            if (a != null && !a.isEmpty()) {
                hasValidAnchor = true;
                break;
            }
        }
        if (!hasValidAnchor) {
            return null;
        }

        // 失败负缓存：同一次查询（类/方法/签名/anchor 集合）未命中后不再重复全量扫描。
        String missKey = missCacheKey(classHint, methodName, returnType, paramTypes, anchors);
        Map<String, Boolean> missCache = missCache(cl);
        if (missCache != null && missCache.containsKey(missKey)) {
            return null;
        }

        DexKitBridge bridge = getBridge(cl, feature, query);
        if (bridge == null) {
            // native 不可用由 getBridge 的失败短路兜住（WARN + HookState 记账已在那里完成），
            // 这里不记负缓存，过窗后仍可重试。
            return null;
        }

        Method found = findMethodAttempts(feature, query, classHint, methodName, anchors,
                bridge::findMethod,
                (candidates, attemptLabel) -> selectMatchingMethod(
                        cl, candidates, returnType, paramTypes,
                        attemptLabel, classHint, methodName));
        if (found == null) {
            // 两次尝试都未定位到方法（attempt1 正常未命中或 attempt2 仍失败）：
            // 记录负缓存，同一查询不再重复全量扫描。
            recordMiss(missCache, missKey);
        }
        return found;
    }

    /** 查询执行器：生产实现为 {@code bridge::findMethod}，JVM 单测可注入假实现。 */
    @FunctionalInterface
    interface MethodListFinder {
        MethodDataList find(FindMethod query) throws Throwable;
    }

    /** 候选选择器：生产实现为 {@code selectMatchingMethod}，JVM 单测可注入假实现。 */
    @FunctionalInterface
    interface MethodSelector {
        Method select(MethodDataList candidates, String attemptLabel) throws Throwable;
    }

    /**
     * attempt1/attempt2 查询逻辑；包内可见并允许注入 finder/selector，使
     * 「attempt1 抛异常 → attempt2 成功」的失败记账撤销可在纯 JVM 单测覆盖
     * （真实路径依赖 dexkit native 桥，JVM 不可达）。
     *
     * 返回 null 表示两次尝试都未定位到方法，负缓存由调用方记录。
     */
    static Method findMethodAttempts(Feature feature, String query, String classHint,
                                     String methodName, String[] anchors,
                                     MethodListFinder finder, MethodSelector selector) {
        // 第一次：类名 + 方法名 + 锚点（当前版本类名还在时最准）
        boolean attempt1Threw = false;
        String attempt1Failure = null;
        try {
            FindMethod query1 = FindMethod.create();
            MethodMatcher matcher1 = MethodMatcher.create();
            if (classHint != null && !classHint.isEmpty()) {
                matcher1.declaredClass(classHint, StringMatchType.Equals, false);
            }
            if (methodName != null && !methodName.isEmpty()) {
                matcher1.name(methodName, StringMatchType.Equals, false);
            }
            for (String anchor : anchors) {
                if (anchor != null && !anchor.isEmpty()) {
                    matcher1.addUsingString(anchor, StringMatchType.Contains, false);
                }
            }
            query1.matcher(matcher1);
            MethodDataList list1 = finder.find(query1);
            if (list1 != null && !list1.isEmpty()) {
                Method m = selector.select(list1, "attempt1");
                if (m != null) {
                    return m;
                }
                Log.w(TAG, "DexKit findMethod attempt 1: " + list1.size()
                        + " candidate(s) for anchors on " + classHint + "#" + methodName
                        + " but none passed signature/uniqueness validation");
            }
        } catch (Throwable t) {
            attempt1Threw = true;
            attempt1Failure = "attempt 1 error: " + describe(t);
            warnFailure(feature, query, attempt1Failure);
        }

        if (!attempt1Threw) {
            // 第一次查询正常结束（0 结果 / 签名不符 / 歧义拒绝）时不再执行第二次：
            // 无名字约束的重扫只可能多找出名字对不上 methodName 的候选（目标被改名
            // 的场景），该自愈路径每次 miss 都要多付一次启动期全量扫描，改由调用方
            // 的类名列表（check-mounts 体检出新名后追加）兜底。记负缓存后直接返回，
            // 同一查询不再重复扫描。
            warnFailure(feature, query,
                    "miss (attempt 1 finished normally); attempt 2 skipped, miss cached");
            return null;
        }

        // 第二次（仅第一次查询抛异常时）：只按 anchors 全量查询。
        // 不能加 name/class 过滤：查询本身抛异常时没有更窄的过滤可用，
        // 候选仍需通过 returnType + paramTypes 强校验。
        try {
            FindMethod query2 = FindMethod.create();
            MethodMatcher matcher2 = MethodMatcher.create();
            for (String anchor : anchors) {
                if (anchor != null && !anchor.isEmpty()) {
                    matcher2.addUsingString(anchor, StringMatchType.Contains, false);
                }
            }
            query2.matcher(matcher2);
            MethodDataList list2 = finder.find(query2);
            if (list2 != null && !list2.isEmpty()) {
                Method m = selector.select(list2, "attempt2");
                if (m != null) {
                    // attempt1 的异常已被 attempt2 成功兜底：撤销 attempt1 的失败记账，
                    // 否则定位成功后诊断页仍会展示假 skip（失败横幅误报）。
                    clearFailure(feature, query, attempt1Failure);
                    return m;
                }
            } else {
                Log.w(TAG, "DexKit findMethod attempt 2: 0 candidates for anchors on "
                        + query);
            }
        } catch (Throwable t) {
            warnFailure(feature, query, "attempt 2 error: " + describe(t));
        }

        warnFailure(feature, query, "miss after attempt 2");
        return null;
    }

    private static Method selectMatchingMethod(ClassLoader cl, MethodDataList list,
                                               Class<?> returnType, Class<?>[] paramTypes,
                                               String attemptLabel, String targetClass,
                                               String targetMethod) {
        int count = list.size();
        List<Method> valid = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MethodData data = list.get(i);
            try {
                Method m = data.getMethodInstance(cl);
                if (returnType != null && !isReturnTypeCompatible(returnType, m.getReturnType())) {
                    Log.w(TAG, "DexKit " + attemptLabel + " candidate " + i + " ("
                            + m.getDeclaringClass().getName() + "#" + m.getName()
                            + ") returnType mismatch: expected " + returnType.getName()
                            + ", got " + m.getReturnType().getName() + "; discarded");
                    continue;
                }
                if (paramTypes != null && !Arrays.equals(paramTypes, m.getParameterTypes())) {
                    Log.w(TAG, "DexKit " + attemptLabel + " candidate " + i + " ("
                            + m.getDeclaringClass().getName() + "#" + m.getName()
                            + ") paramTypes mismatch: expected " + Arrays.toString(paramTypes)
                            + ", got " + Arrays.toString(m.getParameterTypes()) + "; discarded");
                    continue;
                }
                valid.add(m);
            } catch (Throwable t) {
                Log.w(TAG, "DexKit " + attemptLabel + " getMethodInstance error: " + t.getMessage());
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        if (valid.size() > 1) {
            // 锚点串被多个同签名方法共用时，取首个会静默挂错方法（日志显示命中但
            // 功能实际失效）。returnType/paramTypes 校验已经跑过仍剩多个候选，说明
            // 签名无法消歧：拒绝猜测，按未命中处理，由调用方走可见的 markNotFound，
            // 并把准确类名补进调用方的类名列表。
            StringBuilder names = new StringBuilder();
            for (Method m : valid) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(m.getDeclaringClass().getName()).append('#').append(m.getName());
            }
            Log.w(TAG, "DexKit " + attemptLabel + " ambiguous: " + valid.size()
                    + " candidates share the requested signature for " + targetClass
                    + "#" + targetMethod + " [" + names + "]; refusing to guess, treated as miss");
            return null;
        }
        Method chosen = valid.get(0);
        Log.i(TAG, "DexKit " + attemptLabel + " chosen (candidates=" + count + ", valid="
                + valid.size() + ", chosen=" + chosen.getDeclaringClass().getName()
                + "#" + chosen.getName() + ", targetHint=" + targetClass + "#" + targetMethod + ")");
        return chosen;
    }

    private static boolean isReturnTypeCompatible(Class<?> expected, Class<?> actual) {
        if (expected == null || actual == null) {
            return true;
        }
        if (expected == actual || expected.equals(actual)) {
            return true;
        }
        if (expected == boolean.class && actual == Boolean.class) return true;
        if (expected == Boolean.class && actual == boolean.class) return true;
        if (expected == int.class && actual == Integer.class) return true;
        if (expected == Integer.class && actual == int.class) return true;
        if (expected == long.class && actual == Long.class) return true;
        if (expected == Long.class && actual == long.class) return true;
        return false;
    }

    /**
     * 扫描失败负缓存 key：查询身份（类/方法/返回/参数）+ anchor 集合。
     * 类名/方法名 hint 必须进 key：同一 anchor 集合可能被不同目标复用。
     * 包内可见供单测。
     */
    static String missCacheKey(String classHint, String methodName, Class<?> returnType,
                               Class<?>[] paramTypes, String... anchors) {
        return "m\u0002" + (classHint == null ? "" : classHint) + "\u0002"
                + (methodName == null ? "" : methodName) + "\u0002"
                + (returnType == null ? "" : returnType.getName()) + "\u0002"
                + paramSignature(paramTypes) + "\u0002" + anchorKey(anchors);
    }

    private static Map<String, Boolean> missCache(ClassLoader cl) {
        if (cl == null) {
            return null;
        }
        synchronized (sMissCache) {
            Map<String, Boolean> map = sMissCache.get(cl);
            if (map == null) {
                map = new ConcurrentHashMap<>();
                sMissCache.put(cl, map);
            }
            return map;
        }
    }

    private static void recordMiss(Map<String, Boolean> missCache, String key) {
        if (missCache != null) {
            missCache.put(key, Boolean.TRUE);
        }
    }

    /** 参数类型签名串：数组为 null 记为空串，元素为 null 记为 "?"。包内可见供单测。 */
    static String paramSignature(Class<?>[] paramTypes) {
        if (paramTypes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Class<?> type : paramTypes) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(type == null ? "?" : type.getName());
        }
        return sb.toString();
    }

    /** anchor 集合 key：过滤 null/空串后按出现顺序用 \u0001 连接；全空返回 null。包内可见供单测。 */
    static String anchorKey(String... anchors) {
        if (anchors == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String a : anchors) {
            if (a != null && !a.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\u0001');
                }
                sb.append(a);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
