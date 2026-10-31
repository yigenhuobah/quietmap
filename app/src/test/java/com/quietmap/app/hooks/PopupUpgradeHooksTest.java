package com.quietmap.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;

/**
 * PopupUpgradeHooks 的纯 JVM 逻辑：升级对话框命中校验（id 90 回归锁定）。
 * 挂载点已从 AppUpgradeController#q（校验+安装引导总入口，返回 false 会连
 * 安装引导一起断）迁移到内部对话框任务 AppUpgradeController$j#run；
 * run() 必须凭 void()V + Runnable + 非框架类校验后才允许挂。
 */
public class PopupUpgradeHooksTest {

    /** 模拟 17.02 的 AppUpgradeController$j：UiExecutor.post 的对话框 Runnable。 */
    public static final class FakeDialogTask implements Runnable {
        @Override
        public void run() {
        }
    }

    /** 模拟同包被复用的短名：run() 存在但不是 Runnable。 */
    public static final class FakeNonRunnable {
        public void run() {
        }
    }

    /** 模拟签名漂移：run 带返回值（非 void()V）。 */
    public static final class FakeWrongSignature {
        public Object run() {
            return this;
        }
    }

    @Test
    public void voidNoArgRunnableRunAccepted() throws Exception {
        Method run = FakeDialogTask.class.getDeclaredMethod("run");
        assertTrue("对话框任务 run() 是合法命中",
                PopupUpgradeHooks.isDialogRun(run));
    }

    @Test
    public void nonRunnableRunRejected() throws Exception {
        Method run = FakeNonRunnable.class.getDeclaredMethod("run");
        assertFalse("非 Runnable 的 run() 不得挂成对话框跳过",
                PopupUpgradeHooks.isDialogRun(run));
    }

    @Test
    public void wrongSignatureRunRejected() throws Exception {
        Method run = FakeWrongSignature.class.getDeclaredMethod("run");
        assertFalse("带返回值的 run() 不是 void()V，必须拒收",
                PopupUpgradeHooks.isDialogRun(run));
    }

    /** 框架类黑名单兜底：java.lang.Runnable 自身的 run() 不得命中。 */
    @Test
    public void frameworkRunRejected() throws Exception {
        Method run = Runnable.class.getDeclaredMethod("run");
        assertFalse(PopupUpgradeHooks.isDialogRun(run));
    }

    @Test
    public void nullMethodRejected() {
        assertFalse(PopupUpgradeHooks.isDialogRun(null));
    }
}
