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
        /**
         * DexKit 定位失败明细（native 初始化失败 / 查询异常 / 未命中）。
         * 与 skip 分开保存：调用方随后会写更笼统的「锚定失败」，
         * 直接覆盖 skip 会丢掉可定位的查询/类名信息。
         */
        final Set<String> dexkitFailures = ConcurrentHashMap.newKeySet();
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
     * DexKit 定位失败记账：native 初始化失败、查询抛异常、扫描未命中。
     *
     * 单独存放而不是写 skip：调用方随后会写更笼统的「锚定失败」，直接覆盖会
     * 丢掉能定位问题的查询/类名。{@link #skipReason} 会把明细合并进诊断文本，
     * 既不改变调用方 skip 的主语义，也让失败在诊断页可见。
     */
    public static void markDexKitFailure(Feature f, String reason) {
        if (reason != null) {
            state(f).dexkitFailures.add(reason);
        }
    }

    /** 本会话该功能的 DexKit 失败明细（排序副本）；无失败返回空列表。 */
    public static List<String> dexKitFailures(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(s.dexkitFailures);
        Collections.sort(out);
        return out;
    }

    /**
     * 撤销一条已记录的 DexKit 失败明细（精确匹配）。
     *
     * 用于 attempt1 抛异常、attempt2 成功兜底的场景：attempt1 的异常不再代表
     * 本次定位失败，若留在明细里会让诊断页继续展示假 skip。只删除完全匹配的
     * 条目，同功能其他查询/native 初始化失败不受影响。
     */
    public static void clearDexKitFailure(Feature f, String reason) {
        if (reason == null) {
            return;
        }
        State s = STATES.get(f);
        if (s != null) {
            s.dexkitFailures.remove(reason);
        }
    }

    /**
     * 清除该功能的失败标记（skip 原因、critical 集合与 DexKit 失败明细）；
     * labels/hits/entries 保留。
     *
     * 用于 QuietMapModule 回滚 sInstalled 后的重试：失败标记只增不清，上一轮安装
     * 记下的 skip/crit/dexkit 若不在下一轮安装前清掉，重试成功的功能会继续上报
     * crit=1 与「无可用挂载点」，诊断页和失败横幅会把已挂载的功能判成整体失效。
     * 重试要么以干净状态成功，要么重新记录本轮的真实失败。
     */
    public static void clearFailures(Feature f) {
        State s = STATES.get(f);
        if (s != null) {
            s.criticalSkips.clear();
            s.dexkitFailures.clear();
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

    /**
     * 诊断页展示的 skip 文本：调用方写入的 skip 优先；存在 DexKit 失败明细时
     * 追加一条（排序后取第一条），保证「锚定失败」不会掩盖具体查询/异常。
     * 无任何失败返回 null。
     */
    public static String skipReason(Feature f) {
        State s = STATES.get(f);
        if (s == null) {
            return null;
        }
        return composeSkipReason(s.skip, s.dexkitFailures);
    }

    /**
     * skip 文本组合的纯逻辑（包内可见供单测）：先对失败明细做快照再判空。
     *
     * 不能先 {@code isEmpty()} 再 {@code Collections.min(集合)}：clearFailures
     * 会在其他线程清空 dexkitFailures，两步之间被清空时 min 会抛
     * NoSuchElementException（被 Report 的 catch 吞掉，表现为漏报一轮）。
     * 快照后所有判断都基于同一份数据，并发清空最多得到空快照。
     */
    static String composeSkipReason(String skip, Set<String> dexkitFailures) {
        List<String> snapshot = new ArrayList<>(dexkitFailures);
        String dexkit = snapshot.isEmpty() ? null : Collections.min(snapshot);
        if (skip == null) {
            return dexkit;
        }
        if (dexkit == null) {
            return skip;
        }
        return skip + " | " + dexkit;
    }
}
