package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Config.load(boolean, RemoteReader) 纯函数：remote 关闭、空 Map、部分键、
 * reader 抛异常、reader 返回 null 五条路径都必须回退到 Feature 编译期默认值，
 * 且异常绝不外抛（否则 Hooks.installAll 会整体放弃安装）。
 */
public class ConfigTest {

    /** remote=false：即使 reader 能读到全关，也必须走编译期默认值。 */
    @Test
    public void remoteDisabledUsesDefaults() {
        Config c = Config.load(false, () -> {
            Map<String, Boolean> allOff = new HashMap<>();
            for (Feature f : Feature.values()) {
                allOff.put(f.key, false);
            }
            return allOff;
        });
        for (Feature f : Feature.values()) {
            assertEquals("remote=false 时 " + f.key + " 应走编译期默认值",
                    f.defaultEnabled, c.enabled(f));
        }
    }

    /** reader 返回空 Map：缺键全部用编译期默认值。 */
    @Test
    public void emptyReaderMapUsesDefaults() {
        Config c = Config.load(true, () -> Collections.emptyMap());
        for (Feature f : Feature.values()) {
            assertEquals("空 Map 时 " + f.key + " 应走编译期默认值",
                    f.defaultEnabled, c.enabled(f));
        }
    }

    /** reader 返回部分键：只影响被覆盖的键，其余仍用默认值。 */
    @Test
    public void partialOverridesOnlyAffectThatKey() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put(Feature.SPLASH_GATE.key, false);
        Config c = Config.load(true, () -> overrides);
        for (Feature f : Feature.values()) {
            boolean expected = f == Feature.SPLASH_GATE ? false : f.defaultEnabled;
            assertEquals("部分键覆盖后 " + f.key + " 状态不对", expected, c.enabled(f));
        }
    }

    /** reader 抛异常：全默认且不向外抛。 */
    @Test
    public void readerThrowsFallsBackToDefaults() {
        Config c = Config.load(true, () -> {
            throw new IllegalStateException("remote prefs boom");
        });
        for (Feature f : Feature.values()) {
            assertEquals("reader 抛异常时 " + f.key + " 应走编译期默认值",
                    f.defaultEnabled, c.enabled(f));
        }
    }

    /** reader 返回 null（对应 getRemotePreferences 返回 null）：全默认。 */
    @Test
    public void nullReaderMapUsesDefaults() {
        Config c = Config.load(true, () -> null);
        for (Feature f : Feature.values()) {
            assertEquals("reader 返回 null 时 " + f.key + " 应走编译期默认值",
                    f.defaultEnabled, c.enabled(f));
        }
    }

    /** 单个 key 读取抛异常：只回退该 key，其余覆盖值继续生效，告警一次。 */
    @Test
    public void singleKeyFailureOnlyFallsBackThatKey() {
        int[] warnings = {0};
        Config c = Config.load(true, () -> Config.readOverrides((key, def) -> {
            if (Feature.SPLASH_GATE.key.equals(key)) {
                throw new IllegalStateException("bad pref type: " + key);
            }
            if (Feature.BG_MSG_FETCH.key.equals(key)) {
                return false;
            }
            return def;
        }, (key, t) -> warnings[0]++));

        assertEquals("失败 key 回退 defaultEnabled",
                Feature.SPLASH_GATE.defaultEnabled, c.enabled(Feature.SPLASH_GATE));
        assertFalse("其他 key 的覆盖值必须保留",
                c.enabled(Feature.BG_MSG_FETCH));
        for (Feature f : Feature.values()) {
            if (f == Feature.SPLASH_GATE || f == Feature.BG_MSG_FETCH) {
                continue;
            }
            assertEquals("未受影响 key 保持默认值: " + f.key,
                    f.defaultEnabled, c.enabled(f));
        }
        assertEquals("整次加载只告警一次", 1, warnings[0]);
    }

    /** 多个 key 抛异常：各自回退默认值，仍只告警一次，其他 key 不受影响。 */
    @Test
    public void multipleKeyFailuresWarnOnceAndKeepOtherOverrides() {
        int[] warnings = {0};
        Config c = Config.load(true, () -> Config.readOverrides((key, def) -> {
            if (Feature.SPLASH_GATE.key.equals(key)
                    || Feature.SPLASH_REQUEST.key.equals(key)) {
                throw new IllegalStateException("corrupt " + key);
            }
            if (Feature.SPLASH_SHAKE.key.equals(key)) {
                return false;
            }
            return def;
        }, (key, t) -> warnings[0]++));

        assertEquals(Feature.SPLASH_GATE.defaultEnabled,
                c.enabled(Feature.SPLASH_GATE));
        assertEquals(Feature.SPLASH_REQUEST.defaultEnabled,
                c.enabled(Feature.SPLASH_REQUEST));
        assertFalse("正常读取的覆盖值必须保留", c.enabled(Feature.SPLASH_SHAKE));
        assertEquals("多个 key 失败也只告警一次", 1, warnings[0]);
    }

    /** config_version 缺键：按当前版本 1 处理，不告警。 */
    @Test
    public void absentConfigVersionDefaultsToCurrent() {
        List<Integer> warned = new ArrayList<>();
        Config c = Config.load(true, () -> Collections.emptyMap(),
                (key, def) -> def, warned::add);

        assertEquals("config_version", Config.KEY_CONFIG_VERSION);
        assertEquals(1, Config.CURRENT_CONFIG_VERSION);
        assertEquals("缺键必须回落到当前版本",
                Config.CURRENT_CONFIG_VERSION, c.version());
        assertTrue("缺键不是高版本，不应告警", warned.isEmpty());
    }

    /** 存储版本 2 > 模块支持 1：不抛异常、只告警一次、其余开关照常生效。 */
    @Test
    public void futureConfigVersionLoadsCompatiblyAndKeepsOtherKeys() {
        List<Integer> warned = new ArrayList<>();
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put(Feature.SPLASH_GATE.key, false);
        overrides.put(Feature.BG_AWAKEN_REPORT.key, true);

        Config c = Config.load(true, () -> overrides,
                (key, def) -> Config.KEY_CONFIG_VERSION.equals(key) ? 2 : def,
                warned::add);

        assertEquals("高版本仍要记录，便于诊断", 2, c.version());
        assertEquals("高版本只告警一次", Collections.singletonList(2), warned);
        assertFalse("高版本不能影响已有开关", c.enabled(Feature.SPLASH_GATE));
        assertTrue("高版本不能影响已有开关", c.enabled(Feature.BG_AWAKEN_REPORT));
        for (Feature f : Feature.values()) {
            if (f == Feature.SPLASH_GATE || f == Feature.BG_AWAKEN_REPORT) {
                continue;
            }
            assertEquals("高版本下 " + f.key + " 应保持默认值",
                    f.defaultEnabled, c.enabled(f));
        }
    }

    /** 当前版本不告警。 */
    @Test
    public void currentConfigVersionDoesNotWarn() {
        List<Integer> warned = new ArrayList<>();
        Config c = Config.load(true, () -> Collections.emptyMap(),
                (key, def) -> Config.CURRENT_CONFIG_VERSION, warned::add);

        assertEquals(Config.CURRENT_CONFIG_VERSION, c.version());
        assertTrue("当前版本不应告警", warned.isEmpty());
    }

    /** config_version 类型错误/读取异常：只回退版本并告警一次，开关照常读取。 */
    @Test
    public void configVersionReadFailureWarnsOnceAndKeepsSwitches() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put(Feature.SPLASH_GATE.key, false);
        List<String> warnedKeys = new ArrayList<>();
        List<Throwable> warnedErrors = new ArrayList<>();

        Config c = Config.load(true, () -> overrides, (key, def) -> {
            throw new ClassCastException("config_version is not an int");
        }, version -> {
            throw new AssertionError("读取失败不应触发高版本告警");
        }, (key, t) -> {
            warnedKeys.add(key);
            warnedErrors.add(t);
        });

        assertEquals(Config.CURRENT_CONFIG_VERSION, c.version());
        assertFalse("版本读取失败不能影响开关", c.enabled(Feature.SPLASH_GATE));
        assertEquals(Feature.BG_MSG_FETCH.defaultEnabled,
                c.enabled(Feature.BG_MSG_FETCH));
        assertEquals("坏版本只告警一次", 1, warnedKeys.size());
        assertEquals("告警 key 必须是 config_version",
                Config.KEY_CONFIG_VERSION, warnedKeys.get(0));
        assertEquals(1, warnedErrors.size());
        assertTrue("必须带原始异常", warnedErrors.get(0) instanceof ClassCastException);
        assertEquals("config_version is not an int",
                warnedErrors.get(0).getMessage());
    }

    /** remote=false：不读取版本，直接使用当前版本与默认开关。 */
    @Test
    public void remoteDisabledDoesNotReadVersion() {
        Config c = Config.load(false, Collections::emptyMap, (key, def) -> 9, version -> {
            throw new AssertionError("remote=false 不应读取版本");
        });

        assertEquals(Config.CURRENT_CONFIG_VERSION, c.version());
        for (Feature f : Feature.values()) {
            assertEquals(f.defaultEnabled, c.enabled(f));
        }
    }
}
