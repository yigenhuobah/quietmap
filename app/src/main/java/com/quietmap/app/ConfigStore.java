package com.quietmap.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.github.yigenhuobah.core.ConfigPolicy;

/**
 * 模块 App 侧的开关读写。
 *
 * 必须写框架的 remote preferences：高德进程读的是那份存储，
 * 写进模块自己的 MODE_PRIVATE 对目标进程完全不可见。
 * 框架没绑定或没有 PROP_CAP_REMOTE 时才退回本地读取，同时 UI 会把开关置灰。
 *
 * <p>写入可靠性：remote prefs 的 commit 可能走 binder + 框架存储，不能放在主线程；
 * 所有写入统一提交到单线程执行器，并用 {@link SharedPreferences.Editor#commit()}
 * 的真实返回值判断是否成功。remote 不可用时直接返回 false，不再把本地
 * apply 当成“写成功”。
 */
final class ConfigStore {

    private static final String TAG = "QuietMap";

    /**
     * 写入串行执行器：同一 key 的多次切换按提交顺序落盘，
     * 且 commit 的磁盘/binder 等待不占用主线程。
     */
    private static final ExecutorService WRITE_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qm-config-write");
                t.setDaemon(true);
                return t;
            });

    /**
     * 恢复默认的回调必须回主线程；没有 Looper 的纯 JVM 单测退化为当前线程。
     * 写线程本身绝不直接碰 UI。
     */
    private static final Executor MAIN_CALLBACK_EXECUTOR = command -> {
        try {
            Looper main = Looper.getMainLooper();
            if (main == null) {
                command.run();
                return;
            }
            new Handler(main).post(command);
        } catch (Throwable t) {
            logW("main thread callback unavailable", t);
            command.run();
        }
    };

    /** 单个存储的读写抽象；单测可注入纯 JVM 实现。 */
    interface Store {
        /** 返回 null 表示 key 不存在或读取失败。 */
        Boolean get(String key);

        /** 只有真正 commit 成功才返回 true。 */
        boolean put(String key, boolean value);

        /** 返回 null 表示 key 不存在、类型不匹配或读取失败。 */
        default Integer getInt(String key) {
            return null;
        }

        /** 只有真正 commit 成功才返回 true；默认替身不支持 int 键。 */
        default boolean putInt(String key, int value) {
            return false;
        }

        /**
         * 单次 editor/commit 写入全部 Feature 默认值 + config_version。
         * 必须由实现保证原子性；默认实现直接抛异常，避免未来某个 Store
         * 退化成 N 笔 commit 留下写到一半的混合状态。
         */
        default boolean commitDefaults() {
            throw new UnsupportedOperationException(
                    "commitDefaults() must be atomic: implement it with a single editor/commit");
        }

        /**
         * 恢复默认 commit 之后用于回读校验的读取视图。
         * 生产实现必须重新获取一份 remote preferences handle，避免在同一份
         * 内存 map 上回读（commit 失败时框架可能已乐观更新内存）。
         * 测试替身/无法重新获取时默认返回自身。
         */
        default Store readbackStore() {
            return this;
        }
    }

    /** 异步写入结果；在后台写线程回调。 */
    interface WriteCallback {
        void onResult(boolean committed);
    }

    /** 恢复默认结果；与 {@link WriteCallback} 不同，固定投递到主线程。 */
    interface ResetCallback {
        void onResult(boolean success);
    }

    private final Store mNewStore;
    private final boolean mRemote;
    private final Executor mCallbackExecutor;
    /** 成功恢复默认会递增，令重置前的切换写入/失败记忆全部作废。 */
    private final AtomicInteger mResetGeneration = new AtomicInteger();

    /**
     * commit 失败后，框架的 RemotePreferences 内存 map 已被乐观更新（doUpdate 先于
     * doCommit）。这里记录最后一次确认提交的值，避免下一次 refresh 把未落盘的值
     * 当成已生效；key 从未存在时记入 {@link #mCommittedAbsent}。
     */
    private final ConcurrentMap<String, Boolean> mCommittedFallback = new ConcurrentHashMap<>();
    private final Set<String> mCommittedAbsent = ConcurrentHashMap.newKeySet();

    ConfigStore(Context context) {
        SharedPreferences remote = ServiceBridge.remotePreferences(Config.GROUP);

        Context app = context;
        if (context != null) {
            Context appContext = context.getApplicationContext();
            if (appContext != null) {
                app = appContext;
            }
        }
        SharedPreferences local = null;
        if (app != null) {
            try {
                local = app.getSharedPreferences(Config.GROUP, Context.MODE_PRIVATE);
            } catch (Throwable t) {
                logW("local prefs unavailable", t);
            }
        }

        mRemote = remote != null;
        // remote 写入的回读必须重新向框架要一份 handle，不能复用构造时那份
        // 可能已被乐观更新的内存视图；local 不走恢复默认，无需刷新器。
        mNewStore = remote != null
                ? prefsStore(remote, () -> ServiceBridge.remotePreferences(Config.GROUP))
                : prefsStore(local);
        mCallbackExecutor = MAIN_CALLBACK_EXECUTOR;
    }

    /** 纯 JVM 测试入口：注入存储与 remote 可用性。 */
    ConfigStore(Store newStore, boolean remote) {
        this(newStore, remote, MAIN_CALLBACK_EXECUTOR);
    }

    /** 纯 JVM 测试入口：额外注入回调执行器，避免依赖 Android 主线程。 */
    ConfigStore(Store newStore, boolean remote, Executor callbackExecutor) {
        mNewStore = newStore;
        mRemote = remote;
        mCallbackExecutor = callbackExecutor != null ? callbackExecutor : MAIN_CALLBACK_EXECUTOR;
    }

    /** true = 写的是框架存储，目标进程能读到。 */
    boolean remote() {
        return mRemote;
    }

    /**
     * 读取开关值：remote/local 同一份 group（{@link Config#GROUP}），缺键回默认值。
     * 曾有的 "features" 旧 group 兜底已删除——本仓库自 v0.1.0 起只写过
     * group "config"，旧兜底永不命中却让每次构造多两次框架调用。
     */
    boolean get(String key, boolean def) {
        Boolean value = readCommitted(key);
        return value != null ? value : def;
    }

    /**
     * 异步写开关。返回值只表示“是否已提交到写线程”：
     * remote 不可用、执行器拒绝任务时返回 false，调用方应立即提示并回滚 UI；
     * 真正是否落盘由 {@link WriteCallback#onResult(boolean)} 告知。
     */
    boolean put(String key, boolean value, WriteCallback callback) {
        // core 的写入口决策：remote 不可用一律拒绝，绝不拿本地 apply 冒充成功。
        boolean accepted = ConfigPolicy.accepted(ConfigPolicy.decide(mRemote));
        if (!accepted || mNewStore == null) {
            logW("config write rejected: remote prefs unavailable: " + key, null);
            return false;
        }
        final int resetGeneration = mResetGeneration.get();
        try {
            WRITE_EXECUTOR.execute(() -> {
                // 失败回滚必须用“最近一次确认落盘的值”。该 key 已有过失败记录时，
                // RemotePreferences 的内存 map 已被乐观更新污染（doUpdate 先于
                // doCommit），再读 store 会把从未落盘的值当成已确认值，连续两次
                // 失败就会滚回到一个目标进程根本读不到的状态；此时只能沿用已记录
                // 的确认值。没有记录说明 store 对该 key 仍是干净的，读到的即已落盘值。
                // 决策顺序（确认值 → 基线 → 兜底）由 core.ConfigPolicy 固定。
                boolean absent = mCommittedAbsent.contains(key);
                Boolean confirmed = absent ? null : mCommittedFallback.get(key);
                Boolean baseline = absent || confirmed != null
                        ? null : readQuietly(mNewStore, key);
                Boolean before = ConfigPolicy.rollbackValue(confirmed, baseline, null);
                boolean committed = false;
                try {
                    committed = mNewStore.put(key, value);
                } catch (Throwable t) {
                    logW("config write failed: " + key, t);
                }
                if (ConfigPolicy.keepUiState(accepted, committed)) {
                    mCommittedFallback.remove(key);
                    mCommittedAbsent.remove(key);
                } else {
                    // 恢复默认成功后旧失败记忆已被清空且代次递增：这笔重置前的
                    // 写入失败不得再把旧值写回失败记忆，否则下一次 refresh 会把
                    // 重置后的默认值覆盖成重置前的值。
                    if (resetGeneration == mResetGeneration.get()) {
                        if (before != null) {
                            mCommittedFallback.put(key, before);
                            mCommittedAbsent.remove(key);
                        } else {
                            mCommittedFallback.remove(key);
                            mCommittedAbsent.add(key);
                        }
                    }
                    logW("config write not committed: " + key, null);
                }
                if (callback != null) {
                    try {
                        callback.onResult(committed);
                    } catch (Throwable t) {
                        logW("config write callback failed", t);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            logW("config write submit failed: " + key, t);
            return false;
        }
    }

    /**
     * 异步恢复默认：remote 不可用时直接回调 false 且不写任何存储。
     * 后台写线程用一次 editor/commit 写入 22 个 Feature 默认值 +
     * config_version，提交后逐键回读校验；全部匹配才回调 true。
     * 回调固定投递到主线程（见 {@link #mCallbackExecutor}）。
     *
     * @return 任务是否已提交到写线程；remote 不可用/执行器拒绝为 false，
     *         此时回调也会收到 false。
     */
    boolean resetToDefaults(ResetCallback callback) {
        boolean accepted = ConfigPolicy.accepted(ConfigPolicy.decide(mRemote));
        if (!accepted || mNewStore == null) {
            logW("config reset rejected: remote prefs unavailable", null);
            deliverResetResult(callback, false);
            return false;
        }
        try {
            WRITE_EXECUTOR.execute(() -> {
                boolean committed = false;
                try {
                    // 不用 Editor.clear()：显式 put 所有键即可，且不会误伤同 group
                    // 之外/未来新增的无关键。
                    committed = mNewStore.commitDefaults();
                } catch (Throwable t) {
                    logW("config reset failed", t);
                }
                boolean success = false;
                if (committed) {
                    // 回读必须尽量走 commit 之后重新获取的 handle；复用同一份
                    // SharedPreferences 的内存 map 会把乐观更新误当成已落盘。
                    success = verifyDefaults(readbackStoreQuietly(mNewStore));
                }
                if (success) {
                    // 旧失败记忆属于重置前的配置，必须清掉；代次递增让重置前
                    // 已排队的切换写入失败记忆与 UI 回调全部作废。
                    mCommittedFallback.clear();
                    mCommittedAbsent.clear();
                    mResetGeneration.incrementAndGet();
                } else {
                    logW("config reset not committed or read-back mismatch", null);
                }
                deliverResetResult(callback, success);
            });
            return true;
        } catch (Throwable t) {
            logW("config reset submit failed", t);
            deliverResetResult(callback, false);
            return false;
        }
    }

    /** 恢复默认成功会递增；单测用它验证重置前的写入代次已作废。 */
    int resetGeneration() {
        return mResetGeneration.get();
    }

    /**
     * 纯决策：store 缺失或 service 换绑时必须重建。
     * 复用条件由 core.ConfigPolicy 统一：同一 service 实例内 remote preferences
     * 不变，可以复用；换绑后继续使用会指向已失效的 binder。
     */
    static boolean needsRebuild(boolean hasStore, Object cachedService, Object currentService) {
        return !ConfigPolicy.reuseStore(hasStore, cachedService, currentService);
    }

    /** 取回读视图；刷新失败时退回当前 store（残余限制见 prefsStore.readbackStore）。 */
    private static Store readbackStoreQuietly(Store store) {
        if (store == null) {
            return null;
        }
        try {
            Store fresh = store.readbackStore();
            return fresh != null ? fresh : store;
        } catch (Throwable t) {
            logW("config read-back handle unavailable", t);
            return store;
        }
    }

    /** 回读校验：所有 Feature 都等于编译期默认值且 config_version 匹配。 */
    private static boolean verifyDefaults(Store store) {
        for (Feature f : Feature.values()) {
            Boolean committed = readQuietly(store, f.key);
            if (committed == null || committed != f.defaultEnabled) {
                return false;
            }
        }
        Integer version = readIntQuietly(store, Config.KEY_CONFIG_VERSION);
        return version != null && version == Config.CURRENT_CONFIG_VERSION;
    }

    /** 恢复默认回调投递；回调自身异常不得影响写线程。 */
    private void deliverResetResult(ResetCallback callback, boolean success) {
        if (callback == null) {
            return;
        }
        Runnable delivery = () -> {
            try {
                callback.onResult(success);
            } catch (Throwable t) {
                logW("config reset callback failed", t);
            }
        };
        try {
            mCallbackExecutor.execute(delivery);
        } catch (Throwable t) {
            logW("config reset callback dispatch failed", t);
            delivery.run();
        }
    }

    private static Boolean readQuietly(Store store, String key) {
        if (store == null) {
            return null;
        }
        try {
            return store.get(key);
        } catch (Throwable t) {
            logW("config read failed: " + key, t);
            return null;
        }
    }

    private static Integer readIntQuietly(Store store, String key) {
        if (store == null) {
            return null;
        }
        try {
            return store.getInt(key);
        } catch (Throwable t) {
            logW("config int read failed: " + key, t);
            return null;
        }
    }

    /** 新 group 读取：commit 失败过的 key 用最后一次确认提交的值覆盖。 */
    private Boolean readCommitted(String key) {
        Boolean fallback = mCommittedFallback.get(key);
        if (fallback != null) {
            return fallback;
        }
        if (mCommittedAbsent.contains(key)) {
            return null;
        }
        return readQuietly(mNewStore, key);
    }

    static Store prefsStore(SharedPreferences prefs) {
        return prefsStore(prefs, null);
    }

    /**
     * @param refresher commit 后重新获取 remote preferences 的入口；null 表示
     *                  该存储不需要/不支持刷新（本地存储、测试替身）。
     */
    static Store prefsStore(SharedPreferences prefs, Supplier<SharedPreferences> refresher) {
        if (prefs == null) {
            return null;
        }
        return new Store() {
            @Override
            public Store readbackStore() {
                if (refresher == null) {
                    return this;
                }
                SharedPreferences fresh = null;
                try {
                    fresh = refresher.get();
                } catch (Throwable t) {
                    logW("prefs read-back handle unavailable", t);
                }
                if (fresh == null || fresh == prefs) {
                    // 残余限制：框架可能按 group 缓存同一个 SharedPreferences 实例，
                    // 或 service 在 commit 后断开；此时无法绕开其内存 map，回读仍可能
                    // 看到 commit 乐观更新后的值。能拿到新 handle 时一定用新 handle。
                    return this;
                }
                return prefsStore(fresh, refresher);
            }

            @Override
            public Boolean get(String key) {
                try {
                    return prefs.contains(key) ? prefs.getBoolean(key, false) : null;
                } catch (Throwable t) {
                    logW("prefs read failed: " + key, t);
                    return null;
                }
            }

            @Override
            public Integer getInt(String key) {
                try {
                    return prefs.contains(key) ? prefs.getInt(key, 0) : null;
                } catch (Throwable t) {
                    logW("prefs int read failed: " + key, t);
                    return null;
                }
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public boolean put(String key, boolean value) {
                try {
                    // commit 的真实返回值是 remote 写入是否成功的唯一可靠信号。
                    // config_version 与开关放在同一笔 editor/commit 里，避免只写成一半。
                    // 调用方已在后台线程，不会阻塞主线程。
                    return prefs.edit()
                            .putBoolean(key, value)
                            .putInt(Config.KEY_CONFIG_VERSION, Config.CURRENT_CONFIG_VERSION)
                            .commit();
                } catch (Throwable t) {
                    logW("prefs write failed: " + key, t);
                    return false;
                }
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public boolean putInt(String key, int value) {
                try {
                    return prefs.edit().putInt(key, value).commit();
                } catch (Throwable t) {
                    logW("prefs int write failed: " + key, t);
                    return false;
                }
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public boolean commitDefaults() {
                try {
                    // 一次 editor/commit 复现全新安装状态：22 个开关默认值 + 版本。
                    // 不使用 clear()，避免清掉同 group 里未来新增或无关的键。
                    SharedPreferences.Editor editor = prefs.edit();
                    for (Feature f : Feature.values()) {
                        editor.putBoolean(f.key, f.defaultEnabled);
                    }
                    editor.putInt(Config.KEY_CONFIG_VERSION, Config.CURRENT_CONFIG_VERSION);
                    return editor.commit();
                } catch (Throwable t) {
                    logW("prefs reset failed", t);
                    return false;
                }
            }
        };
    }

    private static void logW(String message, Throwable t) {
        try {
            if (t == null) {
                Log.w(TAG, message);
            } else {
                Log.w(TAG, message, t);
            }
        } catch (Throwable ignored) {
            // JVM 单测里 android.util.Log 是抛 Stub! 的桩，日志失败不能影响配置读写。
        }
    }
}
