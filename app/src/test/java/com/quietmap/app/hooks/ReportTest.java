package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;
import com.quietmap.app.QuietMapModule;
import com.quietmap.app.ReportProtocol;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Report 的纯 JVM 逻辑：通道顺序（socket → Provider → 文件）、去重/心跳、
 * 文件写入时机（首次立即写、变化节流 45 秒、未变化 5 分钟心跳）、
 * HookState 快照对 reg/hit/entry/skip 的打平上报。
 */
public class ReportTest {

    private static final long T0 = 1_000_000L;

    @Test
    public void firstWriteIsAlwaysDue() {
        assertTrue(Report.fileWriteDue(true, T0, 0L));
        assertTrue(Report.fileWriteDue(false, T0, 0L));
    }

    @Test
    public void changedSnapshotIsThrottledThenWritten() {
        assertFalse(Report.fileWriteDue(true,
                T0 + Report.FILE_MIN_INTERVAL_MS - 1, T0));
        assertTrue(Report.fileWriteDue(true,
                T0 + Report.FILE_MIN_INTERVAL_MS, T0));
    }

    @Test
    public void unchangedSnapshotIsRewrittenByHeartbeat() {
        assertFalse("未变化时不应跟着 45 秒节流写盘",
                Report.fileWriteDue(false,
                        T0 + Report.FILE_HEARTBEAT_MS - 1, T0));
        assertTrue("5 分钟心跳必须重写，刷新文件 mtime",
                Report.fileWriteDue(false,
                        T0 + Report.FILE_HEARTBEAT_MS, T0));
        assertFalse(Report.fileWriteDue(false,
                T0 + Report.FILE_MIN_INTERVAL_MS, T0));
    }

    @Test
    public void clockRollbackAllowsOneWrite() {
        assertTrue(Report.fileWriteDue(false, T0 - 1, T0));
    }

    @Test
    public void sessionKeepsGenerationContract() {
        // S1 会话协议：QM 固定 "t<启动毫秒>-<随机 uuid>"，生成点只有这里一处。
        // 接收端只做身份比较，不解析毫秒；uuid 后缀保证时钟回拨后同毫秒启动的
        // 两个进程仍是不同会话。
        assertTrue("session 必须以 t<毫秒>- 开头：" + Report.SESSION,
                Report.SESSION.matches("t\\d{10,}-\\S+"));
    }

    // ---------------------------------------------------------------- 快照

    @Test
    public void snapshotReportsEntryCountAlongsideHitAndReg() {
        Feature f = Feature.BG_AWAKEN_REPORT;
        HookState.installed(f, "sendAwakenPage");
        HookState.entry(f);
        HookState.entry(f);
        HookState.hit(f);

        Map<String, String> batch = Report.snapshot();
        assertEquals("2", batch.get("entry." + f.key));
        assertEquals("1", batch.get("hit." + f.key));
        assertEquals("sendAwakenPage", batch.get("reg." + f.key));
    }

    @Test
    public void snapshotKeepsEntryOnlyFeatureAndSendsIt() {
        Feature f = Feature.BG_AFC_TAOKE;
        HookState.entry(f);

        Map<String, String> batch = Report.snapshot();
        assertEquals("1", batch.get("entry." + f.key));
        assertFalse("没有命中时不应伪造 hit", batch.containsKey("hit." + f.key));
        assertTrue("只有 entry 的批次也必须发送",
                Report.shouldSend(batch, Collections.emptyMap(), "s1", "s1", T0, T0));
    }

    @Test
    public void snapshotSendsCriticalWhenNothingInstalled() {
        Feature f = Feature.SPLASH_REQUEST;
        HookState.markNotFoundCritical(f, "无可用挂载点");

        Map<String, String> batch = Report.snapshot();
        assertEquals("1", batch.get("crit." + f.key));
        assertEquals("没有成功挂载点时 reg 必须是空串",
                "", batch.get("reg." + f.key));
        assertEquals("无可用挂载点", batch.get("skip." + f.key));
    }

    @Test
    public void snapshotSendsEmptySkipAndCritToClearOldValues() {
        Feature f = Feature.SPLASH_SHAKE;
        HookState.installed(f, "canShowSplash");

        Map<String, String> batch = Report.snapshot();
        assertEquals("canShowSplash", batch.get("reg." + f.key));
        assertEquals("本次会话没有 skip 时必须发送空串清旧值",
                "", batch.get("skip." + f.key));
        assertEquals("非 critical 功能必须发送空串清旧 crit",
                "", batch.get("crit." + f.key));
    }

    @Test
    public void snapshotOmitsZeroEntryCount() {
        Feature f = Feature.HOME_BANNER;
        HookState.installed(f, "sendList");

        Map<String, String> batch = Report.snapshot();
        assertFalse("entryCount 为 0 时不上报 entry 键",
                batch.containsKey("entry." + f.key));
        assertEquals("sendList", batch.get("reg." + f.key));
    }

    /**
     * reg/skip 超长时必须截断到协议上限：StatusProvider/ReportProtocol 对
     * 超过 MAX_VALUE(=256) 的值会整键丢弃（isValidValue 直接 continue），
     * 截断一旦失效，诊断页会静默丢字段而其它用例仍是短字符串、照常全绿。
     * 用 OPS_ACTIVITY：全测试目录没有其它用例碰它的 HookState，避免静态状态串扰。
     */
    @Test
    public void snapshotTruncatesLongRegAndSkipToProtocolMax() {
        Feature f = Feature.OPS_ACTIVITY;
        String longLabel = repeat('r', ReportProtocol.MAX_VALUE + 100);
        String longSkip = repeat('s', ReportProtocol.MAX_VALUE + 100);
        HookState.installed(f, longLabel);
        HookState.markNotFound(f, longSkip);

        Map<String, String> batch = Report.snapshot();
        String reg = batch.get("reg." + f.key);
        String skip = batch.get("skip." + f.key);
        assertEquals("reg 超长必须截断到协议上限", ReportProtocol.MAX_VALUE, reg.length());
        assertEquals("reg 必须保留前缀", longLabel.substring(0, ReportProtocol.MAX_VALUE),
                reg);
        assertEquals("skip 超长必须截断到协议上限", ReportProtocol.MAX_VALUE, skip.length());
        assertEquals("skip 必须保留前缀", longSkip.substring(0, ReportProtocol.MAX_VALUE),
                skip);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 崩溃回传

    /** crash0/1/2 必须始终出现在快照里：无崩溃时为空串（覆盖清掉旧会话残留）。 */
    @Test
    public void snapshotAlwaysCarriesThreeCrashKeys() {
        Map<String, String> snap = Report.snapshot();

        assertTrue("crash0 必须回传", snap.containsKey(ReportProtocol.KEY_CRASH_0));
        assertTrue("crash1 必须回传", snap.containsKey(ReportProtocol.KEY_CRASH_1));
        assertTrue("crash2 必须回传", snap.containsKey(ReportProtocol.KEY_CRASH_2));
        for (int i = 0; i < 3; i++) {
            String value = snap.get("crash" + i);
            assertNotNull(value);
            assertTrue("crash 值必须通过回传长度校验", ReportProtocol.isValidValue(value));
        }
    }

    /** 崩溃环：最新在前、最多 3 条，新的顶掉最旧的。 */
    @Test
    public void crashRingKeepsLatestThreeNewestFirst() {
        QuietMapModule.recordCrash(new Thread("qm-crash-t1"), new RuntimeException("one"));
        QuietMapModule.recordCrash(new Thread("qm-crash-t2"), new RuntimeException("two"));
        QuietMapModule.recordCrash(new Thread("qm-crash-t3"), new RuntimeException("three"));
        QuietMapModule.recordCrash(new Thread("qm-crash-t4"), new RuntimeException("four"));

        Map<String, String> snap = Report.snapshot();

        assertTrue("crash0 是最新一条",
                snap.get(ReportProtocol.KEY_CRASH_0).contains("|qm-crash-t4|"));
        assertTrue(snap.get(ReportProtocol.KEY_CRASH_1).contains("|qm-crash-t3|"));
        assertTrue("第 4 条新崩溃顶掉了第 1 条",
                !snap.get(ReportProtocol.KEY_CRASH_2).contains("|qm-crash-t1|"));
        assertTrue(snap.get(ReportProtocol.KEY_CRASH_2).contains("|qm-crash-t2|"));
    }

    /** 崩溃记录格式：时间（epoch 毫秒）|线程名|异常类名|栈前 2 帧。 */
    @Test
    public void crashEntryFormatCarriesTimeThreadClassAndTwoFrames() {
        RuntimeException boom = new RuntimeException("format");
        QuietMapModule.recordCrash(new Thread("qm-crash-fmt"), boom);

        String crash0 = Report.snapshot().get(ReportProtocol.KEY_CRASH_0);
        String[] parts = crash0.split("\\|", -1);

        assertEquals("时间|线程|异常类名|帧1|帧2 共 5 段", 5, parts.length);
        long ts = Long.parseLong(parts[0]);
        assertTrue("首段必须是当前毫秒时间",
                Math.abs(System.currentTimeMillis() - ts) < 60_000L);
        assertEquals("qm-crash-fmt", parts[1]);
        assertEquals("java.lang.RuntimeException", parts[2]);
        assertEquals("帧 1 必须来自 getStackTrace()[0]",
                boom.getStackTrace()[0].toString(), parts[3]);
        // 帧 2 可能因 240 截断只保留前缀（新 JDK 的反射栈帧较长时会触发截断）。
        assertTrue("帧 2 必须是 getStackTrace()[1] 的前缀",
                boom.getStackTrace()[1].toString().startsWith(parts[4]));
    }

    /** 单条截断 ≤240（MAX_VALUE=256 之内）：超长线程名只保留前 240 个字符。 */
    @Test
    public void crashEntryTruncatedTo240Chars() {
        QuietMapModule.recordCrash(new Thread("x".repeat(300)), new RuntimeException("trunc"));

        String crash0 = Report.snapshot().get(ReportProtocol.KEY_CRASH_0);

        assertEquals("单条崩溃必须截断到 240 字符", 240, crash0.length());
        assertTrue("截断结果必须通过回传值长度校验", ReportProtocol.isValidValue(crash0));
    }

    /** 防御：null 线程 / null 异常不得抛出（UEH 内任何异常都会破坏原有处理链）。 */
    @Test
    public void recordCrashToleratesNullThreadAndThrowable() {
        QuietMapModule.recordCrash(null, null);

        String crash0 = Report.snapshot().get(ReportProtocol.KEY_CRASH_0);
        assertNotNull("null 输入也要落一条占位记录", crash0);
        assertTrue("线程名/异常类名缺失时以 ? 占位", crash0.endsWith("|?"));
    }

    /**
     * UEH 包装（QuietMapModule.installCrashHandler，onPackageReady 主进程分支调用）：
     * 幂等（静态 boolean 只包一次）、先记录崩溃再透传 previous；previous 的异常
     * 处理行为不被改变（透传后原样交回）。
     */
    @Test
    public void crashHandlerWrapsPreviousOnceAndForwards() {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        try {
            List<Throwable> forwarded = new ArrayList<>();
            Thread.UncaughtExceptionHandler previous = (t, e) -> forwarded.add(e);
            Thread.setDefaultUncaughtExceptionHandler(previous);

            QuietMapModule.installCrashHandler();
            Thread.UncaughtExceptionHandler wrapped = Thread.getDefaultUncaughtExceptionHandler();
            assertNotNull("必须替换默认 UEH", wrapped);
            assertNotSame("默认 UEH 必须被包装", previous, wrapped);

            QuietMapModule.installCrashHandler();
            assertSame("静态 boolean 必须保证只包一次", wrapped,
                    Thread.getDefaultUncaughtExceptionHandler());

            RuntimeException boom = new RuntimeException("wrapped");
            wrapped.uncaughtException(Thread.currentThread(), boom);

            assertEquals("previous 必须被透传", 1, forwarded.size());
            assertSame("透传的必须是原始异常", boom, forwarded.get(0));
            assertTrue("崩溃必须先落进快照",
                    Report.snapshot().get(ReportProtocol.KEY_CRASH_0)
                            .contains("java.lang.RuntimeException"));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
    }

    // ---------------------------------------------------------------- 通道顺序

    @Test
    public void socketSuccessShortCircuitsProviderAndFile() {
        List<String> calls = new ArrayList<>();
        String used = Report.deliver(
                channel(calls, "socket", true),
                channel(calls, "provider", true),
                channel(calls, "file", true));
        assertEquals("socket", used);
        assertEquals("socket 成功后不得再调 Provider/文件",
                Collections.singletonList("socket"), calls);
    }

    @Test
    public void socketFailureFallsBackToProviderAndSkipsFile() {
        List<String> calls = new ArrayList<>();
        String used = Report.deliver(
                channel(calls, "socket", false),
                channel(calls, "provider", true),
                channel(calls, "file", true));
        assertEquals("provider", used);
        assertEquals("Provider 成功后不得再写文件",
                Arrays.asList("socket", "provider"), calls);
    }

    @Test
    public void providerFailureFallsBackToFile() {
        List<String> calls = new ArrayList<>();
        String used = Report.deliver(
                channel(calls, "socket", false),
                channel(calls, "provider", false),
                channel(calls, "file", true));
        assertEquals("file", used);
        assertEquals(Arrays.asList("socket", "provider", "file"), calls);
    }

    @Test
    public void allChannelsFailReturnsNull() {
        assertNull(Report.deliver(
                channel(new ArrayList<>(), "socket", false),
                channel(new ArrayList<>(), "provider", false),
                channel(new ArrayList<>(), "file", false)));
    }

    @Test
    public void channelExceptionIsTreatedAsFailureAndNextIsTried() {
        List<String> calls = new ArrayList<>();
        Report.Channel throwing = () -> {
            calls.add("socket");
            throw new IllegalStateException("boom");
        };
        String used = Report.deliver(throwing,
                channel(calls, "provider", true),
                channel(calls, "file", true));
        assertEquals("provider", used);
        assertEquals(Arrays.asList("socket", "provider"), calls);
    }

    // ---------------------------------------------------------------- 去重/心跳

    @Test
    public void unchangedBatchWithinWindowIsDeduplicated() {
        Map<String, String> batch = snapshot("7");
        assertFalse(Report.shouldSend(batch, new TreeMap<>(batch),
                "s1", "s1", T0, T0));
        assertFalse(Report.shouldSend(batch, new TreeMap<>(batch),
                "s1", "s1", T0 + Report.TRANSPORT_HEARTBEAT_MS - 1, T0));
    }

    @Test
    public void changedValueOrSessionForcesSend() {
        Map<String, String> batch = snapshot("7");
        Map<String, String> changed = snapshot("8");
        assertTrue(Report.shouldSend(changed, new TreeMap<>(batch),
                "s1", "s1", T0, T0));
        assertTrue("session 变化必须重发（覆盖主进程清理后的子进程数据）",
                Report.shouldSend(batch, new TreeMap<>(batch),
                        "s2", "s1", T0, T0));
        assertTrue("首次发送没有 lastSession",
                Report.shouldSend(batch, new TreeMap<>(batch),
                        "s1", null, T0, T0));
    }

    @Test
    public void heartbeatForcesSendAfterWindow() {
        Map<String, String> batch = snapshot("7");
        assertTrue(Report.shouldSend(batch, new TreeMap<>(batch),
                "s1", "s1", T0 + Report.TRANSPORT_HEARTBEAT_MS, T0));
        assertTrue("时钟回拨时允许重发", Report.shouldSend(batch,
                new TreeMap<>(batch), "s1", "s1", T0 - 1, T0));
        assertFalse(Report.shouldSend(Collections.emptyMap(),
                Collections.emptyMap(), "s1", "s1", T0, T0));
    }

    private static Map<String, String> snapshot(String hit) {
        Map<String, String> data = new TreeMap<>();
        data.put("hit.splash_gate", hit);
        return data;
    }

    private static Report.Channel channel(List<String> calls, String name,
                                          boolean result) {
        return () -> {
            calls.add(name);
            return result;
        };
    }
}
