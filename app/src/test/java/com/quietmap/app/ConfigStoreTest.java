package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ConfigStore 的纯 JVM 决策：
 * remote 不可用必须拒绝写入、commit 失败必须上报、
 * 缺键/读取失败回默认值、store 复用条件。
 * （曾有的 "features" 旧 group 兜底已随死代码删除：本仓库从未写过该 group。）
 */
public class ConfigStoreTest {

    @Test
    public void remoteUnavailableRejectsWrite() {
        FakeStore newStore = new FakeStore();
        ConfigStore store = new ConfigStore(newStore, false);

        assertFalse("remote 不可用时 put 必须返回 false",
                store.put(Feature.SPLASH_GATE.key, true, null));
        assertEquals("remote 不可用时不得写新 group", 0, newStore.puts);
    }

    @Test
    public void commitSuccessReportedToCallback() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.commitResult = true;
        ConfigStore store = new ConfigStore(newStore, true);

        Result result = writeAndWait(store, Feature.SPLASH_GATE.key, true);

        assertTrue("remote 可用时应接受写入任务", result.scheduled);
        assertTrue("callback 应收到 commit=true", result.committed.get());
        assertEquals(1, newStore.puts);
        assertTrue(newStore.lastValue);
    }

    @Test
    public void commitFailureReportedToCallback() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, true);

        Result result = writeAndWait(store, Feature.SPLASH_GATE.key, true);

        assertTrue(result.scheduled);
        assertFalse("commit=false 不能被当成写成功", result.committed.get());
    }

    @Test
    public void commitFailureKeepsLastCommittedValue() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.values.put(Feature.SPLASH_GATE.key, true);
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, true);

        Result result = writeAndWait(store, Feature.SPLASH_GATE.key, false);

        assertFalse(result.committed.get());
        // FakeStore 与 RemotePreferences 一样先乐观更新 map 再返回 commit 结果；
        // 读取必须用最后一次确认提交的值覆盖它。
        assertTrue("commit 失败后读取必须仍是上次已提交的值",
                store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void commitFailureForAbsentKeyDoesNotLeakValue() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, true);

        writeAndWait(store, Feature.SPLASH_GATE.key, true);

        assertFalse("写前不存在的 key，commit 失败后仍应回退默认值",
                store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void consecutiveCommitFailuresRollBackToLastConfirmedValue() throws Exception {
        FakeStore newStore = new FakeStore();
        // 已落盘的初始值 true（此前成功写入过）。
        newStore.values.put(Feature.SPLASH_GATE.key, true);
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, true);

        writeAndWait(store, Feature.SPLASH_GATE.key, false);
        writeAndWait(store, Feature.SPLASH_GATE.key, true);

        // 修复前：第二次失败从被乐观更新污染的 map 里读到 false，把它当成
        // “已确认值”，get() 返回从未落盘的 false。
        assertTrue("连续两次失败后必须仍是最近一次确认落盘的值",
                store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void consecutiveFailuresOnAbsentKeyNeverLeakValue() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, true);

        writeAndWait(store, Feature.SPLASH_GATE.key, true);
        writeAndWait(store, Feature.SPLASH_GATE.key, false);

        assertFalse("key 从未成功落盘，连续失败后不得把乐观写入的值当已生效",
                store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void consecutiveFailuresAfterSuccessKeepCommittedValue() throws Exception {
        FakeStore newStore = new FakeStore();
        ConfigStore store = new ConfigStore(newStore, true);

        writeAndWait(store, Feature.SPLASH_GATE.key, true);

        newStore.commitResult = false;
        writeAndWait(store, Feature.SPLASH_GATE.key, false);
        writeAndWait(store, Feature.SPLASH_GATE.key, false);

        assertTrue("成功提交后的连续失败也必须滚回该已提交值",
                store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void readFailureFallsBackToDefault() {
        FakeStore newStore = new FakeStore();
        newStore.throwOnGet = true;
        ConfigStore store = new ConfigStore(newStore, true);

        assertFalse("读取异常时必须回默认值，不得抛出",
                store.get(Feature.SPLASH_GATE.key, false));
        assertTrue(store.get(Feature.SPLASH_GATE.key, true));
    }

    @Test
    public void absentUsesDefault() {
        ConfigStore store = new ConfigStore(new FakeStore(), true);

        assertTrue(store.get(Feature.SPLASH_GATE.key, true));
        assertFalse(store.get(Feature.SPLASH_GATE.key, false));
    }

    @Test
    public void storeRebuildDecision() {
        Object serviceA = new Object();
        Object serviceB = new Object();
        assertTrue("没有 store 时必须重建",
                ConfigStore.needsRebuild(false, serviceA, serviceA));
        assertTrue("service 换绑时必须重建",
                ConfigStore.needsRebuild(true, serviceA, serviceB));
        assertFalse("同一 service 实例且已有 store 时复用",
                ConfigStore.needsRebuild(true, serviceA, serviceA));
        assertFalse("缓存与当前 service 同为 null（尚未绑定）时视为同一实例",
                ConfigStore.needsRebuild(true, null, null));
    }

    /** 恢复默认：22 个开关默认值 + version=1，且只走一次事务。 */
    @Test
    public void resetWritesDefaultsAndVersionInOneTransaction() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.values.put(Feature.SPLASH_GATE.key, false);
        newStore.values.put(Feature.TRACK_SUPPRESS.key, true);
        newStore.ints.put(Config.KEY_CONFIG_VERSION, 7);
        ConfigStore store = new ConfigStore(newStore, true, Runnable::run);

        ResetResult result = resetAndWait(store);

        assertTrue("remote 可用时应接受恢复默认任务", result.scheduled);
        assertTrue("全部回读匹配时回调必须为 true", result.success.get());
        assertEquals("恢复默认必须只提交一次事务", 1, newStore.resetCommits);
        assertEquals("不得退化为逐键 put", 0, newStore.puts);
        assertEquals("不得退化为逐键 putInt", 0, newStore.intPuts);
        for (Feature f : Feature.values()) {
            assertEquals("恢复默认后 " + f.key + " 必须是编译期默认值",
                    f.defaultEnabled, newStore.values.get(f.key));
        }
        assertEquals(Config.CURRENT_CONFIG_VERSION,
                newStore.ints.get(Config.KEY_CONFIG_VERSION).intValue());
    }

    /** remote 不可用：直接回调 false，任何存储都不得被写。 */
    @Test
    public void resetRejectedWhenRemoteUnavailable() throws Exception {
        FakeStore newStore = new FakeStore();
        ConfigStore store = new ConfigStore(newStore, false, Runnable::run);

        ResetResult result = resetAndWait(store);

        assertFalse("remote 不可用时不得排队", result.scheduled);
        assertFalse("remote 不可用时回调必须为 false", result.success.get());
        assertEquals(0, newStore.resetCommits);
        assertEquals(0, newStore.puts);
        assertEquals(0, newStore.intPuts);
    }

    /** store 缺失但 remote 标记可用：同样回调 false，不得 NPE。 */
    @Test
    public void resetWithNullStoreFailsSafely() throws Exception {
        ConfigStore store = new ConfigStore(null, true, Runnable::run);

        ResetResult result = resetAndWait(store);

        assertFalse(result.scheduled);
        assertFalse(result.success.get());
    }

    /** commit 返回 true 但内容没写进去：回读校验必须判定失败。 */
    @Test
    public void resetFailsWhenReadBackMismatches() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.applyReset = false;
        ConfigStore store = new ConfigStore(newStore, true, Runnable::run);

        ResetResult result = resetAndWait(store);

        assertTrue(result.scheduled);
        assertFalse("回读不匹配不能报成功", result.success.get());
        assertEquals(1, newStore.resetCommits);
    }

    /** 开关都写对了但版本没写进去：回读校验同样必须失败。 */
    @Test
    public void resetFailsWhenVersionMissingOnReadBack() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.skipVersionOnReset = true;
        ConfigStore store = new ConfigStore(newStore, true, Runnable::run);

        ResetResult result = resetAndWait(store);

        assertFalse("config_version 回读缺失不能报成功", result.success.get());
    }

    /** commit 抛异常：回调 false，不得让写线程崩掉。 */
    @Test
    public void resetCommitThrowReportedAsFailure() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.throwOnReset = true;
        ConfigStore store = new ConfigStore(newStore, true, Runnable::run);

        ResetResult result = resetAndWait(store);

        assertTrue(result.scheduled);
        assertFalse(result.success.get());
    }

    /** 恢复默认成功必须清掉 commit 失败的旧记忆并递增代次。 */
    @Test
    public void resetClearsFailureMemoryAndBumpsGeneration() throws Exception {
        FakeStore newStore = new FakeStore();
        // BG_AWAKEN_REPORT 默认关；先成功落盘 true，再制造一次失败写入，
        // 让 mCommittedFallback 记住 true。
        newStore.values.put(Feature.BG_AWAKEN_REPORT.key, true);
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, true, Runnable::run);
        writeAndWait(store, Feature.BG_AWAKEN_REPORT.key, false);
        assertTrue("失败写入后仍应读到已确认的 true",
                store.get(Feature.BG_AWAKEN_REPORT.key, false));

        int generationBefore = store.resetGeneration();
        ResetResult result = resetAndWait(store);

        assertTrue(result.success.get());
        assertTrue("恢复默认成功必须递增代次",
                store.resetGeneration() > generationBefore);
        assertFalse("重置后必须读到默认 false，而不是旧失败记忆 true",
                store.get(Feature.BG_AWAKEN_REPORT.key, true));
        assertEquals(Boolean.FALSE, newStore.values.get(Feature.BG_AWAKEN_REPORT.key));
    }

    /** 重置前“key 从未存在”的失败记忆也必须清掉，否则默认 true 读不到。 */
    @Test
    public void resetClearsAbsentKeyMemory() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, true, Runnable::run);
        writeAndWait(store, Feature.SPLASH_GATE.key, false);
        assertFalse("失败写入的 key 从未落盘，读取应回默认 false",
                store.get(Feature.SPLASH_GATE.key, false));

        ResetResult result = resetAndWait(store);

        assertTrue(result.success.get());
        assertTrue("重置后必须读到落盘的默认 true",
                store.get(Feature.SPLASH_GATE.key, false));
    }

    /** 重置失败不得清掉旧失败记忆（内存 map 可能仍被乐观更新污染）。 */
    @Test
    public void failedResetKeepsPreviousFailureMemory() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.values.put(Feature.BG_AWAKEN_REPORT.key, true);
        newStore.commitResult = false;
        newStore.resetCommitResult = false;
        ConfigStore store = new ConfigStore(newStore, true, Runnable::run);
        writeAndWait(store, Feature.BG_AWAKEN_REPORT.key, false);

        ResetResult result = resetAndWait(store);

        assertFalse(result.success.get());
        assertTrue("失败的恢复默认不能丢掉上一次确认值",
                store.get(Feature.BG_AWAKEN_REPORT.key, false));
    }

    /** 重置后的失败写入必须从重置后的落盘值起算，不能复活重置前的确认值。 */
    @Test
    public void failedToggleAfterResetDoesNotResurrectPreResetFallback() throws Exception {
        FakeStore newStore = new FakeStore();
        newStore.values.put(Feature.BG_AWAKEN_REPORT.key, true);
        newStore.commitResult = false;
        ConfigStore store = new ConfigStore(newStore, true, Runnable::run);
        writeAndWait(store, Feature.BG_AWAKEN_REPORT.key, false);
        resetAndWait(store);

        writeAndWait(store, Feature.BG_AWAKEN_REPORT.key, true);

        assertFalse("重置前的确认值 true 不得在重置后的失败写入中复活",
                store.get(Feature.BG_AWAKEN_REPORT.key, true));
    }

    /** 普通 put 也必须把 config_version 放进同一笔 editor/commit。 */
    @Test
    public void putWritesConfigVersionInSameCommit() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(ConfigStore.prefsStore(prefs), true,
                Runnable::run);

        Result result = writeAndWait(store, Feature.SPLASH_GATE.key, false);

        assertTrue(result.scheduled);
        assertTrue(result.committed.get());
        assertEquals(Boolean.FALSE, prefs.values().get(Feature.SPLASH_GATE.key));
        assertEquals("put 必须同时写 config_version",
                Config.CURRENT_CONFIG_VERSION,
                ((Integer) prefs.values().get(Config.KEY_CONFIG_VERSION)).intValue());
        assertEquals("开关与版本必须共用一次 commit", 1, prefs.commitCount());
    }

    /** prefsStore 的恢复默认：一次 commit、显式写全部键、不 clear 无关键。 */
    @Test
    public void resetOnPrefsStoreIsSingleCommitAndKeepsUnrelatedKeys() throws Exception {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.values().put(Feature.SPLASH_GATE.key, false);
        prefs.values().put(Config.KEY_CONFIG_VERSION, 9);
        prefs.values().put("unrelated_key", "keep-me");
        ConfigStore store = new ConfigStore(ConfigStore.prefsStore(prefs), true,
                Runnable::run);

        ResetResult result = resetAndWait(store);

        assertTrue(result.success.get());
        assertEquals("恢复默认必须只 commit 一次", 1, prefs.commitCount());
        for (Feature f : Feature.values()) {
            assertEquals("恢复默认后 " + f.key + " 必须是默认值",
                    f.defaultEnabled, prefs.values().get(f.key));
        }
        assertEquals(Config.CURRENT_CONFIG_VERSION,
                ((Integer) prefs.values().get(Config.KEY_CONFIG_VERSION)).intValue());
        assertEquals("不得 clear 同 group 的无关键", "keep-me",
                prefs.values().get("unrelated_key"));
    }

    /** 回读必须走 commit 后重新获取的 handle：旧 handle 内容正确也不够。 */
    @Test
    public void resetVerifiesOnFreshPrefsHandle() throws Exception {
        FakeSharedPreferences writePrefs = new FakeSharedPreferences();
        FakeSharedPreferences readPrefs = new FakeSharedPreferences();
        for (Feature f : Feature.values()) {
            readPrefs.values().put(f.key, f.defaultEnabled);
        }
        readPrefs.values().put(Config.KEY_CONFIG_VERSION, Config.CURRENT_CONFIG_VERSION);
        ConfigStore store = new ConfigStore(
                ConfigStore.prefsStore(writePrefs, () -> readPrefs), true, Runnable::run);

        ResetResult result = resetAndWait(store);

        assertTrue("新 handle 内容匹配时必须成功", result.success.get());
        assertEquals(1, writePrefs.commitCount());
    }

    /** 新 handle 内容不匹配：即使写入 handle 已提交默认值，也必须判失败。 */
    @Test
    public void resetFailsWhenFreshPrefsHandleMismatches() throws Exception {
        FakeSharedPreferences writePrefs = new FakeSharedPreferences();
        FakeSharedPreferences readPrefs = new FakeSharedPreferences();
        ConfigStore store = new ConfigStore(
                ConfigStore.prefsStore(writePrefs, () -> readPrefs), true, Runnable::run);

        ResetResult result = resetAndWait(store);

        assertFalse("不能在同一份内存 map 上回读，必须用新 handle",
                result.success.get());
        for (Feature f : Feature.values()) {
            assertEquals("写入 handle 本身应已提交默认值",
                    f.defaultEnabled, writePrefs.values().get(f.key));
        }
    }

    /** Store 默认的 commitDefaults 必须显式失败，不能退化为 N 笔 commit。 */
    @Test(expected = UnsupportedOperationException.class)
    public void defaultCommitDefaultsFailsLoudly() {
        ConfigStore.Store store = new ConfigStore.Store() {
            @Override
            public Boolean get(String key) {
                return null;
            }

            @Override
            public boolean put(String key, boolean value) {
                return true;
            }
        };
        store.commitDefaults();
    }

    private static ResetResult resetAndWait(ConfigStore store) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean();
        boolean scheduled = store.resetToDefaults(ok -> {
            success.set(ok);
            latch.countDown();
        });
        assertTrue("恢复默认回调未在 5 秒内返回", latch.await(5, TimeUnit.SECONDS));
        return new ResetResult(scheduled, success);
    }

    private static Result writeAndWait(ConfigStore store, String key, boolean value)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean committed = new AtomicBoolean();
        boolean scheduled = store.put(key, value, ok -> {
            committed.set(ok);
            latch.countDown();
        });
        assertTrue("写入回调未在 5 秒内返回", latch.await(5, TimeUnit.SECONDS));
        return new Result(scheduled, committed);
    }

    private static final class Result {
        final boolean scheduled;
        final AtomicBoolean committed;

        Result(boolean scheduled, AtomicBoolean committed) {
            this.scheduled = scheduled;
            this.committed = committed;
        }
    }

    private static final class ResetResult {
        final boolean scheduled;
        final AtomicBoolean success;

        ResetResult(boolean scheduled, AtomicBoolean success) {
            this.scheduled = scheduled;
            this.success = success;
        }
    }

    private static final class FakeStore implements ConfigStore.Store {
        final Map<String, Boolean> values = new HashMap<>();
        final Map<String, Integer> ints = new HashMap<>();
        boolean commitResult = true;
        boolean resetCommitResult = true;
        boolean applyReset = true;
        boolean skipVersionOnReset;
        boolean throwOnReset;
        boolean throwOnGet;
        int puts;
        int intPuts;
        int resetCommits;
        boolean lastValue;

        @Override
        public Boolean get(String key) {
            if (throwOnGet) {
                throw new IllegalStateException("boom");
            }
            return values.get(key);
        }

        @Override
        public Integer getInt(String key) {
            if (throwOnGet) {
                throw new IllegalStateException("boom");
            }
            return ints.get(key);
        }

        @Override
        public boolean put(String key, boolean value) {
            puts++;
            lastValue = value;
            // 与 RemotePreferences.Editor 一致：先乐观更新内存 map，再返回 commit 结果。
            values.put(key, value);
            return commitResult;
        }

        @Override
        public boolean putInt(String key, int value) {
            intPuts++;
            ints.put(key, value);
            return commitResult;
        }

        @Override
        public boolean commitDefaults() {
            resetCommits++;
            if (throwOnReset) {
                throw new IllegalStateException("reset boom");
            }
            if (!resetCommitResult) {
                return false;
            }
            if (applyReset) {
                for (Feature f : Feature.values()) {
                    values.put(f.key, f.defaultEnabled);
                }
                if (!skipVersionOnReset) {
                    ints.put(Config.KEY_CONFIG_VERSION, Config.CURRENT_CONFIG_VERSION);
                }
            }
            return true;
        }
    }
}
