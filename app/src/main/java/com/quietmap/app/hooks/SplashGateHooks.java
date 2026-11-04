package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * splash_gate 开屏闸门（双保险，官方 NO_SPLASH 分支 + 展示层兜底）。
 *
 * 1) splash_gate_can_show —— BootBizDataPreloaderImpl#canShowSplash() 返回
 *    java.lang.Boolean.FALSE：g.o 内 invoke-interface 后与 Boolean.FALSE 比较走
 *    SplashFinishReason.NO_SPLASH 分支，产品自带的"不展示开屏"路径，最优雅。
 *    返回类型是 java.lang.Boolean 对象：绝不能返回 null（调用方会拆箱 NPE），
 *    一律返回装箱常量 Boolean.FALSE。
 * 2) splash_gate_try_show —— SplashScreenServiceImpl#tryShowSplashView(int, String)
 *    不调用：展示层兜底，开屏展示唯一入口，失败最多不显示开屏。
 *
 * 红线：不 hook com.autonavi.minimap.g（g.o 318 指令大方法）与 SplashFrequencyController。
 */
final class SplashGateHooks {

    private SplashGateHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SPLASH_GATE;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            Method canShowSplash = anchors.method(AnchorKeys.SPLASH_GATE_CAN_SHOW);
            // 返回类型是对象 java.lang.Boolean：装箱常量 FALSE，绝不返回 null。
            HookKit.install(module, f, AnchorKeys.SPLASH_GATE_CAN_SHOW, canShowSplash,
                    HookKit.constValue(module, f, AnchorKeys.SPLASH_GATE_CAN_SHOW,
                            Boolean.FALSE));

            Method tryShowSplashView = anchors.method(AnchorKeys.SPLASH_GATE_TRY_SHOW);
            HookKit.install(module, f, AnchorKeys.SPLASH_GATE_TRY_SHOW, tryShowSplashView,
                    HookKit.skipVoid(module, f, AnchorKeys.SPLASH_GATE_TRY_SHOW));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
