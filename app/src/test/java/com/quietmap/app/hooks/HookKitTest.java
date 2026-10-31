package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * HookKit 的纯 JVM 逻辑：框架类黑名单 + 反射定位 + DexKit 延迟兜底收口。
 * install/hit/skipVoid 的命中路径需要 XposedModule，JVM 单测无法构造；
 * 延迟兜底（{@link HookKit#finishLateAnchor}）与日志出口对 null module 容忍，
 * 可在 JVM 上覆盖 miss 收口与「每 Feature 只提交一次」契约。
 */
public class HookKitTest {

    /** 反射定位 fixture：三个同名重载，只有精确参数类型才能命中。 */
    static class Fixture {
        void m(int v) {
        }

        void m(Integer v) {
        }

        void m(String v) {
        }
    }

    @Test
    public void frameworkClassesRefused() {
        String[] names = {
                "android.view.View",
                "java.lang.String",
                "dalvik.system.PathClassLoader",
                "androidx.core.AppCompat",
                "kotlin.Unit"
        };
        for (String name : names) {
            assertTrue("框架类应被拒绝: " + name, HookKit.isFrameworkClass(name));
        }
        assertTrue("null 类名应被拒绝", HookKit.isFrameworkClass(null));
    }

    @Test
    public void appClassesAllowed() {
        assertFalse("宿主应用类不应被当成框架类", HookKit.isFrameworkClass("com.autonavi.minimap.X"));
        assertFalse("模块自身类不应被当成框架类", HookKit.isFrameworkClass("com.quietmap.app.Foo"));
    }

    @Test
    public void findMethodQuietMatchesExactParamTypes() {
        Method intOverload = HookKit.findMethodQuiet(Fixture.class, "m", int.class);
        assertNotNull("int 参数应命中 m(int)", intOverload);
        assertEquals(int.class, intOverload.getParameterTypes()[0]);

        Method integerOverload = HookKit.findMethodQuiet(Fixture.class, "m", Integer.class);
        assertNotNull("Integer 参数应命中 m(Integer)", integerOverload);
        assertEquals(Integer.class, integerOverload.getParameterTypes()[0]);

        Method stringOverload = HookKit.findMethodQuiet(Fixture.class, "m", String.class);
        assertNotNull("String 参数应命中 m(String)", stringOverload);
        assertEquals(String.class, stringOverload.getParameterTypes()[0]);

        assertFalse("m(int) 与 m(Integer) 必须是两个不同的方法", intOverload.equals(integerOverload));
    }

    @Test
    public void findMethodQuietReturnsNullForUnknownParam() {
        assertNull("不存在的参数类型应返回 null",
                HookKit.findMethodQuiet(Fixture.class, "m", double.class));
        assertNull("不存在的方法名应返回 null",
                HookKit.findMethodQuiet(Fixture.class, "noSuchMethod", int.class));
    }

    @Test
    public void findMethodQuietReturnsNullForNullClass() {
        assertNull("cls 为 null 时应返回 null",
                HookKit.findMethodQuiet((Class<?>) null, "m", int.class));
    }

    /**
     * install(null Method) 的失败必须进入 HookState：写入与 findMethod 同形的 skip
     * 原因后，「零挂载 + 无 skip」的功能就能被 Hooks.markCriticalIfNoMount 判为
     * 整体失效，不再静默。XposedModule 在 JVM 上无法构造，这里按代码库惯例只测
     * 原因形状与状态机（install 体内的 warn 调用不在此覆盖范围）。
     */
    @Test
    public void nullMethodSkipReasonFeedsCriticalDetection() {
        Feature f = Feature.HOME_MSG_FILTER;
        assertEquals("方法未找到 X#m", HookKit.nullMethodSkipReason("X#m"));

        HookState.markNotFound(f, HookKit.nullMethodSkipReason("X#m"));
        assertFalse("单条 skip 不应直接 critical", HookState.hasCriticalSkip(f));
        Hooks.markCriticalIfNoMount(f);
        assertTrue("install(null) 记录 skip 后，零挂载功能必须能判 crit（消除静默失效）",
                HookState.hasCriticalSkip(f));
    }

    /**
     * 延迟兜底的 miss 收口：anchors 全空时 findMethod 直接返回 null（不触 DexKit
     * native），任务必须记 skip 并按 {@link Hooks#markCriticalIfNoMount} 判整体失效，
     * 与同步路径的失败语义一致（零挂载时 skip 统一改写为「无可用挂载点」）。
     */
    @Test
    public void lateAnchorMissRecordsSkipAndCritical() {
        Feature f = Feature.SPLASH_REQUEST;
        HookKit.finishLateAnchor(null, f, "X#m", null, null, "m",
                void.class, new Class<?>[0], new String[0]);
        assertEquals("无可用挂载点", HookState.skipReason(f));
        assertTrue("零挂载 + skip 必须整体失效", HookState.hasCriticalSkip(f));
    }

    /**
     * 延迟兜底的幂等契约：同一 Feature 只提交一次。installAll 重试（安装抛错后
     * 回滚 sInstalled）不得重复排队任务、重复落钩。
     */
    @Test
    public void lateAnchorSubmitsOnlyOncePerFeature() {
        Executor previous = HookKit.sDexKitLateExecutor;
        HookKit.sDexKitLateExecutor = Runnable::run;
        try {
            Feature f = Feature.SPLASH_GATE;
            assertTrue("首次提交应成功（同步执行器内联跑完 miss 收口）",
                    HookKit.installByAnchorLate(null, f, "Y#m", null, null, "m",
                            void.class, new Class<?>[0], new String[0]));
            assertFalse("同一 Feature 第二次提交必须被忽略",
                    HookKit.installByAnchorLate(null, f, "Y#m", null, null, "m",
                            void.class, new Class<?>[0], new String[0]));
        } finally {
            HookKit.sDexKitLateExecutor = previous;
        }
    }
}
