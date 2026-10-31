package com.quietmap.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 二十二钩子开关矩阵：每个开关独立生效，互不干扰。
 * 行=override 的键（关掉一个），列=各 Feature 的 enabled 结果。
 * 批 2 三个开屏键、批 3 三个首页键、批 4 四个搜索/推广键、批 5 三个
 * 弹窗/统计/摇一摇键、批 6 三个运营/通知/消息键同样逐项覆盖。
 */
public class HookSwitchMatrixTest {

    /** 批 2（开屏一条龙）三个新键必须落在矩阵里，且互不干扰。 */
    @Test
    public void splashKeysInMatrix() {
        for (Feature off : new Feature[]{Feature.SPLASH_GATE, Feature.SPLASH_REQUEST,
                Feature.SPLASH_SHAKE}) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    /** 批 3（首页营销三件套）三个新键必须落在矩阵里，且互不干扰。 */
    @Test
    public void homeKeysInMatrix() {
        for (Feature off : new Feature[]{Feature.HOME_BANNER, Feature.HOME_OPERATE_BAR,
                Feature.HOME_MSG_FILTER}) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    /** 批 4（搜索热词 + H5 监测 + 地图浮层 + 图文素材）四个新键必须落在矩阵里。 */
    @Test
    public void searchPromoKeysInMatrix() {
        for (Feature off : new Feature[]{Feature.SEARCH_HOTWORD, Feature.H5_AD_LOG,
                Feature.MAP_FLOAT_LAYER, Feature.MSG_IMAGE_TEXT}) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    /** 批 5（弹窗/统计/摇一摇）三个新键必须落在矩阵里，且互不干扰。 */
    @Test
    public void finalBatchKeysInMatrix() {
        for (Feature off : new Feature[]{Feature.POPUP_CLEAN, Feature.TRACK_SUPPRESS,
                Feature.SHAKE_H5}) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    /** 批 6（运营活动/通知引导/消息中心）三个新键必须落在矩阵里，且互不干扰。 */
    @Test
    public void batch6KeysInMatrix() {
        for (Feature off : new Feature[]{Feature.OPS_ACTIVITY, Feature.NOTIFY_GUIDE,
                Feature.MSG_CENTER}) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    @Test
    public void emptyOverridesUseDefaults() {
        Config c = Config.fromOverrides(new HashMap<String, Boolean>());
        for (Feature f : Feature.values()) {
            assertEquals(f.id + " 应使用编译期默认值", f.defaultEnabled, c.enabled(f));
        }
    }

    @Test
    public void eachSwitchIsIndependent() {
        for (Feature off : Feature.values()) {
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(off.key, false);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f != off && f.defaultEnabled;
                assertEquals("关掉 " + off.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    /**
     * 反方向矩阵：编译期默认关的功能（PV 组）必须能被 override 打开。
     * 上面的用例只 put(false)，对 defaultEnabled=false 的功能 expected 与
     * actual 恒为 false，override 等于没被验证；fromOverrides 一旦丢弃
     * true 值，用户就永远打不开这几个功能，这里逐个拦截。
     */
    @Test
    public void defaultOffFeaturesCanBeEnabledByOverride() {
        int defaultOff = 0;
        for (Feature f : Feature.values()) {
            if (!f.defaultEnabled) {
                defaultOff++;
            }
        }
        assertTrue("本用例假设存在编译期默认关的功能，否则 true 值回归无法暴露",
                defaultOff > 0);

        for (Feature on : Feature.values()) {
            if (on.defaultEnabled) {
                continue;
            }
            Map<String, Boolean> overrides = new HashMap<>();
            overrides.put(on.key, true);
            Config c = Config.fromOverrides(overrides);
            for (Feature f : Feature.values()) {
                boolean expected = f == on || f.defaultEnabled;
                assertEquals("打开 " + on.key + " 后 " + f.key + " 状态不对",
                        expected, c.enabled(f));
            }
        }
    }

    @Test
    public void allOffMatrix() {
        Map<String, Boolean> overrides = new HashMap<>();
        for (Feature f : Feature.values()) {
            overrides.put(f.key, false);
        }
        Config c = Config.fromOverrides(overrides);
        for (Feature f : Feature.values()) {
            assertEquals(f.key + " 应被关掉", false, c.enabled(f));
        }
        assertEquals(false, c.anyEnabled());
    }

    @Test
    public void unknownKeysIgnored() {
        Map<String, Boolean> overrides = new HashMap<>();
        overrides.put("not_a_feature", false);
        Config c = Config.fromOverrides(overrides);
        for (Feature f : Feature.values()) {
            assertEquals(f.id + " 应保持默认值", f.defaultEnabled, c.enabled(f));
        }
    }

    @Test
    public void nullOverridesFallBackToDefaults() {
        Config c = Config.fromOverrides(null);
        for (Feature f : Feature.values()) {
            assertEquals(f.id + " 应回退默认值", f.defaultEnabled, c.enabled(f));
        }
    }
}
