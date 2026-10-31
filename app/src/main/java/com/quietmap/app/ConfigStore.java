package com.quietmap.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** 单个存储的读写抽象；单测可注入纯 JVM 实现。 */
    interface Store {
        /** 返回 null 表示 key 不存在或读取失败。 */
        Boolean get(String key);

        /** 只有真正 commit 成功才返回 true。 */
        boolean put(String key, boolean value);
    }

    /** 异步写入结果；在后台写线程回调。 */
    interface WriteCallback {
        void onResult(boolean committed);
    }

    private final Store mNewStore;
    private final boolean mRemote;

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
        mNewStore = prefsStore(remote != null ? remote : local);
    }

    /** 纯 JVM 测试入口：注入存储与 remote 可用性。 */
    ConfigStore(Store newStore, boolean remote) {
        mNewStore = newStore;
        mRemote = remote;
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
        if (!mRemote || mNewStore == null) {
            logW("config write rejected: remote prefs unavailable: " + key, null);
            return false;
        }
        try {
            WRITE_EXECUTOR.execute(() -> {
                // 失败回滚必须用“最近一次确认落盘的值”。该 key 已有过失败记录时，
                // RemotePreferences 的内存 map 已被乐观更新污染（doUpdate 先于
                // doCommit），再读 store 会把从未落盘的值当成已确认值，连续两次
                // 失败就会滚回到一个目标进程根本读不到的状态；此时只能沿用已记录
                // 的确认值。没有记录说明 store 对该 key 仍是干净的，读到的即已落盘值。
                Boolean before;
                if (mCommittedAbsent.contains(key)) {
                    before = null;
                } else {
                    Boolean confirmed = mCommittedFallback.get(key);
                    before = confirmed != null ? confirmed : readQuietly(mNewStore, key);
                }
                boolean committed = false;
                try {
                    committed = mNewStore.put(key, value);
                } catch (Throwable t) {
                    logW("config write failed: " + key, t);
                }
                if (committed) {
                    mCommittedFallback.remove(key);
                    mCommittedAbsent.remove(key);
                } else {
                    if (before != null) {
                        mCommittedFallback.put(key, before);
                        mCommittedAbsent.remove(key);
                    } else {
                        mCommittedFallback.remove(key);
                        mCommittedAbsent.add(key);
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

    /** 纯决策：store 缺失或 service 换绑时必须重建。 */
    static boolean needsRebuild(boolean hasStore, boolean sameService) {
        return !hasStore || !sameService;
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

    private static Store prefsStore(SharedPreferences prefs) {
        if (prefs == null) {
            return null;
        }
        return new Store() {
            @Override
            public Boolean get(String key) {
                try {
                    return prefs.contains(key) ? prefs.getBoolean(key, false) : null;
                } catch (Throwable t) {
                    logW("prefs read failed: " + key, t);
                    return null;
                }
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public boolean put(String key, boolean value) {
                try {
                    // commit 的真实返回值是 remote 写入是否成功的唯一可靠信号。
                    // 调用方已在后台线程，不会阻塞主线程。
                    return prefs.edit().putBoolean(key, value).commit();
                } catch (Throwable t) {
                    logW("prefs write failed: " + key, t);
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
