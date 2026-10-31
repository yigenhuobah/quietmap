package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import org.junit.Test;

/**
 * INSTALLERS 注册完整性：非 blocked Feature 必须全部有安装器，
 * blocked Feature 不得注册、也不得被 installAll 安装。
 * 漏注册在运行时只会给单个功能记 skip（诊断卡可见但容易被忽略），这里在构建期就拦住。
 */
public class HooksTest {

    @Test
    public void everyNonBlockedFeatureHasInstaller() {
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            assertTrue("缺少 installer: " + f.id + " (" + f.key + ")",
                    Hooks.installerKeys().contains(f));
        }
    }

    @Test
    public void blockedFeaturesAreNotRegisteredOrInstallable() {
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                continue;
            }
            assertFalse("blocked Feature 不应注册 installer: " + f.id,
                    Hooks.installerKeys().contains(f));
            // blocked 与用户配置无关：即使开关为 true 也不得安装。
            assertFalse("blocked Feature 不应被安装: " + f.id,
                    Hooks.shouldInstall(f, true));
            assertFalse("blocked Feature 不应被安装: " + f.id,
                    Hooks.shouldInstall(f, false));
        }
        // 当前 blocked 项 = AD-03/AD-05/PV-03（17.02 挂载点不可用，见 Feature.blockedReason）；
        // 未来新增 blocked 项时同样自动校验这三条规则。
    }

    @Test
    public void shouldInstallHonorsEnabledForNonBlockedFeatures() {
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            assertTrue("开启的非 blocked Feature 应安装: " + f.id,
                    Hooks.shouldInstall(f, true));
            assertFalse("关闭的 Feature 不应安装: " + f.id,
                    Hooks.shouldInstall(f, false));
        }
    }

    @Test
    public void installerCountMatchesNonBlockedFeatureCount() {
        int expected = 0;
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                expected++;
            }
        }
        assertEquals(expected, Hooks.installerKeys().size());
        // 第二个断言不得再用「同一循环的另一种写法」自证（expected 与
        // length-countBlocked() 同源恒真，对 installer 没有验证作用）：
        // 改为逐项对照枚举校验注册表内容——非 blocked 必须注册、blocked 不得
        // 注册，配合上面的 size 断言可拦住「漏注册 A + 多注册 blocked B」这类
        // 总数不变但内容不对的回归。
        for (Feature f : Feature.values()) {
            assertEquals("installer 注册状态不对: " + f.id + " (" + f.key + ")",
                    !f.blocked(), Hooks.installerKeys().contains(f));
        }
    }

    @Test
    public void reportOnlyStartsInMainProcess() {
        assertTrue("主进程必须启动诊断回传", Hooks.shouldStartReport(true));
        assertFalse("子进程不得启动诊断回传", Hooks.shouldStartReport(false));
    }

    @Test
    public void zeroInstallWithSkipBecomesCritical() {
        Feature f = Feature.SHAKE_H5;
        HookState.markNotFound(f, "类未找到 X");
        assertFalse("单个 findClass 失败不应直接 critical",
                HookState.hasCriticalSkip(f));

        Hooks.markCriticalIfNoMount(f);
        assertTrue("零成功挂载 + 有 skip 必须整体失效",
                HookState.hasCriticalSkip(f));
        assertEquals("无可用挂载点", HookState.skipReason(f));
    }

    @Test
    public void installedFeatureWithOptionalSkipStaysNonCritical() {
        Feature f = Feature.MSG_IMAGE_TEXT;
        HookState.installed(f, "La8#run");
        HookState.markNotFound(f, "可选方法未找到");

        Hooks.markCriticalIfNoMount(f);
        assertFalse("有成功挂载点时可选 skip 不计 critical",
                HookState.hasCriticalSkip(f));
        assertEquals(1, HookState.installedCount(f));
    }

    @Test
    public void zeroInstallWithoutSkipDoesNotBecomeCritical() {
        Feature f = Feature.BG_UTDID_UPLOAD;
        // 未安装也未产生 skip（例如安装器尚未执行）：无法判断，不能标 critical。
        Hooks.markCriticalIfNoMount(f);
        assertFalse(HookState.hasCriticalSkip(f));
    }

    @Test
    public void retryInstallClearsStaleCriticalOnSuccess() {
        Feature f = Feature.BG_MSG_FETCH;
        // 上一轮安装：零挂载 + skip → 整体失效（crit=1）。
        HookState.markNotFound(f, "类未找到 X");
        Hooks.markCriticalIfNoMount(f);
        assertTrue(HookState.hasCriticalSkip(f));

        // QuietMapModule 回滚 sInstalled 后的重试：installOne 先清除上一轮的
        // skip/crit，再重装；本轮挂载成功后不得继续上报 crit=1。
        Hooks.installOne(null, null, (m, c) -> HookState.installed(f, "retry#ok"), f);
        assertFalse("重试成功后不得继续上报 crit=1", HookState.hasCriticalSkip(f));
        assertNull("重试成功后上一轮的 skip 不得保留", HookState.skipReason(f));
        assertEquals("本轮挂载正常记账", 1, HookState.installedCount(f));
    }

    @Test
    public void retryClearsStaleFailureBeforeInstallerRuns() {
        Feature f = Feature.HOME_OPERATE_BAR;
        HookState.markNotFoundCritical(f, "无可用挂载点");
        assertTrue(HookState.hasCriticalSkip(f));

        final boolean[] cleanAtStart = {false};
        Hooks.installOne(null, null, (m, c) -> {
            // 清除必须先于重装：安装器执行时上一轮的 skip/crit 已经不在。
            cleanAtStart[0] = !HookState.hasCriticalSkip(f) && HookState.skipReason(f) == null;
        }, f);
        assertTrue("installOne 必须在重装前清除上一轮的 skip/crit", cleanAtStart[0]);
        // 本轮零挂载且无 skip：不能沿用上一轮的 critical 结论（本轮无法判定）。
        assertFalse("上一轮 critical 不得残留", HookState.hasCriticalSkip(f));
    }
}
