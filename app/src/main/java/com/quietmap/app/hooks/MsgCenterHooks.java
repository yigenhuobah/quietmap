package com.quietmap.app.hooks;

import android.util.Log;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * msg_center 消息中心净化（UI-06）。
 *
 * 真机逆向结论（高德 17.01.0.2012，2026-10-27）：
 *
 * 1) 消息页是 AJX 页
 *    path://amap_bundle_messagetab/src/pages/message_tab/MessageTab.page.js。
 *    页面进入时由 JS 通过 AJX 模块分发（AbstractModule#callMethod）调用：
 *    - AjxModuleBadge#getAllSessionList(boolean, JsFunctionCallback)
 *    - AjxModuleBadge#getBadgeInfo("messagetab")（我的页消息图标角标）
 *    - AjxModuleLocalStorage#getItemSync("message_tab", "recentlyMsgList-<uid>")
 *    真机日志实证：getAllSessionList 的 SessionCallback#onResult 返回
 *    {"convList":[...]}，其中 unreadNum 之和正好是消息图标角标 14；
 *    消息页列表（打卡消息通知/地点评论/高德达人/足迹动态/公共交通动态/
 *    高德账号安全/高德酒店等）全部来自该 convList。
 *
 * 2) 旧的 MessageBoxManager / ModuleMessageBox / MsgboxService 四个挂载点
 *    只覆盖首页消息盒/旧消息盒；0.7.0 真机命中 hasNewMessage 后消息页列表和
 *    角标仍不变。UI-02 的 getMessages 钩子在消息页进入时也命中，但同样
 *    不影响 convList。因此 UI-06 改为直接拦截 AJX bridge 数据入口。
 *
 * 3) 本实现只改 Java/AJX bridge 的返回值，不改 JS 资源、不伪造页面：
 *    - getAllSessionList：只对消息页/我的页，直接回调 {"convList":[]}，
 *      不执行原方法；JS 收到空会话列表后渲染「暂无新消息」空态。
 *    - getBadgeInfo("messagetab")：只对消息页/我的页，返回 {"value":0}，
 *      消息图标角标归零。
 *    - getItemSync("message_tab", "recentlyMsgList-*")：返回 "[]"，
 *      清掉最近消息本地缓存；setItemSync 对同一 key 返回 true 不落盘，
 *      避免下次进入又从缓存恢复。
 *
 * 线程：getAllSessionList 由 AJX 调用线程（真机实测为后台线程）同步进入；
 * 原实现也是在 AjxModuleBadge$b.onResult 的同一线程调用
 * JsFunctionCallback#callback，这里不额外切线程。
 *
 * fail-open：
 * - 非消息页/我的页：一律 proceed 原方法，不影响首页、搜索等页面；
 * - 回调为 null、页面路径取不到、类/方法缺失：getAllSessionList/getBadgeInfo
 *   proceed 原方法；getItemSync/setItemSync 例外——namespace/key 已限定
 *   message_tab/recentlyMsgList-*（见 isRecentListKey），页面路径取不到时
 *   按刻意保留的 key-only 回退继续拦截，只影响消息页自己的最近消息缓存
 *   （见 shouldInterceptRecentList 注释与 MsgCenterHooksTest 的回归锁定）；
 * - 回调已开始执行后抛异常：不再 proceed，避免 JS 收到两次结果。
 *
 * 注意：会话列表包含账号安全、公交动态等真实通知，本项开启时会一并隐藏；
 * 设置页可单独关闭本项，关闭后恢复原始列表和角标。
 */
final class MsgCenterHooks {

    private static final String AJX_MODULE_BADGE =
            "com.amap.bundle.badgesystem.ajxmodule.AjxModuleBadge";
    private static final String AJX_MODULE_LOCAL_STORAGE =
            "com.autonavi.minimap.ajx3.modules.internalmodules.AjxModuleLocalStorage";
    private static final String JS_FUNCTION_CALLBACK =
            "com.autonavi.minimap.ajx3.core.JsFunctionCallback";

    /** getAllSessionList 回调的空会话列表 JSON（JS 侧按 {"convList":[...]} 解析）。 */
    static final String EMPTY_SESSION_LIST_JSON = "{\"convList\":[]}";
    /** getBadgeInfo("messagetab") 的零角标 JSON。 */
    static final String ZERO_BADGE_JSON = "{\"value\":0}";
    /** 最近消息缓存的空 JSON 数组。 */
    static final String EMPTY_RECENT_LIST_JSON = "[]";

    static final String MESSAGE_TAB_NAMESPACE = "message_tab";
    static final String RECENT_LIST_KEY_PREFIX = "recentlyMsgList-";
    static final String MESSAGE_TAB_BADGE_ID = "messagetab";

    private static final String TAG = "QuietMap";
    /**
     * jsPath 失败诊断：UI-06 的所有挂载点共享，整个进程只上报一次。
     * 否则 getAllSessionList/getBadgeInfo 每次调用都会覆盖 skip 原因，
     * 诊断卡只能看到“registered/entry”而看不到真正的失败原因。
     */
    private static final AtomicBoolean sJsPathWarned = new AtomicBoolean();

    private MsgCenterHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.MSG_CENTER;
        try {
            installSessionList(module, f, cl);
            installBadgeInfo(module, f, cl);
            installRecentListCache(module, f, cl);
            Batch6Probe.install(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * 会话列表：AjxModuleBadge#getAllSessionList(boolean, JsFunctionCallback)V。
     * 只对消息页/我的页回调空列表，其他页面原样放行。
     */
    private static void installSessionList(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> badge = HookKit.findClass(module, f, cl, AJX_MODULE_BADGE);
        Class<?> callback = HookKit.findClass(module, f, cl, JS_FUNCTION_CALLBACK);
        if (badge == null || callback == null) {
            return;
        }
        Method getAll = HookKit.findMethod(module, f, badge,
                "getAllSessionList", boolean.class, callback);
        Method callbackMethod = HookKit.findMethodQuiet(callback, "callback", Object[].class);
        if (callbackMethod == null) {
            HookKit.warn(module, f.id
                    + " skip: JsFunctionCallback#callback(Object[]) signature not found");
            return;
        }
        setAccessibleQuiet(module, f, callbackMethod);
        HookKit.install(module, f, "AjxModuleBadge#getAllSessionList", getAll,
                emptySessionList(module, f, callbackMethod));
    }

    /**
     * 我的页消息图标角标：AjxModuleBadge#getBadgeInfo("messagetab")String。
     * 只对消息页/我的页返回 {"value":0}，其他 badgeId/页面放行。
     */
    private static void installBadgeInfo(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> badge = HookKit.findClass(module, f, cl, AJX_MODULE_BADGE);
        if (badge == null) {
            return;
        }
        Method getBadgeInfo = HookKit.findMethod(module, f, badge,
                "getBadgeInfo", String.class);
        HookKit.install(module, f, "AjxModuleBadge#getBadgeInfo", getBadgeInfo,
                zeroMessageTabBadge(module, f));
    }

    /**
     * 最近消息缓存：AjxModuleLocalStorage#getItemSync("message_tab",
     * "recentlyMsgList-<uid>")String。读到该 key 返回 "[]"；
     * setItemSync 对同一 key 直接返回 true，不写入营销消息缓存。
     */
    private static void installRecentListCache(XposedModule module, Feature f, ClassLoader cl) {
        Class<?> storage = HookKit.findClass(module, f, cl, AJX_MODULE_LOCAL_STORAGE);
        if (storage == null) {
            return;
        }
        Method getSync = HookKit.findMethod(module, f, storage,
                "getItemSync", String.class, String.class);
        HookKit.install(module, f, "AjxModuleLocalStorage#getItemSync", getSync,
                emptyRecentList(module, f));

        Method setSync = HookKit.findMethodQuiet(storage, "setItemSync",
                String.class, String.class, Object.class);
        if (setSync != null) {
            HookKit.install(module, f, "AjxModuleLocalStorage#setItemSync", setSync,
                    skipRecentListWrite(module, f));
        } else {
            HookKit.warn(module, f.id
                    + " skip: AjxModuleLocalStorage#setItemSync not found");
        }
    }

    /** 会话列表 Hooker：命中消息页/我的页时回调空列表，不执行原方法。 */
    private static XposedInterface.Hooker emptySessionList(final XposedModule module,
                                                           final Feature f,
                                                           final Method callbackMethod) {
        final String logLine = f.id + " hit AjxModuleBadge#getAllSessionList";
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                noteEntry(f);
                try {
                    // 参数顺序：(boolean, JsFunctionCallback)：回调是第 2 参。
                    Object jsCallback = chain.getArg(1);
                    if (jsCallback == null
                            || !isMessageCenterPage(jsPathForUi06(chain.getThisObject(), f))) {
                        return chain.proceed();
                    }
                    invokeSessionListCallback(callbackMethod, jsCallback);
                    hitQuiet(module, f, logLine);
                    return null;
                } catch (InvocationTargetException t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    HookKit.error(module, f.id + " session callback threw", cause);
                    // 回调可能已进入 JS，不能再执行原方法造成双重回调。
                    return null;
                } catch (Throwable t) {
                    // 非 InvocationTargetException 说明实参形状/访问权限失败，
                    // 目标方法没有真正执行，按 fail-open 放行原逻辑。
                    HookKit.error(module, f.id + " intercept error getAllSessionList", t);
                    return chain.proceed();
                }
            }
        };
    }

    /** 角标 Hooker：只把 messagetab 的角标归零，其他 badgeId 原样放行。 */
    private static XposedInterface.Hooker zeroMessageTabBadge(final XposedModule module,
                                                              final Feature f) {
        final String logLine = f.id + " hit AjxModuleBadge#getBadgeInfo";
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                noteEntry(f);
                try {
                    Object bizId = chain.getArg(0);
                    if (MESSAGE_TAB_BADGE_ID.equals(bizId)
                            && isMessageCenterPage(jsPathForUi06(chain.getThisObject(), f))) {
                        hitQuiet(module, f, logLine);
                        return ZERO_BADGE_JSON;
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error getBadgeInfo", t);
                }
                return chain.proceed();
            }
        };
    }

    /** 最近消息缓存读取 Hooker：命中固定 namespace/key 时返回空数组。 */
    private static XposedInterface.Hooker emptyRecentList(final XposedModule module,
                                                          final Feature f) {
        final String logLine = f.id + " hit AjxModuleLocalStorage#getItemSync";
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                noteEntry(f);
                try {
                    String path = jsPathForUi06(chain.getThisObject(), f);
                    if (shouldInterceptRecentList(path, chain.getArg(0), chain.getArg(1))) {
                        hitQuiet(module, f, logLine);
                        return EMPTY_RECENT_LIST_JSON;
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error getItemSync", t);
                }
                return chain.proceed();
            }
        };
    }

    /** 最近消息缓存写入 Hooker：命中固定 namespace/key 时返回 true，不落盘。 */
    private static XposedInterface.Hooker skipRecentListWrite(final XposedModule module,
                                                              final Feature f) {
        final String logLine = f.id + " hit AjxModuleLocalStorage#setItemSync";
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                noteEntry(f);
                try {
                    String path = jsPathForUi06(chain.getThisObject(), f);
                    if (shouldInterceptRecentList(path, chain.getArg(0), chain.getArg(1))) {
                        hitQuiet(module, f, logLine);
                        return Boolean.TRUE;
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error setItemSync", t);
                }
                return chain.proceed();
            }
        };
    }

    /**
     * 是否消息中心相关页面：消息 Tab（messagetab）与我的页（MinePage）。
     * 只在这两个页面清空会话列表/角标，首页、搜索等页面不受影响。
     */
    static boolean isMessageCenterPage(String jsPath) {
        if (jsPath == null) {
            return false;
        }
        String lower = jsPath.toLowerCase(Locale.ROOT);
        return lower.contains("messagetab") || lower.contains("minepage");
    }

    /** 是否最近消息缓存 key：namespace 固定 message_tab，key 以 recentlyMsgList- 开头。 */
    static boolean isRecentListKey(Object namespace, Object key) {
        return MESSAGE_TAB_NAMESPACE.equals(namespace)
                && key instanceof String
                && ((String) key).startsWith(RECENT_LIST_KEY_PREFIX);
    }

    /**
     * getItemSync/setItemSync 的页面门控：key 匹配且（页面已知为消息页/我的页，
     * 或页面路径暂时取不到时保持旧的 key-only 行为）。页面路径能取到且明确是
     * 其他页面时一律 proceed，避免误伤首页/搜索的 AJX 缓存。
     */
    static boolean shouldInterceptRecentList(String jsPath, Object namespace, Object key) {
        if (!isRecentListKey(namespace, key)) {
            return false;
        }
        return jsPath == null || isMessageCenterPage(jsPath);
    }

    /**
     * UI-06 统一的页面路径入口：jsPath 取不到时做一次诊断（warning +
     * HookState.markNotFound），后续调用不再重复上报。返回值仍为 null，
     * 调用方保持“非消息页 / key-only”的 fail-open 行为。
     */
    private static String jsPathForUi06(Object module, Feature f) {
        String path = jsPath(module);
        if (path == null) {
            reportJsPathFailureOnce(f, sJsPathWarned);
        }
        return path;
    }

    /**
     * jsPath 失败诊断的“只上报一次”决策。纯状态 helper：返回 true 表示本次
     * 是进程内首次失败，调用方据此写 warning 并 markNotFound。
     */
    static boolean shouldReportJsPathFailure(AtomicBoolean warned) {
        return warned != null && warned.compareAndSet(false, true);
    }

    /**
     * 首次 jsPath 失败时记录一次 warning 并标记 UI-06 的 skip 原因。
     * 只做诊断，不改变任何 hook 行为；日志/状态异常一律吞掉。
     */
    static boolean reportJsPathFailureOnce(Feature f, AtomicBoolean warned) {
        if (!shouldReportJsPathFailure(warned)) {
            return false;
        }
        try {
            Log.w(TAG, f.id + " jsPath unavailable; message-page gate disabled");
        } catch (Throwable ignored) {
            // 单测环境没有 Android Log 实现；日志失败不影响诊断状态
        }
        try {
            HookState.markNotFound(f, "jsPath 获取失败");
        } catch (Throwable ignored) {
            // 诊断状态失败不影响 hook
        }
        return true;
    }

    /**
     * 从 AJX module 实例取当前页面路径（IAjxContext#getJsPath）。
     * 失败返回 null；诊断由 jsPathForUi06 统一负责，调用方按 fail-open 处理。
     */
    static String jsPath(Object module) {
        if (module == null) {
            return null;
        }
        try {
            Object context = module.getClass().getMethod("getContext").invoke(module);
            if (context == null) {
                return null;
            }
            Object path = context.getClass().getMethod("getJsPath").invoke(context);
            return path instanceof String ? (String) path : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 反射调用 JsFunctionCallback#callback(Object[])，传入单个 Object[] 实参。
     *
     * 关键：参数本身是数组，Method#invoke 的可变参数必须再包一层
     * `new Object[]{ emptySessionListCallbackArgs() }`；直接传
     * `Object[]{"{\"convList\":[]}"}` 会被当成一个 String 实参，必然抛
     * IllegalArgumentException。抽成包内静态方法，单测可用真实
     * callback(Object[]) 接口验证形状。
     */
    static void invokeSessionListCallback(Method callback, Object jsCallback)
            throws InvocationTargetException, IllegalAccessException {
        callback.invoke(jsCallback, new Object[]{emptySessionListCallbackArgs()});
    }

    /** getAllSessionList 回调参数；实测 JS 侧收单个字符串 JSON。 */
    static Object[] emptySessionListCallbackArgs() {
        return new Object[]{EMPTY_SESSION_LIST_JSON};
    }

    /** setAccessible 在个别 ROM 上会抛 SecurityException；只记日志，public 方法仍可 invoke。 */
    private static void setAccessibleQuiet(XposedModule module, Feature f, Method method) {
        try {
            method.setAccessible(true);
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " setAccessible failed: "
                    + method.getDeclaringClass().getName() + "#" + method.getName());
        }
    }

    /** 命中计数与采样日志；诊断失败不影响返回值。 */
    private static void hitQuiet(XposedModule module, Feature f, String logLine) {
        try {
            HookKit.hit(module, f, logLine);
        } catch (Throwable ignored) {
            // 计数/日志失败不影响 hook
        }
    }

    /** 记录入口计数；任何异常都吞掉，绝不影响挂载点行为。 */
    private static void noteEntry(Feature f) {
        try {
            HookState.entry(f);
        } catch (Throwable ignored) {
            // 诊断计数失败不影响 hook
        }
    }
}
