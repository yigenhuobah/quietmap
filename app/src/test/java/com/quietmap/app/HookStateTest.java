package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

/**
 * HookState 的纯 JVM 行为：命中计数、标签排序、skip 原因保留。
 * 诊断卡与 Report 回传的快照都从这里取值，语义变了会直接反映到用户界面。
 *
 * 每个用例使用不同的 Feature：HookState 是进程级静态状态，测试方法之间不能互相污染。
 */
public class HookStateTest {

    @Test
    public void hitIncrementsCount() {
        Feature f = Feature.SPLASH_GATE;
        assertEquals(1L, HookState.hit(f));
        assertEquals(2L, HookState.hit(f));
        assertEquals(2L, HookState.hitCount(f));
    }

    @Test
    public void labelsAreSorted() {
        Feature f = Feature.SEARCH_HOTWORD;
        HookState.installed(f, "beta");
        HookState.installed(f, "alpha");
        assertEquals(Arrays.asList("alpha", "beta"), HookState.labels(f));
    }

    @Test
    public void markNotFoundKeepsLastReason() {
        Feature f = Feature.POPUP_CLEAN;
        HookState.markNotFound(f, "first");
        HookState.markNotFound(f, "second");
        assertEquals("second", HookState.skipReason(f));
    }

    @Test
    public void entryCountIsIndependentFromHitCount() {
        Feature f = Feature.NOTIFY_GUIDE;
        HookState.entry(f);
        HookState.entry(f);
        assertEquals(2L, HookState.entryCount(f));
        assertEquals(0L, HookState.hitCount(f));
        HookState.hit(f);
        assertEquals("hit 不影响入口计数", 2L, HookState.entryCount(f));
        assertEquals(1L, HookState.hitCount(f));
    }

    @Test
    public void untouchedFeatureHasEmptyState() {
        Feature f = Feature.H5_INSTALL_APK;
        assertEquals(0L, HookState.hitCount(f));
        assertEquals(0L, HookState.entryCount(f));
        assertEquals(0, HookState.installedCount(f));
        assertFalse(HookState.hasCriticalSkip(f));
        assertTrue("未触碰的 Feature 不应有标签", HookState.labels(f).isEmpty());
        assertNull("未触碰的 Feature 不应有 skip 原因", HookState.skipReason(f));
    }

    @Test
    public void markNotFoundCriticalAlsoRecordsSkipAndCriticalFlag() {
        Feature f = Feature.TRACK_SUPPRESS;
        assertFalse(HookState.hasCriticalSkip(f));
        HookState.markNotFound(f, "方法未找到 x");
        assertFalse("普通 skip 不是 critical", HookState.hasCriticalSkip(f));

        HookState.markNotFoundCritical(f, "无可用挂载点");
        assertTrue(HookState.hasCriticalSkip(f));
        assertEquals("无可用挂载点", HookState.skipReason(f));
    }

    @Test
    public void installedCountTracksDistinctLabels() {
        Feature f = Feature.H5_AD_LOG;
        assertEquals(0, HookState.installedCount(f));
        HookState.markNotFound(f, "类未找到 X");
        assertEquals("只有 skip 没有成功挂载", 0, HookState.installedCount(f));

        HookState.installed(f, "a");
        HookState.installed(f, "a");
        HookState.installed(f, "b");
        assertEquals("labels 去重后计数", 2, HookState.installedCount(f));
        assertEquals(2, HookState.labels(f).size());
    }

    @Test
    public void clearFailuresRemovesSkipAndCriticalButKeepsLabelsAndHits() {
        Feature f = Feature.POPUP_UPGRADE;
        HookState.markNotFound(f, "类未找到 X");
        HookState.markNotFoundCritical(f, "无可用挂载点");
        HookState.installed(f, "a");
        HookState.hit(f);
        assertTrue(HookState.hasCriticalSkip(f));
        assertEquals("无可用挂载点", HookState.skipReason(f));

        HookState.clearFailures(f);
        assertFalse("清除后不得再报 critical（重试成功后 crit=1 必须消失）",
                HookState.hasCriticalSkip(f));
        assertNull("清除后 skip 原因应为空", HookState.skipReason(f));
        assertEquals("labels 不受清除影响", 1, HookState.installedCount(f));
        assertEquals("hit 计数不受清除影响", 1L, HookState.hitCount(f));
    }

    @Test
    public void clearFailuresOnUntouchedFeatureIsNoOp() {
        Feature f = Feature.MAP_FLOAT_LAYER;
        HookState.clearFailures(f);
        assertFalse(HookState.hasCriticalSkip(f));
        assertNull(HookState.skipReason(f));
    }

    /**
     * DexKit 明细仍由模块保存（core 只有单条 lastFailure）：必须保持去重、
     * 排序、精确撤销，以及「只记 DexKit 失败的功能也进入 touched()」的语义，
     * 否则 Report.snapshot 不会上报该功能，诊断页看不到这条失败。
     */
    @Test
    public void dexKitFailureIsTrackedSeparatelyTouchesFeatureAndClears() {
        Feature f = Feature.MSG_IMAGE_TEXT;
        HookState.clearFailures(f);
        try {
            HookState.markDexKitFailure(f, "DexKit b: boom");
            HookState.markDexKitFailure(f, "DexKit a: miss");
            HookState.markDexKitFailure(f, "DexKit a: miss");
            HookState.markDexKitFailure(f, null);

            assertEquals("明细必须去重并按字典序输出",
                    Arrays.asList("DexKit a: miss", "DexKit b: boom"),
                    HookState.dexKitFailures(f));
            assertTrue("只记 DexKit 明细的功能也必须进入 touched()",
                    HookState.touched().contains(f));

            HookState.clearDexKitFailure(f, "DexKit a: miss");
            assertEquals(Collections.singletonList("DexKit b: boom"),
                    HookState.dexKitFailures(f));
        } finally {
            HookState.clearFailures(f);
        }
        assertTrue("clearFailures 必须清空 DexKit 明细",
                HookState.dexKitFailures(f).isEmpty());
    }

    /**
     * 回归：clearFailures 可能在 skipReason 的 isEmpty() 与 Collections.min()
     * 之间并发清空 dexkitFailures。修复前集合报告非空、迭代器却已为空，
     * Collections.min 抛 NoSuchElementException（被 Report 的 catch 吞掉，
     * 表现为漏报一轮）。这里用「isEmpty 恒 false、迭代器为空」的集合精确
     * 复现该交错，要求组合逻辑不抛异常。
     */
    @Test
    public void skipReasonToleratesFailuresClearedBetweenEmptyCheckAndMin() {
        Set<String> clearedMidRead = new AbstractSet<String>() {
            @Override
            public Iterator<String> iterator() {
                return Collections.emptyIterator();
            }

            @Override
            public int size() {
                return 0;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }
        };

        assertNull("明细被并发清空时不得抛异常",
                HookState.composeSkipReason(null, clearedMidRead));
        assertEquals("调用方 skip 文本仍须保留", "锚定失败",
                HookState.composeSkipReason("锚定失败", clearedMidRead));
    }
}
