package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 「文件比 SharedPreferences 新才用文件」的选择逻辑与读取辅助方法。
 */
public class ReportStateTest {

    @Test
    public void fileIsUsedWhenProviderHasNoData() {
        ReportState picked = ReportState.pick(entry(1_000L, 1_000L, "reg.splash_gate", "x"),
                ReportState.none());
        assertEquals(ReportState.Source.FILE, picked.source());
        assertEquals("x", picked.reg("splash_gate"));
    }

    @Test
    public void fileIsUsedWhenProviderHasNoTimestamp() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "old"), 0L);
        ReportState picked = ReportState.pick(
                entry(1_000L, 1_000L, "reg.splash_gate", "new"), provider);
        assertEquals(ReportState.Source.FILE, picked.source());
        assertEquals("new", picked.reg("splash_gate"));
    }

    @Test
    public void newerOrEqualFileWins() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "provider"), 2_000L);
        assertEquals(ReportState.Source.FILE, ReportState.pick(
                entry(2_000L, 2_000L, "reg.splash_gate", "file"), provider).source());
        assertEquals(ReportState.Source.FILE, ReportState.pick(
                entry(2_001L, 2_001L, "reg.splash_gate", "file"), provider).source());
    }

    @Test
    public void olderFileFallsBackToProvider() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "provider"), 2_000L);
        ReportState picked = ReportState.pick(
                entry(1_000L, 1_000L, "reg.splash_gate", "file"), provider);
        assertEquals(ReportState.Source.PROVIDER, picked.source());
        assertEquals("provider", picked.reg("splash_gate"));
    }

    @Test
    public void bothEmptyReturnsNone() {
        assertEquals(ReportState.Source.NONE, ReportState.pick(null, ReportState.none()).source());
        assertFalse(ReportState.none().hasData());
        assertNull(ReportState.none().path());
    }

    @Test
    public void countsParseAndClampNegativeValues() {
        Map<String, String> data = new TreeMap<>();
        data.put("hit.splash_gate", "7");
        data.put("hit.bg_msg_fetch", "-3");
        data.put("hit.shake_h5", "bad");
        data.put("entry.splash_gate", "4");
        data.put("entry.bg_msg_fetch", "-3");
        data.put("entry.shake_h5", "bad");
        data.put("entry.msg_center", "9223372036854775808");
        ReportState state = ReportState.fromProvider(data, 1L);
        assertEquals(7L, state.hit("splash_gate"));
        assertEquals(0L, state.hit("bg_msg_fetch"));
        assertEquals(0L, state.hit("shake_h5"));
        assertEquals(4L, state.entry("splash_gate"));
        assertEquals(0L, state.entry("bg_msg_fetch"));
        assertEquals(0L, state.entry("shake_h5"));
        assertEquals("溢出文本按非法值丢弃", 0L, state.entry("msg_center"));
        assertTrue(state.hasData());
    }

    @Test
    public void entryOnlyDataCountsAsData() {
        ReportState state = ReportState.fromProvider(
                Collections.singletonMap("entry.splash_gate", "2"), 1L);
        assertTrue("只有 entry 也算已回传", state.hasData());
        assertEquals(2L, state.entry("splash_gate"));
        assertEquals(0L, state.hit("splash_gate"));
    }

    @Test
    public void legacySnapshotWithoutEntryStillWorks() {
        ReportState state = ReportState.fromProvider(
                Collections.singletonMap("hit.splash_gate", "3"), 1L);
        assertEquals(3L, state.hit("splash_gate"));
        assertEquals("旧快照没有 entry 字段时按 0 处理", 0L, state.entry("splash_gate"));
        assertTrue(state.hasData());
    }

    @Test
    public void pickMergesKeysFromBothSourcesByKey() {
        Map<String, String> fileData = new TreeMap<>();
        fileData.put("reg.splash_gate", "file");
        Map<String, String> providerData = new TreeMap<>();
        providerData.put("reg.splash_gate", "provider");
        providerData.put("reg.bg_msg_fetch", "provider-only");
        ReportState provider = ReportState.fromProvider(providerData, 1_000L);

        ReportState picked = ReportState.pick(entry(2_000L, 2_000L, fileData), provider);
        assertEquals(ReportState.Source.FILE, picked.source());
        assertEquals("file", picked.reg("splash_gate"));
        assertEquals("provider-only", picked.reg("bg_msg_fetch"));
    }

    @Test
    public void pickTakesMaxCountsAndKeepsNonEmptyValue() {
        Map<String, String> fileData = new TreeMap<>();
        fileData.put("hit.splash_gate", "3");
        fileData.put("entry.splash_gate", "8");
        fileData.put("skip.splash_gate", "");
        Map<String, String> providerData = new TreeMap<>();
        providerData.put("hit.splash_gate", "9");
        providerData.put("entry.splash_gate", "2");
        providerData.put("skip.splash_gate", "provider skip");
        ReportState provider = ReportState.fromProvider(providerData, 1_000L);

        ReportState picked = ReportState.pick(entry(2_000L, 2_000L, fileData), provider);
        assertEquals(9L, picked.hit("splash_gate"));
        assertEquals("entry 跨来源同样取最大值", 8L, picked.entry("splash_gate"));
        assertEquals("较新文件为空时保留旧的非空值", "provider skip",
                picked.skip("splash_gate"));
    }

    @Test
    public void pickKeepsEntryPresentInOnlyOneSource() {
        Map<String, String> fileData = Collections.singletonMap(
                "entry.splash_gate", "5");
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("entry.bg_msg_fetch", "3"), 1_000L);

        ReportState picked = ReportState.pick(entry(2_000L, 2_000L, fileData), provider);
        assertEquals(5L, picked.entry("splash_gate"));
        assertEquals(3L, picked.entry("bg_msg_fetch"));
    }

    @Test
    public void staleFileStateKeepsDataAndTimestamp() {
        ReportState stale = ReportState.fromStaleFile(
                entry(1_000L, 1_000L, "reg.splash_gate", "last"));
        assertEquals(ReportState.Source.FILE_STALE, stale.source());
        assertTrue(stale.hasData());
        assertEquals("last", stale.reg("splash_gate"));
        assertEquals(1_000L, stale.updatedAt());
    }

    @Test
    public void fileAndStaleStatesKeepSnapshotSession() {
        ReportState file = ReportState.fromFile(entry(1_000L, 1_000L, "session-1",
                Collections.singletonMap("reg.splash_gate", "x")));
        assertEquals("session-1", file.session());

        ReportState stale = ReportState.fromStaleFile(entry(1_000L, 1_000L, "session-2",
                Collections.singletonMap("reg.splash_gate", "x")));
        assertEquals("session-2", stale.session());
    }

    @Test
    public void providerAndSocketStatesKeepMetaSession() {
        Map<String, String> data = Collections.singletonMap("reg.splash_gate", "x");
        assertEquals("provider-session",
                ReportState.fromProvider(data, 1L, "provider-session").session());
        assertEquals("socket-session",
                ReportState.fromSocket(data, 1L, "socket-session").session());
        assertNull("旧调用不带 session 时保持 null",
                ReportState.fromProvider(data, 1L).session());
        assertNull(ReportState.none().session());
    }

    @Test
    public void pickIgnoresFileFromDifferentSession() {
        ReportState provider = ReportState.fromSocket(
                Collections.singletonMap("reg.splash_gate", "current"),
                2_000L, "session-new");
        ReportState picked = ReportState.pick(entry(3_000L, 3_000L, "session-old",
                Collections.singletonMap("reg.splash_gate", "old")), provider);
        assertEquals("旧会话文件即使更新也必须忽略",
                ReportState.Source.SOCKET, picked.source());
        assertEquals("current", picked.reg("splash_gate"));
        assertEquals("session-new", picked.session());
    }

    @Test
    public void mismatchedFileWithoutProviderDataYieldsNone() {
        ReportState provider = ReportState.fromProvider(
                Collections.emptyMap(), 0L, "session-new");
        ReportState picked = ReportState.pick(entry(3_000L, 3_000L, "session-old",
                Collections.singletonMap("reg.splash_gate", "old")), provider);
        assertEquals("旧会话文件不能成为唯一数据源",
                ReportState.Source.NONE, picked.source());
        assertFalse(picked.hasData());
    }

    @Test
    public void pickAcceptsLegacyFileWithoutSessionAndKeepsCurrentSession() {
        ReportState provider = ReportState.fromSocket(
                Collections.singletonMap("reg.splash_gate", "current"),
                2_000L, "session-new");
        ReportState picked = ReportState.pick(
                entry(3_000L, 3_000L,
                        Collections.singletonMap("reg.splash_gate", "file")),
                provider);
        assertEquals(ReportState.Source.FILE, picked.source());
        assertEquals("file", picked.reg("splash_gate"));
        assertEquals("旧文件没有 session 时按兼容处理，仍沿用当前 session",
                "session-new", picked.session());
    }

    @Test
    public void pickTakesSessionFromNewerSource() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "provider"),
                3_000L, "session-provider");
        ReportState picked = ReportState.pick(entry(1_000L, 1_000L, "session-file",
                Collections.singletonMap("reg.splash_gate", "file")), provider);
        assertEquals(ReportState.Source.PROVIDER, picked.source());
        assertEquals("session-provider", picked.session());
    }

    @Test
    public void sessionCompatibleTreatsMissingSideAsCompatible() {
        assertTrue("旧文件没有 session 时兼容", ReportState.sessionCompatible(null, "file"));
        assertTrue("尚未收到主进程 session 时兼容", ReportState.sessionCompatible("current", null));
        assertTrue(ReportState.sessionCompatible("", "file"));
        assertTrue(ReportState.sessionCompatible("current", ""));
        assertTrue(ReportState.sessionCompatible("same", "same"));
        assertFalse("双方都有值且不同必须判定为旧会话",
                ReportState.sessionCompatible("current", "old"));
    }

    @Test
    public void providerValuesAreSanitized() {
        Map<String, String> data = new TreeMap<>();
        data.put("reg.splash_gate", "ok");
        data.put("unknown.key", "x");
        data.put("hit.splash_gate", "not-a-number");
        data.put("entry.splash_gate", "not-a-number");
        data.put("entry.bg_msg_fetch", "6");
        ReportState state = ReportState.fromProvider(data, 1L);
        assertEquals("ok", state.reg("splash_gate"));
        assertEquals(0L, state.hit("splash_gate"));
        assertEquals(0L, state.entry("splash_gate"));
        assertEquals(6L, state.entry("bg_msg_fetch"));
        assertNull("非法 entry 值必须被 sanitize 丢弃",
                state.value(ReportProtocol.PREFIX_ENTRY, "splash_gate"));
        assertNull(state.value(ReportProtocol.PREFIX_SKIP, "splash_gate"));
    }

    @Test
    public void pickKeepsSocketSourceWhenPrefsAreNewer() {
        ReportState socket = ReportState.fromSocket(
                Collections.singletonMap("reg.splash_gate", "socket"), 2_000L);
        assertEquals(ReportState.Source.SOCKET, socket.source());

        ReportState picked = ReportState.pick(
                entry(1_000L, 1_000L, "reg.splash_gate", "file"), socket);
        assertEquals("prefs 较新时必须保留 socket 来源",
                ReportState.Source.SOCKET, picked.source());
        assertEquals("socket", picked.reg("splash_gate"));

        ReportState fileWins = ReportState.pick(
                entry(3_000L, 3_000L, "reg.splash_gate", "file"), socket);
        assertEquals(ReportState.Source.FILE, fileWins.source());
        assertEquals("file", fileWins.reg("splash_gate"));
    }

    @Test
    public void critIsSanitizedAndReadable() {
        Map<String, String> data = new TreeMap<>();
        data.put("crit.splash_gate", "1");
        data.put("crit.bg_msg_fetch", "");
        data.put("crit.shake_h5", "yes");
        ReportState state = ReportState.fromProvider(data, 1L);
        assertEquals("1", state.crit("splash_gate"));
        assertTrue(state.critical("splash_gate"));
        assertEquals("", state.crit("bg_msg_fetch"));
        assertFalse(state.critical("bg_msg_fetch"));
        assertFalse("非 \"1\" 一律按非 critical 处理", state.critical("shake_h5"));
        assertNull(state.crit("msg_center"));
    }

    @Test
    public void pickUsesNewestCritWithoutFallingBackToOldOne() {
        Map<String, String> fileData = Collections.singletonMap(
                "crit.splash_gate", "");
        Map<String, String> providerData = Collections.singletonMap(
                "crit.splash_gate", "1");

        ReportState provider = ReportState.fromProvider(providerData, 1_000L);
        ReportState recoveredByFile = ReportState.pick(
                entry(2_000L, 2_000L, fileData), provider);
        assertFalse("较新文件的空 crit 必须清掉旧 crit=1",
                recoveredByFile.critical("splash_gate"));

        ReportState newerProvider = ReportState.fromProvider(providerData, 3_000L);
        ReportState picked = ReportState.pick(
                entry(2_000L, 2_000L, fileData), newerProvider);
        assertTrue("较旧文件的空 crit 不能覆盖较新的 crit=1",
                picked.critical("splash_gate"));
    }

    @Test
    public void pickKeepsCritPresentInOnlyOneSource() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "x"), 2_000L);
        ReportState picked = ReportState.pick(
                entry(1_000L, 1_000L,
                        Collections.singletonMap("crit.splash_gate", "1")), provider);
        assertTrue("只有文件带 crit 时保留该值",
                picked.critical("splash_gate"));
    }

    @Test
    public void pickUsesNewestCrashWithoutFallingBackToOldOne() {
        String crash = "1710000000000|main|java.lang.NullPointerException";
        Map<String, String> fileData = Collections.singletonMap(
                ReportProtocol.KEY_CRASH_0, "");
        Map<String, String> providerData = Collections.singletonMap(
                ReportProtocol.KEY_CRASH_0, crash);

        ReportState provider = ReportState.fromProvider(providerData, 1_000L);
        ReportState clearedByFile = ReportState.pick(
                entry(2_000L, 2_000L, fileData), provider);
        assertEquals("同会话内较新的空 crash 值必须覆盖较旧的非空值（清残留）",
                "", clearedByFile.value("", ReportProtocol.KEY_CRASH_0));

        ReportState newerProvider = ReportState.fromProvider(providerData, 3_000L);
        ReportState picked = ReportState.pick(
                entry(2_000L, 2_000L, fileData), newerProvider);
        assertEquals("较旧文件的空 crash 不能覆盖较新的非空值",
                crash, picked.value("", ReportProtocol.KEY_CRASH_0));
    }

    @Test
    public void pickKeepsCrashPresentInOnlyOneSource() {
        ReportState provider = ReportState.fromProvider(
                Collections.singletonMap("reg.splash_gate", "x"), 2_000L);
        ReportState picked = ReportState.pick(
                entry(1_000L, 1_000L, Collections.singletonMap(
                        ReportProtocol.KEY_CRASH_0, "1710000000000|main|IllegalState")),
                provider);
        assertEquals("只有文件带 crash 时保留该值",
                "1710000000000|main|IllegalState",
                picked.value("", ReportProtocol.KEY_CRASH_0));
    }

    private static ReportFileStore.Entry entry(long mtime, long updatedAt,
                                               String key, String value) {
        return entry(mtime, updatedAt, Collections.singletonMap(key, value));
    }

    private static ReportFileStore.Entry entry(long mtime, long updatedAt,
                                               Map<String, String> data) {
        return entry(mtime, updatedAt, null, data);
    }

    private static ReportFileStore.Entry entry(long mtime, long updatedAt,
                                               String session,
                                               Map<String, String> data) {
        ReportSnapshot snapshot = new ReportSnapshot(updatedAt, session, data);
        return new ReportFileStore.Entry(new File("qm_report.json"), snapshot, mtime);
    }
}
