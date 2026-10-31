package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 文件读取的纯 JVM 规则：10 分钟新鲜度、损坏 JSON 返回空、白名单过滤、多路径取最新。
 */
public class ReportFileStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void readsFreshValidFile() throws Exception {
        long now = System.currentTimeMillis();
        File file = write("fresh.json", json(now, "hit.splash_gate", "3"));
        ReportFileStore.Entry entry = ReportFileStore.read(
                file, now, ReportFileStore.MAX_AGE_MS);
        assertNotNull(entry);
        assertEquals("3", entry.snapshot.data().get("hit.splash_gate"));
        assertEquals(now, entry.snapshot.updatedAt());
    }

    @Test
    public void acceptsTenMinuteOldFileButRejectsPastThirtyMinutes() throws Exception {
        long now = System.currentTimeMillis();
        File tenMinutes = write("ten-minutes.json",
                json(now - 10 * 60_000L, "reg.splash_gate", "x"));
        assertTrue("10 分钟仍在 30 分钟 TTL 内",
                tenMinutes.setLastModified(now - 10 * 60_000L));
        assertNotNull(ReportFileStore.read(tenMinutes, now, ReportFileStore.MAX_AGE_MS));

        File stale = write("over-ttl.json",
                json(now - ReportFileStore.MAX_AGE_MS - 1_000L, "reg.splash_gate", "x"));
        assertTrue(stale.setLastModified(now - ReportFileStore.MAX_AGE_MS - 1_000L));
        assertNull("超过 30 分钟不再算新鲜数据",
                ReportFileStore.read(stale, now, ReportFileStore.MAX_AGE_MS));
        // 但诊断页仍可用 readLastKnown 展示“最近一次数据 + 时间”。
        assertNotNull(ReportFileStore.readLastKnown(stale));
    }

    /**
     * 未来时间戳的两个方向：时钟小幅偏快（< 5 分钟容差）不能误判过期，
     * 远期时间戳（> 5 分钟容差）不能长期保持新鲜。容差分支是
     * fresh() 里的 age >= -FUTURE_SKEW_MS，mtime 与 updated_at 各查一次；
     * 三个用例分开放，任一分支被改坏都能独立失败。
     */
    @Test
    public void acceptsSmallFutureSkew() throws Exception {
        long now = System.currentTimeMillis();
        // 未来 4 分钟（容差内）：mtime 与内容时间都偏快，仍按新鲜处理。
        File futureSkew = write("future-skew.json",
                json(now + 4 * 60_000L, "reg.splash_gate", "x"));
        assertTrue(futureSkew.setLastModified(now + 4 * 60_000L));
        assertNotNull("5 分钟内的未来时间戳不能判为过期",
                ReportFileStore.read(futureSkew, now, ReportFileStore.MAX_AGE_MS));
    }

    @Test
    public void rejectsFarFutureMtime() throws Exception {
        long now = System.currentTimeMillis();
        // 未来 6 分钟（容差外）：mtime 先被拒，reason 标 stale mtime。
        File farFutureMtime = write("far-future-mtime.json",
                json(now, "reg.splash_gate", "x"));
        assertTrue(farFutureMtime.setLastModified(now + 6 * 60_000L));
        ReportFileStore.Probe probe = ReportFileStore.probe(
                farFutureMtime, now, ReportFileStore.MAX_AGE_MS);
        assertNull(probe.entry);
        assertTrue(probe.reason, probe.reason.contains("stale mtime"));
    }

    @Test
    public void rejectsFarFutureContentTimestamp() throws Exception {
        long now = System.currentTimeMillis();
        // 内容时间未来 6 分钟、mtime 正常：命中 stale updated_at 分支。
        File farFutureContent = write("far-future-content.json",
                json(now + 6 * 60_000L, "reg.splash_gate", "x"));
        assertTrue(farFutureContent.setLastModified(now));
        ReportFileStore.Probe probe = ReportFileStore.probe(
                farFutureContent, now, ReportFileStore.MAX_AGE_MS);
        assertNull(probe.entry);
        assertTrue(probe.reason, probe.reason.contains("stale updated_at"));
    }

    @Test
    public void rejectsStaleMtime() throws Exception {
        long now = System.currentTimeMillis();
        File file = write("stale-mtime.json", json(now, "reg.splash_gate", "x"));
        assertTrue(file.setLastModified(now - ReportFileStore.MAX_AGE_MS - 1_000L));

        ReportFileStore.Probe probe = ReportFileStore.probe(
                file, now, ReportFileStore.MAX_AGE_MS);
        assertNull(probe.entry);
        assertTrue(probe.reason, probe.reason.contains("stale mtime"));
    }

    @Test
    public void rejectsStaleContentTimestamp() throws Exception {
        long now = System.currentTimeMillis();
        File file = write("stale-content.json",
                json(now - ReportFileStore.MAX_AGE_MS - 1_000L, "reg.splash_gate", "x"));
        assertTrue(file.setLastModified(now));

        ReportFileStore.Probe probe = ReportFileStore.probe(
                file, now, ReportFileStore.MAX_AGE_MS);
        assertNull(probe.entry);
        assertTrue(probe.reason, probe.reason.contains("stale updated_at"));
    }

    @Test
    public void corruptJsonReturnsNullWithoutThrowing() throws Exception {
        long now = System.currentTimeMillis();
        String[] corrupt = {"", "not json", "{", "{\"v\":1,\"updated_at\":1}",
                "{\"v\":1,\"updated_at\":1,\"data\":{\"reg.a\":1}}",
                "{\"v\":1,\"updated_at\":1,\"data\":{}}" + "x"};
        for (int i = 0; i < corrupt.length; i++) {
            File file = write("corrupt-" + i + ".json", corrupt[i]);
            assertNull("case " + i, ReportFileStore.read(
                    file, now, ReportFileStore.MAX_AGE_MS));
        }
    }

    @Test
    public void emptyPlaceholderIsTreatedAsNoData() throws Exception {
        long now = System.currentTimeMillis();
        File file = write("empty.json", ReportSnapshot.emptyJson());
        ReportFileStore.Probe probe = ReportFileStore.probe(
                file, now, ReportFileStore.MAX_AGE_MS);
        assertNull(probe.entry);
        assertNotNull(probe.reason);
    }

    @Test
    public void filtersUnknownKeysAndMalformedCounts() throws Exception {
        long now = System.currentTimeMillis();
        Map<String, String> data = new TreeMap<>();
        data.put("foo", "x");
        data.put("hit.splash_gate", "not-a-number");
        data.put("entry.splash_gate", "not-a-number");
        data.put("entry.bg_msg_fetch", "4");
        data.put("reg.splash_gate", "canShowSplash");
        data.put("skip.splash_gate", "ok");
        data.put("crit.splash_gate", "1");
        File file = write("filter.json", ReportSnapshot.toJson(now, data));

        ReportFileStore.Entry entry = ReportFileStore.read(
                file, now, ReportFileStore.MAX_AGE_MS);
        assertNotNull(entry);
        assertEquals(4, entry.snapshot.data().size());
        assertEquals("canShowSplash", entry.snapshot.data().get("reg.splash_gate"));
        assertEquals("ok", entry.snapshot.data().get("skip.splash_gate"));
        assertEquals("crit 必须通过白名单", "1",
                entry.snapshot.data().get("crit.splash_gate"));
        assertEquals("4", entry.snapshot.data().get("entry.bg_msg_fetch"));
    }

    @Test
    public void entryOnlySnapshotIsValidData() throws Exception {
        long now = System.currentTimeMillis();
        File file = write("entry-only.json", json(now, "entry.splash_gate", "2"));

        ReportFileStore.Entry entry = ReportFileStore.read(
                file, now, ReportFileStore.MAX_AGE_MS);
        assertNotNull("只有 entry 的快照不能被当作无数据", entry);
        assertEquals("2", entry.snapshot.data().get("entry.splash_gate"));
    }

    @Test
    public void freshestPicksNewestByMtime() throws Exception {
        long now = System.currentTimeMillis();
        File older = write("older.json", json(now - 5_000L, "reg.splash_gate", "old"));
        File newer = write("newer.json", json(now - 1_000L, "reg.splash_gate", "new"));
        assertTrue(older.setLastModified(now - 5_000L));
        assertTrue(newer.setLastModified(now - 1_000L));

        ReportFileStore.Entry entry = ReportFileStore.freshest(
                Arrays.asList(older, newer), now, ReportFileStore.MAX_AGE_MS);
        assertNotNull(entry);
        assertEquals("new", entry.snapshot.data().get("reg.splash_gate"));
    }

    @Test
    public void readForMergeAcceptsOldButValidFile() throws Exception {
        long old = System.currentTimeMillis() - 86_400_000L;
        File file = write("old.json", json(old, "hit.splash_gate", "9"));
        assertTrue(file.setLastModified(old));

        assertNull(ReportFileStore.read(file, System.currentTimeMillis(),
                ReportFileStore.MAX_AGE_MS));
        assertNotNull(ReportFileStore.readForMerge(file));
    }

    @Test
    public void probePreservesSessionForReaderFiltering() throws Exception {
        long now = System.currentTimeMillis();
        File file = write("session.json", ReportSnapshot.toJson(now, "session-1",
                Collections.singletonMap("reg.splash_gate", "x")));
        ReportFileStore.Entry entry = ReportFileStore.read(
                file, now, ReportFileStore.MAX_AGE_MS);
        assertNotNull(entry);
        assertEquals("session-1", entry.snapshot.session());

        File legacy = write("legacy-session.json",
                json(now, "reg.splash_gate", "x"));
        ReportFileStore.Entry legacyEntry = ReportFileStore.read(
                legacy, now, ReportFileStore.MAX_AGE_MS);
        assertNotNull(legacyEntry);
        assertNull("旧文件没有 session", legacyEntry.snapshot.session());
    }

    @Test
    public void oversizedFileIsRejected() throws Exception {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("{\"v\":1,\"updated_at\":")
                .append(now).append(",\"data\":{\"reg.splash_gate\":\"");
        for (int i = 0; i < ReportFileStore.MAX_BYTES + 1_000; i++) {
            sb.append('a');
        }
        sb.append("\"}}");
        File file = write("huge.json", sb.toString());
        assertNull(ReportFileStore.read(file, now, ReportFileStore.MAX_AGE_MS));
    }

    @Test
    public void missingFileAndDirectoryReturnNull() throws Exception {
        File missing = new File(folder.getRoot(), "missing.json");
        assertNull(ReportFileStore.read(missing, System.currentTimeMillis(),
                ReportFileStore.MAX_AGE_MS));
        File dir = folder.newFolder("as-file");
        assertNull(ReportFileStore.read(dir, System.currentTimeMillis(),
                ReportFileStore.MAX_AGE_MS));
        assertNull(ReportFileStore.read(null, System.currentTimeMillis(),
                ReportFileStore.MAX_AGE_MS));
        assertEquals(Collections.emptyMap(), ReportFileStore.sanitize(null));
    }

    private String json(long updatedAt, String key, String value) {
        return ReportSnapshot.toJson(updatedAt,
                Collections.singletonMap(key, value));
    }

    private File write(String name, String content) throws Exception {
        File file = folder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
