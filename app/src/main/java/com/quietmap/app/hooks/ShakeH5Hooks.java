package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * shake_h5 H5 / 小程序摇一摇净化。
 *
 * dexsql 实测（高德 17.01.0.2012）：
 *
 * 1) h5_shake_handle_event —— H5ShakePlugin#handleEvent(H5Event,H5BridgeContext)Z
 *    同时处理 "vibrate"（振动反馈）与 "watchShake"（注册加速度传感器）。
 *    本钩子**只拦 watchShake**：命中时直接 return true（消费事件、不注册传感器），
 *    其余 action 原样 proceed —— 保留 H5 页面正常的振动能力。
 *    若整段 skipVoid 会连 vibrate 一起废掉，故用带参数的过滤 Hooker。
 *
 * 2) h5_sensor_register_gyroscope —— H5SensorPlugin#registerGyroscopeListener(H5Event)Z
 *    私有注册方法，方法体从 SensorManager 取陀螺仪/加速度/磁场三个 sensor 并
 *    registerListener。返回常量 false（调用方 handleEvent 忽略返回值），不注册即无摇一摇。
 *
 * 3) mini_app_shake_init —— AMapAppStartShakePoint#onInitialized()V
 *    小程序摇一摇扩展点的初始化：new SensorManagerHelper 并直接
 *    SensorManager.registerListener(...)。不调用即不创建/注册监听器。
 *    （dexsql 实测 onAppStart/onAppExit 只维护 appId→callback 的 HashMap，
 *    真正的传感器注册在 onInitialized，故只挂这一处。）
 *
 * 4) shake_sensor_changed —— SensorManagerHelper#onSensorChanged(SensorEvent)V
 *    监听回调本体，作为第二道保险不调用（SensorManagerHelper 本身没有
 *    "注册方法"，注册是 onInitialized 里直接调 SensorManager 完成的）。
 *
 * 红线：**不 hook android.hardware.SensorManager 框架类**（HookKit 框架类黑名单
 * 也会拒绝），只动业务层的注册/回调入口。
 */
final class ShakeH5Hooks {

    /**
     * H5Event#getAction() 反射结果缓存（按运行时具体类）。
     * 空 Optional 是负缓存（该类没有/拿不到 getAction），避免每次事件都反复反射找方法。
     * 有界性：H5Event 具体子类在单个进程内通常唯一/极少，缓存规模可忽略。
     */
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> GET_ACTION =
            new ConcurrentHashMap<>();

    private static final Function<Class<?>, Optional<Method>> LOOKUP_GET_ACTION =
            new Function<Class<?>, Optional<Method>>() {
                @Override
                public Optional<Method> apply(Class<?> type) {
                    try {
                        // anchor-allowlist: H5 容器公开事件 API，稳定 SDK 接口而非宿主混淆挂载点
                        return Optional.of(type.getMethod("getAction"));
                    } catch (Throwable t) {
                        return Optional.empty();
                    }
                }
            };

    private ShakeH5Hooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SHAKE_H5;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            installH5ShakePlugin(module, f, anchors);
            installH5SensorPlugin(module, f, anchors);
            installMiniProgramShake(module, f, anchors);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /** H5 容器摇一摇：只吞 watchShake，保留 vibrate。 */
    private static void installH5ShakePlugin(XposedModule module, Feature f,
                                             AnchorTable anchors) {
        Method handleEvent = anchors.method(AnchorKeys.H5_SHAKE_HANDLE_EVENT);
        HookKit.install(module, f, AnchorKeys.H5_SHAKE_HANDLE_EVENT, handleEvent,
                watchShakeFilter(module, f, AnchorKeys.H5_SHAKE_HANDLE_EVENT));
    }

    /**
     * 过滤 Hooker：读取 H5Event#getAction()，命中 "watchShake" 时返回 true
     * （事件已消费、不执行原注册逻辑），其余 action 原样放行。
     * proceed 只在函数末尾调用一次；反射失败按未知 action 处理，fail-open。
     */
    private static XposedInterface.Hooker watchShakeFilter(final XposedModule module,
                                                           final Feature f,
                                                           final String label) {
        final String hitLine = f.id + " hit " + label + " watchShake";
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                try {
                    Object action = null;
                    try {
                        Object event = chain.getArg(0);
                        if (event != null) {
                            Optional<Method> getAction = GET_ACTION.computeIfAbsent(
                                    event.getClass(), LOOKUP_GET_ACTION);
                            if (getAction.isPresent()) {
                                action = getAction.get().invoke(event);
                            }
                        }
                    } catch (Throwable ignored) {
                        // 拿不到 action 就当普通事件放行
                    }
                    if ("watchShake".equals(action)) {
                        HookKit.hit(module, f, hitLine);
                        return Boolean.TRUE;
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error " + label, t);
                }
                return chain.proceed();
            }
        };
    }

    /** 陀螺仪监听注册：不注册（返回 false，调用方不检查返回值）。 */
    private static void installH5SensorPlugin(XposedModule module, Feature f,
                                              AnchorTable anchors) {
        Method register = anchors.method(AnchorKeys.H5_SENSOR_REGISTER_GYROSCOPE);
        HookKit.install(module, f, AnchorKeys.H5_SENSOR_REGISTER_GYROSCOPE, register,
                HookKit.constBool(module, f, AnchorKeys.H5_SENSOR_REGISTER_GYROSCOPE,
                        false));
    }

    /** 小程序摇一摇扩展点：onInitialized 不调用 + 监听回调不调用。 */
    private static void installMiniProgramShake(XposedModule module, Feature f,
                                               AnchorTable anchors) {
        Method onInitialized = anchors.method(AnchorKeys.MINI_APP_SHAKE_INIT);
        HookKit.install(module, f, AnchorKeys.MINI_APP_SHAKE_INIT, onInitialized,
                HookKit.skipVoid(module, f, AnchorKeys.MINI_APP_SHAKE_INIT));

        Method onSensorChanged = anchors.method(AnchorKeys.SHAKE_SENSOR_CHANGED);
        HookKit.install(module, f, AnchorKeys.SHAKE_SENSOR_CHANGED, onSensorChanged,
                HookKit.skipVoid(module, f, AnchorKeys.SHAKE_SENSOR_CHANGED));
    }
}
