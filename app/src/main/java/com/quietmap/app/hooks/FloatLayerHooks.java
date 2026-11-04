package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * map_float_layer 地图运营浮层断源（首页地图不再注册运营挂件）。
 *
 * FloatingLayerManager 的注册入口由 anchors.json 的 float_layer_register 给出：
 * a(String, String, JSONObject, FloatingLayerManager$b)String —— 返回注册结果的 key。
 *
 * 返回常量选 ""（空串）而不是 null —— 依据来自两个调用点的 smali：
 *   1) FloatingLayerManager 的调用方 b;->f(Activity, JSONObject)V
 *      把返回值直接 JSONObject.put("key", result) 后 JsAdapter.callJs 回传前端。
 *      org.json 的 put(name, null) 会删除该键，JS 侧拿不到 "key" 可能走异常分支；
 *      返回 "" 保持键存在、值为空串，不改变控制流。
 *   2) AppBackManager$2;->a(AppBackResponse)V 完全不使用返回值。
 * 因此 "" 对两处调用点都安全。
 *
 * 单例 d() 不 hook：调用点会立即解引用（d().a(...)、d().b 等），返回 null 必 NPE。
 *
 * 只挂注册入口：constValue 返回 ""，不 proceed；异常回退 proceed 恰好一次（fail-open）。
 */
final class FloatLayerHooks {

    private FloatLayerHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.MAP_FLOAT_LAYER;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            Method register = anchors.method(AnchorKeys.FLOAT_LAYER_REGISTER);
            HookKit.install(module, f, AnchorKeys.FLOAT_LAYER_REGISTER, register,
                    HookKit.constValue(module, f, AnchorKeys.FLOAT_LAYER_REGISTER, ""));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
