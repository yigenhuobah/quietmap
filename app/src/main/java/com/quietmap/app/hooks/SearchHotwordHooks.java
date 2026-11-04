package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * search_hotword 搜索热词断源（搜索框回退普通 placeholder）。
 *
 * 四个挂载点的完整签名由 anchors.json 提供：
 * 1) search_preset_word_request —— BaseSearchBar#requestPreSetWord(Z, String)V
 *    唯一重载。方法体把 sceneData 通过 JsFunctionCallback 交给 AJX/JS，真正的
 *    热词请求在前端发起；不调用它即不发起热词请求。
 * 2) search_preset_word_count —— PreSetWordManager 的本地热词历史计数
 *    （方法体含 "setPreSetWord obj=" 与 "yyyy-MM-dd"；参数类型随版本混淆改名，
 *    由锚点表 s 字段描述）。
 * 3) search_preset_word_tracker —— 天擎第三方曝光上报（17.02 起从
 *    PreSetWordManager 挪到 BaseSearchBar#uploadPreSetWordTracker）。
 * 4) search_preset_word_response —— ModuleSlideContainer 的 AJX 回填入口
 *    （Java 侧无调用点，由 JS bridge 反射调用），作为第二道保险一起断掉。
 *
 * 红线：不动 ISearchService.sugg（联想词），联想输入体验保持原样。
 * 全部 skipVoid：不调用原方法；定位失败只记 skip，其余照常挂（fail-open）。
 */
final class SearchHotwordHooks {

    private SearchHotwordHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SEARCH_HOTWORD;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            Method request = anchors.method(AnchorKeys.SEARCH_PRESET_WORD_REQUEST);
            HookKit.install(module, f, AnchorKeys.SEARCH_PRESET_WORD_REQUEST, request,
                    HookKit.skipVoid(module, f, AnchorKeys.SEARCH_PRESET_WORD_REQUEST));

            Method count = anchors.method(AnchorKeys.SEARCH_PRESET_WORD_COUNT);
            HookKit.install(module, f, AnchorKeys.SEARCH_PRESET_WORD_COUNT, count,
                    HookKit.skipVoid(module, f, AnchorKeys.SEARCH_PRESET_WORD_COUNT));

            Method tracker = anchors.method(AnchorKeys.SEARCH_PRESET_WORD_TRACKER);
            HookKit.install(module, f, AnchorKeys.SEARCH_PRESET_WORD_TRACKER, tracker,
                    HookKit.skipVoid(module, f, AnchorKeys.SEARCH_PRESET_WORD_TRACKER));

            Method response = anchors.method(AnchorKeys.SEARCH_PRESET_WORD_RESPONSE);
            HookKit.install(module, f, AnchorKeys.SEARCH_PRESET_WORD_RESPONSE, response,
                    HookKit.skipVoid(module, f, AnchorKeys.SEARCH_PRESET_WORD_RESPONSE));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
