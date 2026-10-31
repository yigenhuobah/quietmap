package com.quietmap.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * socket 回传的 peer 包白名单：只允许高德目标进程与模块自身。
 * UID/凭据读取是 Android 胶水，这里覆盖纯包名判断。
 */
public class ReportSocketServerTest {

    private static final String MODULE = "io.github.yigenhuobah.quietmap";

    @Test
    public void allowsTargetAndModulePackages() {
        assertTrue(ReportSocketServer.isAllowedPackage(
                new String[]{ReportPaths.TARGET_PACKAGE}, MODULE));
        assertTrue(ReportSocketServer.isAllowedPackage(
                new String[]{MODULE}, MODULE));
    }

    @Test
    public void rejectsOtherPackagesAndNull() {
        assertFalse(ReportSocketServer.isAllowedPackage(
                new String[]{"com.example.evil"}, MODULE));
        assertFalse(ReportSocketServer.isAllowedPackage(null, MODULE));
        assertFalse(ReportSocketServer.isAllowedPackage(new String[0], MODULE));
    }

    @Test
    public void peerRejectLogIsThrottledLikeAcceptLog() {
        long t0 = 1_000_000L;
        // 前 5 条立即记录，让排查能看到拒绝开始。
        for (int i = 0; i < 5; i++) {
            assertTrue("第 " + (i + 1) + " 条应记录",
                    ReportSocketServer.shouldLogPeerReject(i, 0L, t0));
        }
        assertFalse("窗口内第 6 条起静默",
                ReportSocketServer.shouldLogPeerReject(5, t0, t0 + 1));
        assertFalse("窗口内第 7 条静默",
                ReportSocketServer.shouldLogPeerReject(6, t0, t0 + 59_999));
        assertTrue("60 秒窗口过后放行一条",
                ReportSocketServer.shouldLogPeerReject(6, t0, t0 + 60_000));
    }

    @Test
    public void peerRejectLogSurvivesClockRollback() {
        long t0 = 1_000_000L;
        // 时钟回拨（now < last）不得把日志永久静默。
        assertTrue(ReportSocketServer.shouldLogPeerReject(6, t0, t0 - 1));
    }

    @Test
    public void peerDecisionCacheIsFreshWithinTtlOnly() {
        long t0 = 1_000_000L;
        // 无缓存/空条目：必须回源查询。
        assertFalse(ReportSocketServer.isFresh(null, t0));
        ReportSocketServer.PeerDecision d =
                new ReportSocketServer.PeerDecision(false, t0);
        // TTL 内复用缓存：循环 connect 不再每次触发 getPackagesForUid。
        assertTrue("TTL 内应命中缓存", ReportSocketServer.isFresh(d, t0 + 1));
        assertTrue("TTL 边界前应命中缓存",
                ReportSocketServer.isFresh(d, t0 + 59_999));
        assertFalse("到达 TTL 必须过期回源",
                ReportSocketServer.isFresh(d, t0 + 60_000));
        // 时钟回拨（now < atMs）按过期处理，宁可重查一次 binder。
        assertFalse(ReportSocketServer.isFresh(d, t0 - 1));
    }
}
