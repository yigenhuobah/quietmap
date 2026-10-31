package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * StatusProvider 的纯 Java 协议规则：键白名单、值长度、hit/entry 计数最大值合并。
 * 这些规则决定跨进程写入 prefs 的边界，改动必须同步更新本测试。
 */
public class ReportProtocolTest {

    @Test
    public void validKeyAcceptsRepresentativePrefixes() {
        assertTrue(ReportProtocol.validKey("reg.splash_gate"));
        assertTrue(ReportProtocol.validKey("hit.bg_msg_fetch"));
        assertTrue(ReportProtocol.validKey("entry.bg_msg_fetch"));
        assertTrue(ReportProtocol.validKey("skip.shake_h5"));
        assertTrue(ReportProtocol.validKey("crit.shake_h5"));
    }

    @Test
    public void validKeyAcceptsAll110GeneratedKeys() {
        int count = 0;
        for (Feature f : Feature.values()) {
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_REG + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_HIT + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_ENTRY + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_SKIP + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_CRIT + f.key));
            count += 5;
        }
        assertEquals(110, count);
    }

    @Test
    public void validKeyRejectsUnknownKeys() {
        assertFalse(ReportProtocol.validKey("foo"));
        assertFalse(ReportProtocol.validKey("hit.not_a_feature"));
        assertFalse(ReportProtocol.validKey("entry.not_a_feature"));
        assertFalse(ReportProtocol.validKey("hit." + "x".repeat(125)));
        assertFalse(ReportProtocol.validKey(null));
        assertFalse(ReportProtocol.validKey(""));
        assertFalse(ReportProtocol.validKey("REG.splash_gate"));
        assertFalse(ReportProtocol.validKey("ENTRY.splash_gate"));
        assertFalse(ReportProtocol.validKey("reg.splash_gate "));
    }

    @Test
    public void mergeHitKeepsLargerValue() {
        assertEquals(10L, ReportProtocol.mergeHit(0L, 10L));
        assertEquals(10L, ReportProtocol.mergeHit(10L, 8L));
    }

    @Test
    public void mergeHitTreatsNegativeAsZero() {
        assertEquals(3L, ReportProtocol.mergeHit(-5L, 3L));
        assertEquals(0L, ReportProtocol.mergeHit(-5L, -3L));
        assertEquals(7L, ReportProtocol.mergeHit(7L, -1L));
    }

    @Test
    public void countKeysCoverHitAndEntryOnly() {
        assertTrue(ReportProtocol.isCountKey("hit.splash_gate"));
        assertTrue(ReportProtocol.isCountKey("entry.splash_gate"));
        assertFalse(ReportProtocol.isCountKey("reg.splash_gate"));
        assertFalse(ReportProtocol.isCountKey("skip.splash_gate"));
        assertFalse("crit 是最新值覆盖，不是计数", ReportProtocol.isCountKey("crit.splash_gate"));
        assertFalse(ReportProtocol.isCountKey(null));
    }

    @Test
    public void isValidValueRejectsNullAndOversizedValues() {
        assertTrue(ReportProtocol.isValidValue(""));
        assertTrue(ReportProtocol.isValidValue("a".repeat(256)));
        assertFalse(ReportProtocol.isValidValue(null));
        assertFalse(ReportProtocol.isValidValue("a".repeat(257)));
    }

    @Test
    public void validKeyRejectsPrefixOnlyAndBoundaryLengths() {
        assertFalse(ReportProtocol.validKey(ReportProtocol.PREFIX_REG));
        assertFalse(ReportProtocol.validKey(ReportProtocol.PREFIX_HIT));
        assertFalse(ReportProtocol.validKey(ReportProtocol.PREFIX_ENTRY));
        assertFalse(ReportProtocol.validKey(ReportProtocol.PREFIX_SKIP));
        assertFalse(ReportProtocol.validKey(ReportProtocol.PREFIX_CRIT));
        // 128 字符恰好等于上限但不在白名单；129 与超长输入同样拒绝。
        assertFalse(ReportProtocol.validKey("reg." + "a".repeat(ReportProtocol.MAX_KEY - 4)));
        assertFalse(ReportProtocol.validKey("reg." + "a".repeat(ReportProtocol.MAX_KEY)));
        assertFalse(ReportProtocol.validKey("a".repeat(10_000)));
    }

    @Test
    public void parseHitRejectsMalformedValues() {
        // QuietMap 协议不使用 JSON：畸形文本（非数字/空白/溢出）等价于畸形载荷。
        assertNull(ReportProtocol.parseHit(null));
        assertNull(ReportProtocol.parseHit(""));
        assertNull(ReportProtocol.parseHit("abc"));
        assertNull(ReportProtocol.parseHit("1.5"));
        assertNull(ReportProtocol.parseHit(" 1"));
        assertNull(ReportProtocol.parseHit("1 "));
        assertNull(ReportProtocol.parseHit("0x10"));
        assertNull(ReportProtocol.parseHit("9223372036854775808"));
        assertNull(ReportProtocol.parseHit("a".repeat(ReportProtocol.MAX_VALUE + 1)));
    }

    @Test
    public void parseHitAcceptsDecimalCounts() {
        assertEquals(Long.valueOf(0), ReportProtocol.parseHit("0"));
        assertEquals(Long.valueOf(42), ReportProtocol.parseHit("42"));
        assertEquals(Long.valueOf(Long.MAX_VALUE),
                ReportProtocol.parseHit(String.valueOf(Long.MAX_VALUE)));
        // 负数交给 mergeHit 按 0 处理，不在这里吞掉。
        assertEquals(Long.valueOf(-1), ReportProtocol.parseHit("-1"));
    }

    @Test
    public void mergeHitAcrossProcessesTakesMaximum() {
        assertEquals(7L, ReportProtocol.mergeHit(ReportProtocol.mergeHit(3L, 7L), 5L));
        assertEquals(9L, ReportProtocol.mergeHit(2L, ReportProtocol.mergeHit(9L, 4L)));
        assertEquals(Long.MAX_VALUE, ReportProtocol.mergeHit(Long.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, ReportProtocol.mergeHit(1L, Long.MAX_VALUE));
    }

    @Test
    public void resetSessionOnlyForNewMainProcessSession() {
        assertTrue(ReportProtocol.shouldResetSession(null, "s1", true));
        assertTrue(ReportProtocol.shouldResetSession("s0", "s1", true));
        assertFalse("同一会话不清理", ReportProtocol.shouldResetSession("s1", "s1", true));
        assertFalse("子进程不能清空其他进程数据",
                ReportProtocol.shouldResetSession("s0", "s1", false));
        assertFalse("没有 session 的旧客户端不清理",
                ReportProtocol.shouldResetSession("s0", null, true));
        assertFalse(ReportProtocol.shouldResetSession("s0", "", true));
    }

    @Test
    public void nonMainProcessOnlyAcceptsCountKeys() {
        assertTrue("主进程可写 reg",
                ReportProtocol.acceptFromProcess(true, "reg.splash_gate"));
        assertTrue("主进程可写 skip",
                ReportProtocol.acceptFromProcess(true, "skip.splash_gate"));
        assertTrue("子进程的 hit 仍然有效",
                ReportProtocol.acceptFromProcess(false, "hit.splash_gate"));
        assertTrue("子进程的 entry 仍然有效",
                ReportProtocol.acceptFromProcess(false, "entry.splash_gate"));
        assertFalse("子进程的 reg 必须忽略",
                ReportProtocol.acceptFromProcess(false, "reg.splash_gate"));
        assertFalse("子进程的 skip 必须忽略",
                ReportProtocol.acceptFromProcess(false, "skip.splash_gate"));
    }

    @Test
    public void isCriticalOnlyAcceptsExactOne() {
        assertTrue(ReportProtocol.isCritical("1"));
        assertFalse(ReportProtocol.isCritical("0"));
        assertFalse(ReportProtocol.isCritical(""));
        assertFalse(ReportProtocol.isCritical(null));
        assertFalse(ReportProtocol.isCritical("true"));
        assertFalse("带空白不是合法 crit 值", ReportProtocol.isCritical(" 1"));
        assertFalse(ReportProtocol.isCritical("11"));
    }

    @Test
    public void featureFailedCountsOnlyMissingMountOrCritical() {
        // 有成功挂载 + 可选方法 skip：只展示，不失败。
        assertFalse("可选锚点缺失不算失败",
                ReportProtocol.featureFailed("canShowSplash", "方法未找到 x", false, 0, 0));
        assertFalse(ReportProtocol.featureFailed("canShowSplash", null, false, 0, 0));
        // 有回传但没有任何成功挂载点：失败（普通 skip 也会暴露这一点）。
        assertTrue(ReportProtocol.featureFailed("", "类未找到 X", false, 0, 0));
        assertTrue(ReportProtocol.featureFailed(null, "方法未找到 x", false, 0, 0));
        // 只有 hit/entry 也属于已回传；没有 reg 时仍判定失败。
        assertTrue(ReportProtocol.featureFailed("", null, false, 3, 0));
        assertTrue(ReportProtocol.featureFailed(null, null, false, 0, 2));
        // 主锚点整体失效：即使有 reg/命中，也必须计入失败。
        assertTrue(ReportProtocol.featureFailed("canShowSplash", null, true, 5, 1));
        // 完全没有回传：不参与失败统计。
        assertFalse(ReportProtocol.featureFailed(null, null, false, 0, 0));
        assertFalse(ReportProtocol.featureFailed("", "", false, 0, 0));
    }

    @Test
    public void isFreshHonorsTtlAndClockRollback() {
        long now = 1_000_000L;
        long ttl = ReportProtocol.DEFAULT_TTL_MS;
        assertFalse("从未回传不算新鲜", ReportProtocol.isFresh(0L, now, ttl));
        assertTrue("恰好 TTL 仍算新鲜",
                ReportProtocol.isFresh(now - ttl, now, ttl));
        assertFalse("超过 TTL 视为过期",
                ReportProtocol.isFresh(now - ttl - 1L, now, ttl));
        assertTrue("小幅时钟偏差（5 分钟内）按新鲜处理",
                ReportProtocol.isFresh(now + 60_000L, now, ttl));
        assertFalse("ttl <= 0 一律过期", ReportProtocol.isFresh(now, now, 0L));
    }

    @Test
    public void isFreshRejectsFutureTimestampsBeyondSkewWindow() {
        long now = 1_000_000L;
        long ttl = ReportProtocol.DEFAULT_TTL_MS;
        assertTrue("未来偏差恰好在容忍窗口边界仍算新鲜",
                ReportProtocol.isFresh(now + ReportProtocol.MAX_FUTURE_SKEW_MS, now, ttl));
        assertFalse("未来时间戳超过 5 分钟视为过期（时钟回拨）",
                ReportProtocol.isFresh(now + ReportProtocol.MAX_FUTURE_SKEW_MS + 1L,
                        now, ttl));
        assertFalse("回拨 30 天的旧数据不再当新鲜",
                ReportProtocol.isFresh(now + 30L * 24 * 60 * 60 * 1000L, now, ttl));
    }

    @Test
    public void sessionIdentityComparisonIgnoresTimestampOrder() {
        // 会话按“身份”比较：不同字符串即新会话（主进程清空旧数据），不比较启动毫秒。
        // 设备时钟回拨后新会话的时间戳比已存值更早，也不得当成“迟到的旧会话”。
        assertTrue("时间戳更早的新会话仍是新会话（时钟回拨场景）",
                ReportProtocol.shouldResetSession("t2000-a", "t1999-b", true));
        assertTrue("时间戳更晚的新会话是新会话",
                ReportProtocol.shouldResetSession("t2000-a", "t2001-b", true));
        assertFalse("session 相同 = 同进程重复上报，不得清空已合并数据",
                ReportProtocol.shouldResetSession("t2000-a", "t2000-a", true));
        assertTrue("同一启动毫秒的不同实例（uuid 后缀不同）是不同会话",
                ReportProtocol.shouldResetSession("t2000-a", "t2000-b", true));
    }

    @Test
    public void oldFormatSessionsAreTreatedByIdentityOnly() {
        // 旧格式（纯数字/纯 UUID）没有随机后缀，也不解析时间戳：不同即新会话，
        // 相同即同进程；不存在“无法排序就整批拒绝”的路径。
        assertTrue(ReportProtocol.shouldResetSession("t2000-a", "1650000000000", true));
        assertTrue(ReportProtocol.shouldResetSession("1650000000000", "t2000-a", true));
        assertTrue(ReportProtocol.shouldResetSession("old-plain-uuid", "t2000-b", true));
        assertFalse(ReportProtocol.shouldResetSession("1650000000000",
                "1650000000000", true));
        assertFalse("空/缺 session 只合并不清理",
                ReportProtocol.shouldResetSession("t2000-a", null, true)
                        || ReportProtocol.shouldResetSession("t2000-a", "", true));
    }

    @Test
    public void repeatedReportsFromSameSessionKeepMergedState() {
        // 同会话重复上报（60 秒心跳）：首次清空后，后续每批都必须原位合并。
        assertTrue("首次回传清理旧会话",
                ReportProtocol.shouldResetSession(null, "t1000-abc", true));
        for (int i = 0; i < 3; i++) {
            assertFalse("同会话第 " + (i + 2) + " 批不得再清空",
                    ReportProtocol.shouldResetSession("t1000-abc", "t1000-abc", true));
        }
        // 计数合并同样不受“迟到”判定影响：同 key 重复上报只按取大合并。
        assertFalse("相同计数不触发写入",
                ReportProtocol.valueChanged("hit.splash_gate", "5", "5"));
        assertTrue("更大计数正常合并",
                ReportProtocol.valueChanged("hit.splash_gate", "5", "6"));
    }

    @Test
    public void valueChangedOnlyWhenActuallyWritten() {
        assertTrue(ReportProtocol.valueChanged("reg.a", null, "x"));
        assertFalse(ReportProtocol.valueChanged("reg.a", "x", "x"));
        assertTrue(ReportProtocol.valueChanged("reg.a", "x", "y"));
        assertFalse("skip 相同不应刷新时间戳",
                ReportProtocol.valueChanged("skip.a", "old", "old"));
        assertTrue(ReportProtocol.valueChanged("hit.a", "3", "5"));
        assertFalse("hit 变小不写", ReportProtocol.valueChanged("hit.a", "5", "3"));
        assertFalse("hit 非法不写", ReportProtocol.valueChanged("hit.a", "5", "bad"));
        assertTrue(ReportProtocol.valueChanged("hit.a", null, "1"));
        // entry 与 hit 共用同一套非负计数规则。
        assertTrue(ReportProtocol.valueChanged("entry.a", "3", "5"));
        assertFalse("entry 变小不写", ReportProtocol.valueChanged("entry.a", "5", "3"));
        assertFalse("entry 负数不写", ReportProtocol.valueChanged("entry.a", "5", "-1"));
        assertFalse("entry 非法不写",
                ReportProtocol.valueChanged("entry.a", "5", "bad"));
        assertFalse("entry 溢出按非法处理", ReportProtocol.valueChanged(
                "entry.a", "5", "9223372036854775808"));
        assertTrue(ReportProtocol.valueChanged("entry.a", null, "1"));
        // crit 是最新值覆盖：空串必须能清掉旧的 "1"。
        assertTrue(ReportProtocol.valueChanged("crit.a", "1", ""));
        assertFalse(ReportProtocol.valueChanged("crit.a", "1", "1"));
        assertTrue(ReportProtocol.valueChanged("crit.a", "", "1"));
    }
}
