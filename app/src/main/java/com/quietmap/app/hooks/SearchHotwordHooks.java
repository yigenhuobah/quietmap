package com.quietmap.app.hooks;

import com.quietmap.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * search_hotword 搜索热词断源（搜索框回退普通 placeholder）。
 *
 * dexsql 实测（高德 17.02.0.2010）：
 * 1) BaseSearchBar#requestPreSetWord(Z, String)V —— 唯一重载（调研清单一致）。
 *    方法体把 sceneData 通过 JsFunctionCallback 交给 AJX/JS，真正的热词请求在
 *    前端发起；不调用它即不发起热词请求。
 * 2) PreSetWordManager#d(p75)V —— 本地热词历史计数（方法体含 "setPreSetWord obj="
 *    与 "yyyy-MM-dd"；17.01.0.2012 为 d(n55)V，参数类型混淆改名）。
 *    天擎第三方曝光上报从 17.01 的 PreSetWordManager#e(String, n55)V **挪到了**
 *    BaseSearchBar#uploadPreSetWordTracker(p75)V（方法体仍含
 *    "thirdTrackRequest 天擎 曝光"、"preset word"、"shadedWord" 等，17.02 起
 *    由 setPreSetWord 直接调用；PreSetWordManager 里已无 e 方法）。
 *    p75 是默认包混淆类（描述符 Lp75;，17.01 为 Ln55;），找不到时只跳过这几项。
 * 3) ModuleSlideContainer#responsePreSetWords(String)V —— AJX 回填入口
 *    （Java 侧无调用点，由 JS bridge 反射调用），作为第二道保险一起断掉。
 *    AbstractModuleSlidecontainer 的同名方法是抽象基类实现，运行时由子类
 *    ModuleSlideContainer 覆写并分派，因此挂子类即可。
 *
 * 红线：不动 ISearchService.sugg（联想词），联想输入体验保持原样。
 * 全部 skipVoid：不调用原方法；定位失败只记 skip，其余照常挂（fail-open）。
 */
final class SearchHotwordHooks {

    private static final String SEARCH_BAR =
            "com.autonavi.bundle.amaphome.components.searchbar.BaseSearchBar";
    private static final String MANAGER =
            "com.autonavi.bundle.uitemplate.preset.PreSetWordManager";
    /** PreSetWordManager#d 与 BaseSearchBar#uploadPreSetWordTracker 的参数类型：
     *  默认包混淆类 Lp75;（Java 名 p75；17.01.0.2012 为 Ln55;）。 */
    private static final String PRESET_WORD_PARAM = "p75";
    private static final String SLIDE_CONTAINER =
            "com.autonavi.bundle.uitemplate.ajx.ModuleSlideContainer";

    private SearchHotwordHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.SEARCH_HOTWORD;
        try {
            Class<?> bar = HookKit.findClass(module, f, cl, SEARCH_BAR);
            if (bar != null) {
                Method request = HookKit.findMethod(module, f, bar,
                        "requestPreSetWord", boolean.class, String.class);
                HookKit.install(module, f, "BaseSearchBar#requestPreSetWord", request,
                        HookKit.skipVoid(module, f, "BaseSearchBar#requestPreSetWord"));
            }

            Class<?> manager = HookKit.findClass(module, f, cl, MANAGER);
            Class<?> presetParam = HookKit.findClass(module, f, cl, PRESET_WORD_PARAM);
            if (manager != null && presetParam != null) {
                Method d = HookKit.findMethod(module, f, manager, "d", presetParam);
                HookKit.install(module, f, "PreSetWordManager#d", d,
                        HookKit.skipVoid(module, f, "PreSetWordManager#d"));
            }
            // 天擎曝光上报：17.02 起从 PreSetWordManager#e(String, n55) 挪到
            // BaseSearchBar#uploadPreSetWordTracker(p75)，挂在搜索框类上。
            if (bar != null && presetParam != null) {
                Method tracker = HookKit.findMethod(module, f, bar,
                        "uploadPreSetWordTracker", presetParam);
                HookKit.install(module, f, "BaseSearchBar#uploadPreSetWordTracker", tracker,
                        HookKit.skipVoid(module, f,
                                "BaseSearchBar#uploadPreSetWordTracker"));
            }

            Class<?> slide = HookKit.findClass(module, f, cl, SLIDE_CONTAINER);
            if (slide != null) {
                Method response = HookKit.findMethod(module, f, slide,
                        "responsePreSetWords", String.class);
                HookKit.install(module, f, "ModuleSlideContainer#responsePreSetWords", response,
                        HookKit.skipVoid(module, f,
                                "ModuleSlideContainer#responsePreSetWords"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }
}
