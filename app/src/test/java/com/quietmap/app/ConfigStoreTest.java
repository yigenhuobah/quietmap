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

    private static final class FakeStore implements ConfigStore.Store {
        final Map<String, Boolean> values = new HashMap<>();
        boolean commitResult = true;
        boolean throwOnGet;
        int puts;
        boolean lastValue;

        @Override
        public Boolean get(String key) {
            if (throwOnGet) {
                throw new IllegalStateException("boom");
            }
            return values.get(key);
        }

        @Override
        public boolean put(String key, boolean value) {
            puts++;
            lastValue = value;
            // 与 RemotePreferences.Editor 一致：先乐观更新内存 map，再返回 commit 结果。
            values.put(key, value);
            return commitResult;
        }
    }
}
