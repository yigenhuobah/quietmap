package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Feature 编号、key 与编译期默认值映射。
 *
 * id 是统一功能编号，可随重编号调整；key 是 remote prefs 两读一写共用的键，
 * 改了会连带断掉已保存的开关状态，必须锁死。默认值遵循统一策略：
 * AD/UI 默认开，PV/EN/EX 默认关。
 */
public class FeatureTest {

    /** 统一编号格式：AD/UI/PV/EN/EX + 两位序号。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^(AD|UI|PV|EN|EX)-\\d{2}$");

    /** FEATURE_ID_MAP.md 2.3 节 QuietMap 表：key → id，顺序即设置页顺序。 */
    private static final Map<String, String> EXPECTED_IDS = new LinkedHashMapBuilder()
            .put("splash_gate", "AD-01")
            .put("splash_request", "AD-02")
            .put("splash_shake", "AD-03")
            .put("bg_msg_fetch", "AD-04")
            .put("bg_awaken_report", "PV-01")
            .put("bg_afc_taoke", "AD-05")
            .put("bg_utdid_upload", "PV-02")
            .put("h5_install_apk", "PV-03")
            .put("popup_upgrade", "UI-01")
            .put("home_banner", "AD-06")
            .put("home_operate_bar", "AD-07")
            .put("home_msg_filter", "UI-02")
            .put("search_hotword", "UI-03")
            .put("h5_ad_log", "AD-08")
            .put("map_float_layer", "AD-09")
            .put("msg_image_text", "AD-10")
            .put("popup_clean", "UI-04")
            .put("track_suppress", "PV-04")
            .put("shake_h5", "AD-11")
            .put("ops_activity", "AD-12")
            .put("notify_guide", "UI-05")
            .put("msg_center", "UI-06")
            .build();

    @Test
    public void twentyTwoFeaturesPresent() {
        assertEquals("Feature 数量必须是 22", 22, Feature.values().length);
    }

    @Test
    public void keysMatchSpec() {
        Set<String> actual = new HashSet<>();
        for (Feature f : Feature.values()) {
            actual.add(f.key);
        }
        assertEquals(new HashSet<>(EXPECTED_IDS.keySet()), actual);
    }

    @Test
    public void idsMatchSpecAndEnumOrder() {
        assertEquals("枚举顺序必须与设置页顺序一致",
                new java.util.ArrayList<>(EXPECTED_IDS.keySet()), keysInEnumOrder());
        for (Feature f : Feature.values()) {
            assertEquals("编号与 FEATURE_ID_MAP 不一致: " + f.key,
                    EXPECTED_IDS.get(f.key), f.id);
        }
    }

    @Test
    public void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Feature f : Feature.values()) {
            assertTrue("id 重复: " + f.id, seen.add(f.id));
        }
        assertEquals(Feature.values().length, seen.size());
    }

    @Test
    public void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Feature f : Feature.values()) {
            assertTrue("key 重复: " + f.key, seen.add(f.key));
        }
        assertEquals(EXPECTED_IDS.size(), seen.size());
    }

    @Test
    public void idsUseUnifiedFormat() {
        for (Feature f : Feature.values()) {
            assertTrue("id 格式不合法: " + f.id, ID_PATTERN.matcher(f.id).matches());
        }
    }

    @Test
    public void adAndUiDefaultEnabled() {
        for (Feature f : Feature.values()) {
            if (!f.blocked() && (f.id.startsWith("AD-") || f.id.startsWith("UI-"))) {
                assertTrue(f.id + " " + f.key + " 应默认开启", f.defaultEnabled);
            }
        }
    }

    @Test
    public void pvEnExDefaultDisabled() {
        for (Feature f : Feature.values()) {
            if (f.id.startsWith("PV-") || f.id.startsWith("EN-") || f.id.startsWith("EX-")) {
                assertFalse(f.id + " " + f.key + " 应默认关闭", f.defaultEnabled);
            }
        }
    }

    @Test
    public void defaultEnabledMatchesGroupPolicy() {
        for (Feature f : Feature.values()) {
            boolean expected = !f.blocked()
                    && (f.id.startsWith("AD-") || f.id.startsWith("UI-"));
            assertEquals(f.id + " " + f.key + " 默认值与分组策略不符",
                    expected, f.defaultEnabled);
        }
    }

    @Test
    public void msgCenterIsAvailableAndDefaultsOn() {
        Feature f = Feature.MSG_CENTER;
        assertFalse("UI-06 已完成真机验证，不应再标记 blocked", f.blocked());
        assertTrue("UI 组功能默认开启", f.defaultEnabled);
        assertFalse("非 blocked 项不得保留 blocked 原因", f.blockedReason != null);
    }

    /**
     * 17.02.0.2010 两个挂载点不可用（复核发现 #85/#93）：AD-05 的目标方法在全 dex
     * 无调用点（钩子永不命中），AD-03 的数据层钩子拦不住 ShakeButton 传感器触发链。
     * 一律 blocked：保留编号与行位、不注册安装器、默认关、UI 置灰并显示原因——
     * 不再假装有覆盖。PV-03 已于 0.8.4 重定位（Lec0#dispatch 推广分支门控），
     * 不再 blocked。
     */
    @Test
    public void deadMountPointsAreBlockedOn1702() {
        assertEquals("17.02 摇一摇触发链在 ShakeButton 传感器层，SplashButtonInfo 数据层钩子拦不住",
                Feature.SPLASH_SHAKE.blockedReason);
        assertEquals("17.02 AfcTkManager#registerTkFlag 无调用点，钩子永不命中",
                Feature.BG_AFC_TAOKE.blockedReason);

        int blocked = 0;
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                blocked++;
            }
        }
        assertEquals("blocked 集合必须恰好是上述两个死挂载点", 2, blocked);
    }

    /**
     * PV-03 重定位（0.8.4）：旧挂载点 DeviceInfo#installApk 死代码，新挂载点
     * Lec0#dispatch 推广分支门控（dexsql 实测官方分支独立）。恢复可用：
     * 不 blocked，但 PV 组默认关（用户显式开启才落钩）。
     */
    @Test
    public void h5InstallApkIsAvailableAndDefaultsOff() {
        Feature f = Feature.H5_INSTALL_APK;
        assertFalse("PV-03 已重定位，不应再标记 blocked", f.blocked());
        assertFalse("非 blocked 项不得保留 blocked 原因", f.blockedReason != null);
        assertFalse("PV 组功能默认关闭", f.defaultEnabled);
        assertEquals("PV-03", f.id);
        assertEquals("h5_install_apk", f.key);
    }

    private static java.util.List<String> keysInEnumOrder() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (Feature f : Feature.values()) {
            keys.add(f.key);
        }
        return keys;
    }

    /** 小工具：避免匿名双花括号初始化（会生成额外内部类）。 */
    private static final class LinkedHashMapBuilder {
        private final Map<String, String> map = new java.util.LinkedHashMap<>();

        LinkedHashMapBuilder put(String key, String value) {
            map.put(key, value);
            return this;
        }

        Map<String, String> build() {
            return map;
        }
    }
}
