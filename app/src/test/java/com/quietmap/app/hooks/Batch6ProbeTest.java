package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;

import org.junit.Test;

/**
 * Batch6Probe 的纯 JVM 逻辑：探针的类定位不得写 HookState。
 *
 * 背景：探针曾经把 Feature.MSG_CENTER 直接传给 HookKit.findClass/install，
 * DEBUG_PROBE=true 时探针标签会记成成功挂载、探针类缺失会写 skip，既掩盖
 * 真实的零挂载 critical，也在诊断页留下假 skip。现在定位/挂载都走探针本地
 * 实现，只打 PROBE 日志。挂载路径需要 XposedModule，JVM 无法构造，故本类
 * 只覆盖类定位的两条路径；HookState 断言用「前后一致」而不是绝对空值，
 * 避免与同 JVM 内其它用例对 MSG_CENTER 的写入互相污染。
 */
public class Batch6ProbeTest {

    @Test
    public void probeFindClassResolvesWithoutTouchingHookState() {
        String skipBefore = HookState.skipReason(Feature.MSG_CENTER);
        int installsBefore = HookState.installedCount(Feature.MSG_CENTER);

        Class<?> cls = Batch6Probe.probeFindClass(getClass().getClassLoader(),
                "com.quietmap.app.hooks.Batch6ProbeTest");
        assertNotNull(cls);

        assertEquals("探针定位成功不得写 HookState.skip", skipBefore,
                HookState.skipReason(Feature.MSG_CENTER));
        assertEquals("探针定位成功不得写 HookState.installed", installsBefore,
                HookState.installedCount(Feature.MSG_CENTER));
    }

    @Test
    public void probeClassMissDoesNotMarkNotFound() {
        String skipBefore = HookState.skipReason(Feature.MSG_CENTER);

        assertNull("不存在的类应返回 null",
                Batch6Probe.probeFindClass(getClass().getClassLoader(), "no.such.ProbeClass"));

        assertEquals("探针类缺失不得写 skip（HookKit.findClass 会写「类未找到」，探针不得）",
                skipBefore, HookState.skipReason(Feature.MSG_CENTER));
        assertFalse("探针类缺失不得置 critical", HookState.hasCriticalSkip(Feature.MSG_CENTER));
    }
}
