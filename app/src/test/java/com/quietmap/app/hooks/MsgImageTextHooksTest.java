package com.quietmap.app.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;

/**
 * MsgImageTextHooks 的纯 JVM 逻辑：类名快路径命中校验（id 89 回归锁定）。
 * 混淆短名（n8）会被版本升级重排复用，快路径必须凭 void()V + Runnable +
 * 非框架类拒收无关 run()，宁可降级 DexKit 锚点也不误挂。
 */
public class MsgImageTextHooksTest {

    /** 模拟 17.02 的 Ln8：无参 void run() 的 Runnable 下载任务。 */
    public static final class FakeDownloader implements Runnable {
        @Override
        public void run() {
        }
    }

    /** 模拟被复用的短名：有无参 run() 但不是 Runnable（不可整段跳过的无关类）。 */
    public static final class FakeNonRunnable {
        public void run() {
        }
    }

    /** 模拟被复用的短名：Runnable 但 run 带参/带返回值（签名不符）。 */
    public static final class FakeWrongSignature {
        public int run(boolean retry) {
            return retry ? 1 : 0;
        }
    }

    @Test
    public void voidNoArgRunnableRunAccepted() throws Exception {
        Method run = FakeDownloader.class.getDeclaredMethod("run");
        assertTrue("void 无参 Runnable#run 是合法命中",
                MsgImageTextHooks.isDownloaderRun(run));
    }

    @Test
    public void nonRunnableRunRejected() throws Exception {
        Method run = FakeNonRunnable.class.getDeclaredMethod("run");
        assertFalse("非 Runnable 的 run() 不得整段跳过（id 89：无关后台任务）",
                MsgImageTextHooks.isDownloaderRun(run));
    }

    @Test
    public void wrongSignatureRunRejected() throws Exception {
        Method run = FakeWrongSignature.class.getDeclaredMethod("run", boolean.class);
        assertFalse("带参/带返回值的 run() 不是 void()V，必须拒收",
                MsgImageTextHooks.isDownloaderRun(run));
    }

    /** 框架类黑名单兜底：java.lang.Runnable 自身的 run() 不得命中。 */
    @Test
    public void frameworkRunRejected() throws Exception {
        Method run = Runnable.class.getDeclaredMethod("run");
        assertFalse(MsgImageTextHooks.isDownloaderRun(run));
    }

    @Test
    public void nullMethodRejected() {
        assertFalse(MsgImageTextHooks.isDownloaderRun(null));
    }
}
