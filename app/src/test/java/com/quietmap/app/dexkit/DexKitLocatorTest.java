package com.quietmap.app.dexkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * DexKitLocator 的纯 JVM 逻辑：扫描失败负缓存的 key 组成（S4）。
 * key 必须区分"类/方法/返回/参数/anchor 集合"任一维度的差异——同一 anchor
 * 集合可能被不同目标 hint 复用，key 不含查询身份就会让 A 的负缓存误杀 B。
 * findMethod/selectMatchingMethod 依赖 android.util.Log 与 dexkit native 桥
 * （JVM 单测里 Log 桩抛 Stub!、native 桥不可用），故扫描/歧义拒绝路径本身
 * 不在 JVM 单测覆盖范围内，只覆盖无副作用的 key 构造。
 */
public class DexKitLocatorTest {

    @Test
    public void anchorKeyFiltersBlankEntriesAndKeepsOrder() {
        assertEquals("a\u0001b", DexKitLocator.anchorKey("a", null, "", "b"));
        assertEquals("single", DexKitLocator.anchorKey("single"));
        assertNull("全空 anchor 集合应返回 null", DexKitLocator.anchorKey("", null));
        assertNull("null 数组应返回 null", DexKitLocator.anchorKey((String[]) null));
        assertNotEquals("anchor 顺序参与 key", DexKitLocator.anchorKey("a", "b"),
                DexKitLocator.anchorKey("b", "a"));
    }

    @Test
    public void paramSignatureDistinguishesArityOrderAndNulls() {
        assertEquals("", DexKitLocator.paramSignature(null));
        assertEquals("", DexKitLocator.paramSignature(new Class<?>[0]));
        assertEquals("int,boolean",
                DexKitLocator.paramSignature(new Class<?>[]{int.class, boolean.class}));
        assertNotEquals("参数顺序参与 key",
                DexKitLocator.paramSignature(new Class<?>[]{int.class, boolean.class}),
                DexKitLocator.paramSignature(new Class<?>[]{boolean.class, int.class}));
        assertEquals("int,?", DexKitLocator.paramSignature(new Class<?>[]{int.class, null}));
    }

    @Test
    public void missCacheKeyIsStableForIdenticalQueries() {
        String k1 = DexKitLocator.missCacheKey(null, "attemptRequestData", void.class,
                new Class<?>[]{int.class, boolean.class}, Anchors.STR_SPLASH_REQ_NO_SEND);
        String k2 = DexKitLocator.missCacheKey(null, "attemptRequestData", void.class,
                new Class<?>[]{int.class, boolean.class}, Anchors.STR_SPLASH_REQ_NO_SEND);
        assertEquals("同一查询必须得到同一 key", k1, k2);
    }

    @Test
    public void missCacheKeySeparatesDifferentQueryIdentities() {
        String base = DexKitLocator.missCacheKey(null, "run", void.class,
                new Class<?>[0], Anchors.STR_IMAGE_TEXT_DL, Anchors.STR_IMAGE_TEXT_DL_CLASS);

        assertNotEquals("类名 hint 必须进 key（同 anchor 集合可被不同目标复用）",
                base, DexKitLocator.missCacheKey("n8", "run", void.class,
                        new Class<?>[0], Anchors.STR_IMAGE_TEXT_DL, Anchors.STR_IMAGE_TEXT_DL_CLASS));
        assertNotEquals("方法名必须进 key",
                base, DexKitLocator.missCacheKey(null, "attemptRequestData", void.class,
                        new Class<?>[0], Anchors.STR_IMAGE_TEXT_DL, Anchors.STR_IMAGE_TEXT_DL_CLASS));
        assertNotEquals("返回类型必须进 key",
                base, DexKitLocator.missCacheKey(null, "run", int.class,
                        new Class<?>[0], Anchors.STR_IMAGE_TEXT_DL, Anchors.STR_IMAGE_TEXT_DL_CLASS));
        assertNotEquals("参数类型必须进 key",
                base, DexKitLocator.missCacheKey(null, "run", void.class,
                        new Class<?>[]{int.class},
                        Anchors.STR_IMAGE_TEXT_DL, Anchors.STR_IMAGE_TEXT_DL_CLASS));
        assertNotEquals("anchor 集合必须进 key",
                base, DexKitLocator.missCacheKey(null, "run", void.class,
                        new Class<?>[0], Anchors.STR_IMAGE_TEXT_DL));
    }

    @Test
    public void missCacheKeyNormalizesNullHintsToEmpty() {
        // null 与空串 hint 语义相同（都不加过滤），必须映射到同一 key，
        // 否则同一查询会因 hint 写法不同而重复全量扫描。
        assertEquals(
                DexKitLocator.missCacheKey(null, "run", void.class, null, "x"),
                DexKitLocator.missCacheKey("", "run", void.class, new Class<?>[0], "x"));
    }
}
