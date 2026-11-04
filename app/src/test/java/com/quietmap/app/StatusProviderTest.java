package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * failedFeatureCount 的纯逻辑部分：FILE_STALE、TTL、未回传不计数；
 * 已开启且（reg 为空或 crit=1）才计数，普通 skip 不再计入横幅。
 * Context/SharedPreferences 胶水不在这里覆盖。
 */
public class StatusProviderTest {

    private static final long NOW = 1_000_000_000L;

    @Test
    public void fileStaleNeverCountsAsFailure() {
        Map<String, String> data = new TreeMap<>();
        // 在 PROVIDER 状态下这条会被判失败（有回传、无 reg、有 skip）。
        data.put("skip.splash_gate", "class not found");
        ReportState stale = ReportState.fromStaleFile(entry(data));

        assertEquals(ReportState.Source.FILE_STALE, stale.source());
        assertEquals("过期文件只用于展示，不得计入失败数", 0,
                StatusProvider.failedFeatureCount(stale, f -> true, NOW));
    }

    @Test
    public void providerStateCountsOnlyReportedAndEnabledFailures() {
        Map<String, String> data = new TreeMap<>();
        data.put("skip.splash_gate", "class not found");
        ReportState provider = ReportState.fromProvider(data, NOW);

        assertEquals(1, StatusProvider.failedFeatureCount(provider, f -> true, NOW));
        assertEquals("用户关闭的功能即使 skip 也不计入", 0,
                StatusProvider.failedFeatureCount(provider, f -> false, NOW));
        assertEquals("没有回传的功能不计入", 0,
                StatusProvider.failedFeatureCount(ReportState.none(), f -> true, NOW));
    }

    @Test
    public void registeredFeatureWithOptionalSkipIsNotFailure() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "canShowSplash");
        data.put("skip.splash_gate", "方法未找到 x");
        ReportState provider = ReportState.fromProvider(data, NOW);
        assertEquals("有成功挂载点时可选 skip 只展示，不计入横幅", 0,
                StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    @Test
    public void registeredFeatureWithoutSkipIsNotFailure() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "canShowSplash"), NOW);
        assertEquals(0, StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    @Test
    public void criticalFeatureCountsEvenWhenRegistered() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "canShowSplash");
        data.put("crit.splash_gate", "1");
        ReportState provider = ReportState.fromProvider(data, NOW);
        assertEquals("crit=1 表示整体失效，必须计入失败", 1,
                StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    @Test
    public void emptyCritDoesNotCountAsFailure() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "canShowSplash");
        data.put("crit.splash_gate", "");
        ReportState provider = ReportState.fromProvider(data, NOW);
        assertEquals(0, StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    @Test
    public void staleReportDataIsIgnored() {
        Map<String, String> data = new TreeMap<>();
        data.put("skip.splash_gate", "class not found");
        ReportState provider = ReportState.fromProvider(
                data, NOW - ReportProtocol.DEFAULT_TTL_MS - 1L);
        assertEquals("超过 TTL 说明目标进程可能已退出，按未知处理", 0,
                StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    @Test
    public void freshReportDataAtTtlBoundaryStillCounts() {
        Map<String, String> data = new TreeMap<>();
        data.put("skip.splash_gate", "class not found");
        ReportState provider = ReportState.fromProvider(
                data, NOW - ReportProtocol.DEFAULT_TTL_MS);
        assertEquals(1, StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    /**
     * crash0/1/2（最近崩溃，crash0 最新）：写入侧经白名单后走 reg/skip 的文本
     * 覆盖路径（applyReport 通用分支，无 Context 不在本文件直测，语义由
     * ReportProtocolTest 的 valueChanged/acceptFromProcess 断言锁定）；这里锁定
     * 读取侧：crash 键经 sanitize 保留、供诊断页展示，且不影响失败统计。
     */
    @Test
    public void crashKeysSurviveSanitizeAndDoNotAffectFailureCount() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "canShowSplash");
        data.put(ReportProtocol.KEY_CRASH_0,
                "1700000000000|main|java.lang.NullPointerException|a.b(C.java:1)|d.e(F.java:2)");
        data.put(ReportProtocol.KEY_CRASH_1, "");
        data.put(ReportProtocol.KEY_CRASH_2, "x".repeat(257));
        ReportState provider = ReportState.fromProvider(data, NOW);

        assertEquals("crash 键经白名单/sanitize 后必须保留",
                data.get(ReportProtocol.KEY_CRASH_0),
                provider.value("", ReportProtocol.KEY_CRASH_0));
        assertEquals("无崩溃槽位的空串原样保留（诊断页据此隐藏该行）", "",
                provider.value("", ReportProtocol.KEY_CRASH_1));
        assertNull("超过 256 的 crash 值必须被读侧丢弃",
                provider.value("", ReportProtocol.KEY_CRASH_2));
        assertEquals("崩溃记录不改变失败统计", 0,
                StatusProvider.failedFeatureCount(provider, f -> true, NOW));
    }

    // ------------------------------------------------------- 崩溃路径同步落盘

    @Test
    public void syncApplyReportUsesCommitAndReturnsChanged() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("reg.splash_gate", "canShowSplash");
        data.put("hit.splash_gate", "3");

        Boolean applied = StatusProvider.applyReport(sp, data, "t1-test", true,
                ReportProtocol.SOURCE_PROVIDER, true);

        assertEquals(Boolean.TRUE, applied);
        assertEquals("sync=true 必须走 commit()", 1, sp.commitCount());
        assertEquals("sync=true 不得走 apply()", 0, sp.applyCount());
        assertEquals("canShowSplash", sp.values().get("v:reg.splash_gate"));
        assertEquals(3L, sp.values().get("v:hit.splash_gate"));
        assertEquals("t1-test", sp.values().get(StatusProvider.PREFS_SESSION));
    }

    @Test
    public void syncApplyReportCommitFailureReturnsNullAndWritesNothing() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        sp.setCommitResult(false);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("reg.splash_gate", "canShowSplash");

        Boolean applied = StatusProvider.applyReport(sp, data, "t1-test", true,
                ReportProtocol.SOURCE_PROVIDER, true);

        assertNull("commit 失败必须返回 null，调用方才能继续走文件兜底", applied);
        assertEquals("commit 失败时不得更新内存值", 0, sp.values().size());
        assertEquals(1, sp.commitCount());
        assertEquals(0, sp.applyCount());
    }

    @Test
    public void normalApplyReportKeepsAsyncApply() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("reg.splash_gate", "canShowSplash");

        Boolean applied = StatusProvider.applyReport(sp, data, "t1-test", true,
                ReportProtocol.SOURCE_PROVIDER, false);

        assertEquals(Boolean.TRUE, applied);
        assertEquals("常规心跳不得同步 commit", 0, sp.commitCount());
        assertEquals(1, sp.applyCount());
        assertEquals("canShowSplash", sp.values().get("v:reg.splash_gate"));
    }

    @Test
    public void syncApplyReportCommitsSessionMarkerEvenWithoutDataChange() {
        FakeSharedPreferences sp = new FakeSharedPreferences();
        sp.values().put(StatusProvider.PREFS_SESSION, "t1-test");
        sp.values().put("v:reg.splash_gate", "canShowSplash");
        sp.values().put(StatusProvider.PREFS_UPDATED_AT,
                System.currentTimeMillis());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("reg.splash_gate", "canShowSplash");

        Boolean applied = StatusProvider.applyReport(sp, data, "t1-test", true,
                ReportProtocol.SOURCE_PROVIDER, true);

        assertEquals("内容没变返回 false（没有实际数据变更）", Boolean.FALSE, applied);
        assertEquals("session 标记仍会写入，sync 下必须 commit 后才能返回",
                1, sp.commitCount());
        assertEquals(0, sp.applyCount());
    }

    private static ReportFileStore.Entry entry(Map<String, String> data) {
        return new ReportFileStore.Entry(new File("qm_report.json"),
                new ReportSnapshot(1L, "session-1", data), 1L);
    }
}
