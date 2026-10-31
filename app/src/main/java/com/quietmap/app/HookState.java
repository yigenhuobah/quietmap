package com.quietmap.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 目标进程内的诊断状态：每个钩子的注册标签、命中次数、跳过原因与整体失效标记。
 *
 * 真正带细节的日志由 HookKit 打进 logcat（tag QuietMap）；这里只存计数快照，
 * 由 hooks 包的 Report 定期回传给模块 App 的 StatusProvider，供设置页诊断卡展示。
 *
 * 线程安全：命中可能来自任意线程（含 binder 线程），全部用并发容器。
 */
public final class HookState {

    private static final ConcurrentMap<Feature, State> STATES = new ConcurrentHashMap<>();

    private HookState() {
    }

    private static final class State {
        final Set<String> labels = ConcurrentHashMap.newKeySet();
        /** 主锚点整体失效的原因集合；非空即表示该功能没有任何可用挂载点。 */
        final Set<String> criticalSkips = ConcurrentHashMap.newKeySet();
        final AtomicLong hits = new AtomicLong();
        final AtomicLong entries = new AtomicLong();
        volatile String skip;

        void markSkip(String reason) {
            if (reason != null) {
                skip = reason;
            }
        }
    }

    private static State state(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            s = new State();
            State prev = STATES.putIfAbsent(f, s);
            if (prev != null) {
                s = prev;
            }
        }
        return s;
    }

    public static void installed(Feature f, String label) {
        state(f).labels.add(label);
    }

    /**
     * 可选锚点缺失：只记录 skip 供诊断页展示，不计入失败横幅。
     * 方法改名/签名变化、可选类缺失都属于这一类。
     */
    public static void markNotFound(Feature f, String reason) {
        state(f).markSkip(reason);
    }

    /**
     * 主锚点整体缺失（该 Feature 没有任何成功安装的挂载点）：除记录 skip 外
     * 再打上 critical 标记，让 ReportProtocol.featureFailed 把功能计入失败统计。
     */
    public static void markNotFoundCritical(Feature f, String reason) {
        State s = state(f);
        s.markSkip(reason);
        if (reason != null) {
            s.criticalSkips.add(reason);
        }
    }

    /** 本会话是否出现过主锚点整体失效。 */
    public static boolean hasCriticalSkip(Feature f) {
        State s = STATES.get(f);
        return s != null && !s.criticalSkips.isEmpty();
    }

    /**
     * 清除该功能的失败标记（skip 原因与 critical 集合）；labels/hits/entries 保留。
     *
     * 用于 QuietMapModule 回滚 sInstalled 后的重试：失败标记只增不清，上一轮安装
     * 记下的 skip/crit 若不在下一轮安装前清掉，重试成功的功能会继续上报 crit=1
     * 与「无可用挂载点」，诊断页和失败横幅会把已挂载的功能判成整体失效。
     * 重试要么以干净状态成功，要么重新记录本轮的真实失败。
     */
    public static void clearFailures(Feature f) {
        State s = STATES.get(f);
        if (s != null) {
            s.criticalSkips.clear();
            s.skip = null;
        }
    }

    /** 命中计数 +1，返回累加后的次数（供 HookKit 做日志采样）。 */
    public static long hit(Feature f) {
        return state(f).hits.incrementAndGet();
    }

    /**
     * 入口计数 +1：hooker 每次被调用都记一次，用于区分
     * “挂载点从未被调用”和“调用了但未命中/未改写”。
     * 与 hit 独立，不影响既有诊断字段。
     */
    public static void entry(Feature f) {
        state(f).entries.incrementAndGet();
    }

    // ---- 快照（供 Report 回传） ----

    public static List<Feature> touched() {
        return new ArrayList<>(STATES.keySet());
    }

    /** 成功安装的挂载点数量（labels 去重后的条目数）；0 表示整体未挂载。 */
    public static int installedCount(Feature f) {
        State s = STATES.get(f);
        return s == null ? 0 : s.labels.size();
    }

    public static List<String> labels(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(s.labels);
        Collections.sort(out);
        return out;
    }

    public static long hitCount(Feature f) {
        State s = STATES.get(f);
        return s == null ? 0L : s.hits.get();
    }

    public static long entryCount(Feature f) {
        State s = STATES.get(f);
        return s == null ? 0L : s.entries.get();
    }

    public static String skipReason(Feature f) {
        State s = STATES.get(f);
        return s == null ? null : s.skip;
    }
}
