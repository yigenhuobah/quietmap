package com.quietmap.app.dexkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import org.junit.Test;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DexKitLocator 的纯 JVM 逻辑：扫描失败负缓存的 key 组成（S4）、
 * native 初始化失败的 60 秒重试窗口判定、失败记账（WARN 文本/HookState 明细）。
 * key 必须区分"类/方法/返回/参数/anchor 集合"任一维度的差异——同一 anchor
 * 集合可能被不同目标 hint 复用，key 不含查询身份就会让 A 的负缓存误杀 B。
 * findMethod/selectMatchingMethod 依赖 android.util.Log 与 dexkit native 桥
 * （JVM 单测里 Log 桩抛 Stub!、native 桥不可用），真实扫描/歧义拒绝路径本身
 * 不在 JVM 单测覆盖范围内；native 初始化失败路径可测（无 libdexkit 时必然
 * 失败），查询阶段的 catch 通过注入 finder/selector 覆盖（见
 * {@link DexKitLocator#findMethodAttempts}）。
 * 失败记账通过 {@link DexKitLocator#warnFailure} / {@link DexKitLocator#recordFailure}
 * 直接验证。
 * （时间由调用方传入，见 {@link DexKitLocator#shouldShortCircuitNativeInit}）。
 */
public class DexKitLocatorTest {

    /** 与 anchors.json 的 dexkit 字段一致；表值由 AnchorContractTest 锁定。 */
    private static final String SPLASH_ANCHOR = "不发送请求";
    private static final String IMAGE_TEXT_DL = "invalid download item: ";
    private static final String IMAGE_TEXT_DL_CLASS = "ImageTextMessageDownloader";

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
                new Class<?>[]{int.class, boolean.class}, SPLASH_ANCHOR);
        String k2 = DexKitLocator.missCacheKey(null, "attemptRequestData", void.class,
                new Class<?>[]{int.class, boolean.class}, SPLASH_ANCHOR);
        assertEquals("同一查询必须得到同一 key", k1, k2);
    }

    @Test
    public void missCacheKeySeparatesDifferentQueryIdentities() {
        String base = DexKitLocator.missCacheKey(null, "run", void.class,
                new Class<?>[0], IMAGE_TEXT_DL, IMAGE_TEXT_DL_CLASS);

        assertNotEquals("类名 hint 必须进 key（同 anchor 集合可被不同目标复用）",
                base, DexKitLocator.missCacheKey("n8", "run", void.class,
                        new Class<?>[0], IMAGE_TEXT_DL, IMAGE_TEXT_DL_CLASS));
        assertNotEquals("方法名必须进 key",
                base, DexKitLocator.missCacheKey(null, "attemptRequestData", void.class,
                        new Class<?>[0], IMAGE_TEXT_DL, IMAGE_TEXT_DL_CLASS));
        assertNotEquals("返回类型必须进 key",
                base, DexKitLocator.missCacheKey(null, "run", int.class,
                        new Class<?>[0], IMAGE_TEXT_DL, IMAGE_TEXT_DL_CLASS));
        assertNotEquals("参数类型必须进 key",
                base, DexKitLocator.missCacheKey(null, "run", void.class,
                        new Class<?>[]{int.class},
                        IMAGE_TEXT_DL, IMAGE_TEXT_DL_CLASS));
        assertNotEquals("anchor 集合必须进 key",
                base, DexKitLocator.missCacheKey(null, "run", void.class,
                        new Class<?>[0], IMAGE_TEXT_DL));
    }

    @Test
    public void missCacheKeyNormalizesNullHintsToEmpty() {
        // null 与空串 hint 语义相同（都不加过滤），必须映射到同一 key，
        // 否则同一查询会因 hint 写法不同而重复全量扫描。
        assertEquals(
                DexKitLocator.missCacheKey(null, "run", void.class, null, "x"),
                DexKitLocator.missCacheKey("", "run", void.class, new Class<?>[0], "x"));
    }

    @Test
    public void nativeInitRetryWindowShortCircuitsOnlyWithin60sWindow() {
        assertEquals("重试窗口规格写死 60 秒", 60_000L,
                DexKitLocator.NATIVE_INIT_RETRY_WINDOW_MS);

        final long failedAt = 1_000_000L;
        assertTrue("窗口内（<60s）必须短路，避免每次兜底查询重复 load/create",
                DexKitLocator.shouldShortCircuitNativeInit(failedAt, failedAt + 59_999L));
        assertTrue("失败瞬间也在窗口内",
                DexKitLocator.shouldShortCircuitNativeInit(failedAt, failedAt));
        assertFalse("恰好到窗（60s）应放行重试",
                DexKitLocator.shouldShortCircuitNativeInit(failedAt, failedAt + 60_000L));
        assertFalse("过窗后必须放行重试（瞬时失败可自愈）",
                DexKitLocator.shouldShortCircuitNativeInit(failedAt, failedAt + 60_001L));
    }

    @Test
    public void nativeInitRetryWindowNeverShortCircuitsBeforeAnyFailure() {
        // 0 = 从未失败：不得短路，否则首次 getBridge 永远拿不到 bridge。
        assertFalse("从未失败（0 时间戳）不得短路",
                DexKitLocator.shouldShortCircuitNativeInit(0L, 0L));
        assertFalse(DexKitLocator.shouldShortCircuitNativeInit(0L, 123_456_789L));
    }

    // ------------------------------------------------------- 失败记账（HookState）

    @Test
    public void queryLabelKeepsClassMethodSignatureAndAnchorsForWarnings() {
        assertEquals("Lah5#attemptRequestData(int,boolean):void [anchors="
                        + SPLASH_ANCHOR + "]",
                DexKitLocator.queryLabel("Lah5", "attemptRequestData",
                        void.class, new Class<?>[]{int.class, boolean.class},
                        SPLASH_ANCHOR));
        assertEquals("run():void", DexKitLocator.queryLabel(null, "run", void.class,
                new Class<?>[0]));
        assertEquals("[anchors=only-anchor]",
                DexKitLocator.queryLabel(null, null, null, null, "only-anchor"));
        assertEquals("():void [anchors=only-anchor]",
                DexKitLocator.queryLabel(null, null, void.class,
                        new Class<?>[0], "only-anchor"));
        assertEquals("<empty query>", DexKitLocator.queryLabel(null, null, null, null));
    }

    /**
     * 回归（第二轮复核 #11）：失败明细按 queryLabel 精确匹配撤销，因此
     * 同一个 class#method 的返回类型、参数类型、anchor 集合任一不同都必须是
     * 不同查询身份；否则一个查询 attempt2 成功会清掉另一个查询仍有效的失败，
     * skipReason 变 null 后 Hooks.markCriticalIfNoMount 不再标 critical。
     */
    @Test
    public void queryLabelSeparatesSameClassMethodWithDifferentSignaturesAndAnchors() {
        String base = DexKitLocator.queryLabel("Lah5", "attemptRequestData", void.class,
                new Class<?>[]{int.class}, SPLASH_ANCHOR);

        assertNotEquals("返回类型必须进查询身份",
                base, DexKitLocator.queryLabel("Lah5", "attemptRequestData", boolean.class,
                        new Class<?>[]{int.class}, SPLASH_ANCHOR));
        assertNotEquals("参数类型必须进查询身份",
                base, DexKitLocator.queryLabel("Lah5", "attemptRequestData", void.class,
                        new Class<?>[]{int.class, boolean.class},
                        SPLASH_ANCHOR));
        assertNotEquals("anchor 集合必须进查询身份",
                base, DexKitLocator.queryLabel("Lah5", "attemptRequestData", void.class,
                        new Class<?>[]{int.class}, "another-anchor"));
    }

    /**
     * 回归（第二轮复核 #11）：两个同 class#method 不同签名的查询共享失败串时，
     * attempt2 成功撤销本查询的 attempt1 明细不得连带清掉另一个查询的失败。
     * 这里让两个查询的失败明细文本完全相同（旧实现只按 class#method 记账，
     * 会视为同一条并一起删掉），断言另一个查询的失败仍在、skipReason 仍非 null
     * （markCriticalIfNoMount 的判据）。
     */
    @Test
    public void attempt2SuccessDoesNotClearOtherQueryWithSameClassMethodDifferentSignature()
            throws Exception {
        Feature f = Feature.SPLASH_REQUEST;
        HookState.clearFailures(f);
        try {
            String queryVoid = DexKitLocator.queryLabel("Lah5", "attemptRequestData",
                    void.class, new Class<?>[]{int.class}, SPLASH_ANCHOR);
            String queryBoolean = DexKitLocator.queryLabel("Lah5", "attemptRequestData",
                    boolean.class, new Class<?>[]{int.class},
                    SPLASH_ANCHOR);
            assertNotEquals("同 class#method 不同签名必须得到不同查询身份",
                    queryVoid, queryBoolean);

            // 另一签名的查询先失败；明细与下面 attempt1 完全相同，
            // 旧实现（身份只有 class#method）会把它误当成同一条明细清除。
            String sharedDetail = "attempt 1 error: IllegalStateException: boom";
            DexKitLocator.warnFailure(f, queryVoid, sharedDetail);
            assertNotNull("前置：另一查询的失败必须已记录", HookState.skipReason(f));

            Method expected = String.class.getMethod("length");
            AtomicInteger calls = new AtomicInteger();
            Method found = DexKitLocator.findMethodAttempts(
                    f, queryBoolean, "Lah5", "attemptRequestData",
                    new String[]{SPLASH_ANCHOR},
                    q -> {
                        if (calls.incrementAndGet() == 1) {
                            throw new IllegalStateException("boom");
                        }
                        return oneCandidate();
                    },
                    (candidates, attemptLabel) -> expected);

            assertSame("attempt2 选出的方法必须原样返回", expected, found);
            assertEquals("attempt1 抛异常后必须执行 attempt2", 2, calls.get());
            List<String> remaining = HookState.dexKitFailures(f);
            assertEquals("attempt2 成功只撤销本查询的 attempt1 明细，"
                            + "不得清掉同 class#method 另一签名的失败: " + remaining,
                    1, remaining.size());
            assertTrue("保留的必须是另一签名的失败: " + remaining,
                    remaining.get(0).contains(queryVoid));
            assertNotNull("另一签名的失败仍是有效 skip；skipReason 变 null 会让 "
                            + "markCriticalIfNoMount 漏标 critical",
                    HookState.skipReason(f));
        } finally {
            HookState.clearFailures(f);
        }
    }

    @Test
    public void dexKitFailureIsRecordedAndComposedIntoSkipReason() {
        Feature f = Feature.SPLASH_REQUEST;
        HookState.clearFailures(f);
        try {
            // warnFailure 是生产 catch 路径的统一收口：JVM 单测里 android.util.Log
            // 是抛 Stub! 的桩，这里同时验证「日志失败不破坏 fail-open 与记账」。
            DexKitLocator.warnFailure(f, "Lah5#attemptRequestData",
                    "attempt 1 error: IllegalStateException: boom");
            assertTrue("DexKit 失败必须写入 HookState 明细",
                    HookState.dexKitFailures(f).contains(
                            "DexKit Lah5#attemptRequestData: "
                                    + "attempt 1 error: IllegalStateException: boom"));

            HookState.markNotFound(f, "锚定失败");
            assertEquals("调用方 skip 必须保留，并追加 DexKit 明细",
                    "锚定失败 | DexKit Lah5#attemptRequestData: "
                            + "attempt 1 error: IllegalStateException: boom",
                    HookState.skipReason(f));
        } finally {
            HookState.clearFailures(f);
        }
        assertTrue("重试清理必须清空 DexKit 明细", HookState.dexKitFailures(f).isEmpty());
        assertNull("重试清理后不得残留 skip", HookState.skipReason(f));
    }

    @Test
    public void dexKitFailureWithoutFeatureIsNotAttributed() {
        // 公共 getBridge(ClassLoader) 路径没有功能上下文：只打日志，不得写任何 Feature。
        DexKitLocator.recordFailure(null, "native init", "UnsatisfiedLinkError");
        for (Feature f : Feature.values()) {
            assertTrue("无 Feature 上下文的失败不得归属到 " + f.id,
                    HookState.dexKitFailures(f).isEmpty());
        }
    }

    /**
     * 真实失败路径（native 初始化失败）必须 fail-open 返回 null 并记账：
     * JVM 单测没有 libdexkit，getBridge 必然失败；这条用例覆盖 findMethod →
     * getBridge → warnFailure 的完整链路，而不只是记账方法本身。
     */
    @Test
    public void findMethodNativeFailureIsRecordedAndFailsOpen() {
        Feature f = Feature.SPLASH_REQUEST;
        HookState.clearFailures(f);
        try {
            assertNull("native 不可用时必须返回 null（fail-open）",
                    DexKitLocator.findMethod(getClass().getClassLoader(), f,
                            "X", "m", void.class, new Class<?>[0], "qm-unit-test-anchor"));
            assertFalse("native 初始化/查询失败必须写入 HookState 明细",
                    HookState.dexKitFailures(f).isEmpty());
            String detail = HookState.dexKitFailures(f).get(0);
            assertTrue("失败明细必须带完整查询身份（类/方法/签名/anchor）与 native 初始化原因："
                            + detail,
                    detail.contains("X#m():void [anchors=qm-unit-test-anchor]")
                            && (detail.contains("native init failed")
                                || detail.contains("bridge create returned null")));
        } finally {
            HookState.clearFailures(f);
        }
    }

    /**
     * 无效输入在触达 Log/native 之前就返回 null，不得产生虚假的失败记账。
     * native 初始化失败路径由 {@link #findMethodNativeFailureIsRecordedAndFailsOpen}
     * 覆盖；查询阶段的 catch（bridge 可用后）仍依赖真实 dexkit native，JVM 不可达。
     */
    @Test
    public void invalidInputReturnsNullWithoutFailureAccounting() {
        Feature f = Feature.SPLASH_REQUEST;
        HookState.clearFailures(f);
        try {
            assertNull("cl=null 必须 fail-open",
                    DexKitLocator.findMethod(null, f, "X", "m",
                            void.class, new Class<?>[0], "anchor"));
            assertNull("anchors 全空必须 fail-open",
                    DexKitLocator.findMethod(getClass().getClassLoader(), f, "X", "m",
                            void.class, new Class<?>[0], new String[0]));
            assertTrue("前置校验失败不是 DexKit 扫描失败，不得记账",
                    HookState.dexKitFailures(f).isEmpty());
        } finally {
            HookState.clearFailures(f);
        }
    }

    /**
     * 回归：attempt1 抛异常会先记账，attempt2 成功兜底后必须撤销 attempt1 的
     * 明细，否则功能已定位成功仍会在诊断页展示假 skip。通过注入 finder/selector
     * 在纯 JVM 上覆盖真实 attempt1 → attempt2 流程（无需 dexkit native 桥）。
     */
    @Test
    public void attempt2SuccessClearsAttempt1FailureAccounting() throws Exception {
        Feature f = Feature.SPLASH_REQUEST;
        HookState.clearFailures(f);
        try {
            Method expected = String.class.getMethod("length");
            AtomicInteger calls = new AtomicInteger();
            Method found = DexKitLocator.findMethodAttempts(
                    f, "Lah5#attemptRequestData", "Lah5", "attemptRequestData",
                    new String[]{"anchor"},
                    query -> {
                        if (calls.incrementAndGet() == 1) {
                            throw new IllegalStateException("attempt1 boom");
                        }
                        return oneCandidate();
                    },
                    (candidates, attemptLabel) -> expected);

            assertSame("attempt2 选出的方法必须原样返回", expected, found);
            assertEquals("attempt1 抛异常后必须执行 attempt2", 2, calls.get());
            assertTrue("attempt2 成功后不得残留 attempt1 的失败记账（假 skip）: "
                            + HookState.dexKitFailures(f),
                    HookState.dexKitFailures(f).isEmpty());
            assertNull("无调用方 skip 时诊断文本应为空", HookState.skipReason(f));
        } finally {
            HookState.clearFailures(f);
        }
    }

    /**
     * 撤销 attempt1 明细必须精确匹配：同功能的其他 DexKit 失败（如 native
     * 初始化失败、其他查询）不得被连带清除。
     */
    @Test
    public void attempt2SuccessKeepsOtherDexKitFailures() throws Exception {
        Feature f = Feature.SPLASH_REQUEST;
        HookState.clearFailures(f);
        try {
            String query = "Lah5#attemptRequestData";
            DexKitLocator.warnFailure(f, query, "native init failed: UnsatisfiedLinkError");
            Method expected = String.class.getMethod("length");
            AtomicInteger calls = new AtomicInteger();
            Method found = DexKitLocator.findMethodAttempts(
                    f, query, "Lah5", "attemptRequestData",
                    new String[]{"anchor"},
                    q -> {
                        if (calls.incrementAndGet() == 1) {
                            throw new IllegalStateException("attempt1 boom");
                        }
                        return oneCandidate();
                    },
                    (candidates, attemptLabel) -> expected);

            assertSame(expected, found);
            List<String> remaining = HookState.dexKitFailures(f);
            assertEquals("只撤销 attempt1 明细，其他失败必须保留: " + remaining,
                    1, remaining.size());
            assertTrue("native 初始化失败不得被误清: " + remaining,
                    remaining.get(0).contains("native init failed"));
        } finally {
            HookState.clearFailures(f);
        }
    }

    /** 构造一个非空候选列表；选择器已注入，候选内容不参与上述用例。 */
    private static MethodDataList oneCandidate() {
        MethodDataList candidates = new MethodDataList();
        candidates.add(null);
        return candidates;
    }
}
