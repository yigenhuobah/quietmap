package com.quietmap.app;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 开关读取：有 PROP_CAP_REMOTE 才读 getRemotePreferences("config")，
 * 否则（含能力探测/读取失败）回退到 Feature 的编译期内置默认值。
 * 读取阶段按 key 隔离：单个 key 抛异常只回退该 key 的 defaultEnabled，
 * 其余 key 继续读取，绝不因为一个坏值放弃全部开关。
 * 必须在 onModuleLoaded 之后调用，之前任何 API 都会抛 IllegalStateException。
 */
public final class Config {

    public static final String GROUP = "config";

    /** 配置结构版本键：与布尔开关同住 "config" group，单独一个 int 键。 */
    public static final String KEY_CONFIG_VERSION = "config_version";
    /** 当前模块能理解的配置版本；读到更高版本时按兼容模式继续加载。 */
    public static final int CURRENT_CONFIG_VERSION = 1;

    private static final String TAG = "QuietMap";

    private final Map<Feature, Boolean> mEnabled = new EnumMap<>(Feature.class);
    /** 配置里记录的版本；缺键/读取失败时等于 {@link #CURRENT_CONFIG_VERSION}。 */
    private int mConfigVersion = CURRENT_CONFIG_VERSION;

    private Config() {
        for (Feature f : Feature.values()) {
            mEnabled.put(f, f.defaultEnabled);
        }
    }

    /**
     * 纯 JVM 可测入口：overrides 里缺的键用 Feature 编译期默认值。
     * 单测的「二十二钩子开关矩阵」走这里，不碰框架 API。
     */
    static Config fromOverrides(Map<String, Boolean> overrides) {
        Config config = new Config();
        if (overrides != null) {
            for (Feature f : Feature.values()) {
                Boolean v = overrides.get(f.key);
                if (v != null) {
                    config.mEnabled.put(f, v);
                }
            }
        }
        return config;
    }

    /**
     * remote 开关的读取器：返回 key → enabled 的 Map。
     * 整份读取失败（含返回 null）由 {@link #load(boolean, RemoteReader)} 统一吞掉
     * 并回退默认值；实现可以放心把框架异常往外抛。生产读取器应先用
     * {@link #readOverrides} 逐 key 兜住单键异常。
     */
    @FunctionalInterface
    interface RemoteReader {
        Map<String, Boolean> read() throws Throwable;
    }

    /**
     * 单 key 读取器：key 读取异常由 {@link #readOverrides} 逐个兜住，
     * 实现可以把 SharedPreferences/框架异常直接往外抛。
     */
    @FunctionalInterface
    interface BooleanGetter {
        boolean get(String key, boolean def) throws Throwable;
    }

    /** 单 key 读取失败告警；整次加载最多调用一次，避免坏 prefs 刷屏。 */
    @FunctionalInterface
    interface Warning {
        void warn(String key, Throwable t);
    }

    /**
     * 配置版本读取器：与布尔开关完全独立，类型错误/读取异常由
     * {@link #readConfigVersion} 兜住，不影响任何开关。
     */
    @FunctionalInterface
    interface IntGetter {
        int get(String key, int def) throws Throwable;
    }

    /** 高版本配置告警：只在 stored > {@link #CURRENT_CONFIG_VERSION} 时调用一次。 */
    @FunctionalInterface
    interface VersionWarning {
        void warn(int storedVersion);
    }

    /**
     * 读取 config_version：缺键或读取异常回落到当前版本；类型错误等异常
     * 通过 {@code readWarning} 告警一次（key + 原始异常），高版本则通过
     * {@code versionWarning} 告警一次，随后继续按现有键加载（兼容模式）。
     */
    static int readConfigVersion(IntGetter getter, VersionWarning warning) {
        return readConfigVersion(getter, warning, null);
    }

    static int readConfigVersion(IntGetter getter, VersionWarning warning,
                                 Warning readWarning) {
        if (getter == null) {
            return CURRENT_CONFIG_VERSION;
        }
        int stored;
        try {
            stored = getter.get(KEY_CONFIG_VERSION, CURRENT_CONFIG_VERSION);
        } catch (Throwable t) {
            if (readWarning != null) {
                try {
                    readWarning.warn(KEY_CONFIG_VERSION, t);
                } catch (Throwable ignored) {
                    // 告警出口自身失败不能影响配置加载。
                }
            }
            return CURRENT_CONFIG_VERSION;
        }
        if (stored > CURRENT_CONFIG_VERSION && warning != null) {
            try {
                warning.warn(stored);
            } catch (Throwable ignored) {
                // 告警出口自身失败不能影响配置加载。
            }
        }
        return stored;
    }

    /**
     * 逐 key 读取 remote 开关：单个 key 抛异常只跳过该 key（调用方回退
     * defaultEnabled），整次加载记录一次 warning，其余 key 继续。
     * 返回的 Map 可能缺少失败键；任何异常都不向外抛。
     */
    static Map<String, Boolean> readOverrides(BooleanGetter getter, Warning warning) {
        Map<String, Boolean> overrides = new HashMap<>();
        if (getter == null) {
            return overrides;
        }
        boolean warned = false;
        for (Feature f : Feature.values()) {
            try {
                overrides.put(f.key, getter.get(f.key, f.defaultEnabled));
            } catch (Throwable t) {
                if (!warned && warning != null) {
                    warned = true;
                    try {
                        warning.warn(f.key, t);
                    } catch (Throwable ignored) {
                        // 告警出口自身失败也不能影响其余 key。
                    }
                }
            }
        }
        return overrides;
    }

    /**
     * 纯 JVM 可测入口：remote=false 或整份读取失败时全默认；
     * 读取成功时缺键用 Feature 编译期默认值，未知键忽略。
     * 任何异常都不向外抛。
     */
    static Config load(boolean remote, RemoteReader reader) {
        return load(remote, reader, null, null, null);
    }

    /**
     * 带 config_version 的加载：版本读取与布尔开关各自 try/catch，
     * 版本坏值/高版本不会让任何一个开关失效。
     */
    static Config load(boolean remote, RemoteReader reader, IntGetter versionGetter,
                       VersionWarning versionWarning) {
        return load(remote, reader, versionGetter, versionWarning, null);
    }

    /**
     * 完整加载入口：额外接收版本读取失败告警（key + 原始异常），
     * 让生产端能像布尔键一样记录 WARN。
     */
    static Config load(boolean remote, RemoteReader reader, IntGetter versionGetter,
                       VersionWarning versionWarning, Warning versionReadWarning) {
        if (!remote) {
            return new Config();
        }
        Map<String, Boolean> overrides = null;
        try {
            overrides = reader == null ? null : reader.read();
        } catch (Throwable ignored) {
            // 整份读取失败：全部回默认值，版本仍独立读取。
        }
        Config config = fromOverrides(overrides);
        config.mConfigVersion = readConfigVersion(
                versionGetter, versionWarning, versionReadWarning);
        return config;
    }

    public static Config load(XposedModule module) {
        boolean remote;
        try {
            remote = (module.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) != 0;
        } catch (Throwable t) {
            // 能力探测本身失败：按「无 remote」处理，绝不让 22 个功能全部不装。
            module.log(Log.WARN, TAG, "framework properties unavailable: use built-in defaults", t);
            return new Config();
        }
        if (!remote) {
            module.log(Log.WARN, TAG, "no PROP_CAP_REMOTE: use built-in defaults");
            return new Config();
        }
        SharedPreferences sp;
        try {
            sp = module.getRemotePreferences(GROUP);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "remote prefs unavailable: use built-in defaults", t);
            return new Config();
        }
        if (sp == null) {
            module.log(Log.WARN, TAG, "remote prefs unavailable: use built-in defaults",
                    new IllegalStateException("remote prefs is null"));
            return new Config();
        }
        return load(true,
                () -> readOverrides(sp::getBoolean, (key, t) ->
                        module.log(Log.WARN, TAG, "remote pref read failed key=" + key
                                + ": use built-in default", t)),
                sp::getInt,
                stored -> module.log(Log.WARN, TAG, "配置版本 v" + stored
                        + " 高于模块支持 v" + CURRENT_CONFIG_VERSION + "，按兼容模式继续"),
                (key, t) -> module.log(Log.WARN, TAG,
                        "remote pref read failed key=" + key
                                + " (" + t.getClass().getName() + ": " + t.getMessage()
                                + "): use built-in default v" + CURRENT_CONFIG_VERSION, t));
    }

    /** 配置中记录的版本；缺键或读取失败时等于 {@link #CURRENT_CONFIG_VERSION}。 */
    int version() {
        return mConfigVersion;
    }

    public boolean enabled(Feature f) {
        return Boolean.TRUE.equals(mEnabled.get(f));
    }

    public boolean anyEnabled() {
        for (Boolean v : mEnabled.values()) {
            if (Boolean.TRUE.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
