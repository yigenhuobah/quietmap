package com.quietmap.app;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.yigenhuobah.core.ReportProtocolCore;

/**
 * 报表通道的纯 Java 协议逻辑：键白名单、取值长度、命中/入口计数合并、
 * 失败判定、新鲜度与会话重置判定。
 *
 * <p>协议主体已迁移到 {@link ReportProtocolCore}：QuietMap 的完整键白名单在
 * 静态初始化时一次性显式配置（Feature 的 reg/hit/entry/skip/crit +
 * crash0/1/2），合并/长度/新鲜度/失败判定直接委托 core。模块只保留 core
 * 未建模的扩展：
 * <ul>
 *   <li>{@code entry.} 计数前缀（core 只有 hit./err.，QuietMap 的入口计数
 *       不属于 hook 体异常，不能映射成 err.）；</li>
 *   <li>{@link #parseHit}、{@link #shouldResetSession}、{@link #valueChanged}、
 *       {@link #acceptFromProcess} 等接收端胶水。</li>
 * </ul>
 *
 * 不依赖任何 android.*，可直接在 JVM 单测（见 ReportProtocolTest）；
 * StatusProvider 只做 Binder / SharedPreferences 胶水，校验规则集中在这里。
 *
 * 统一诊断语义（与 QB 对齐）：
 * - reg. 有值表示至少一个挂载点安装成功；
 * - skip. 只描述可选锚点缺失，不再单独计入失败；
 * - crit.="1" 表示该功能没有任何成功安装的挂载点，整体失效；
 * - 已启用、有回传且（reg 为空或 crit=1）才计入失败横幅；
 * - meta.updated_at 超过 {@link #DEFAULT_TTL_MS} 视为未知，失败计数按 0。
 */
public final class ReportProtocol {

    public static final String PREFIX_REG = ReportProtocolCore.PREFIX_REG;
    public static final String PREFIX_HIT = ReportProtocolCore.PREFIX_HIT;
    /**
     * 入口计数前缀：QuietMap 模块扩展，core 未建模。
     * 与 hit. 一样是非负计数、跨进程取历史最大值。
     */
    public static final String PREFIX_ENTRY = "entry.";
    public static final String PREFIX_SKIP = ReportProtocolCore.PREFIX_SKIP;
    /**
     * 整体失效标记：值为 "1" 表示该功能没有任何成功安装的挂载点
     * （主锚点/类整体缺失）。可选方法缺失仍只写普通 skip，不计入失败横幅。
     */
    public static final String PREFIX_CRIT = ReportProtocolCore.PREFIX_CRIT;

    /** 最近崩溃回传键（crash0 最新，crash2 最旧）：键值通道的固定键名。 */
    public static final String KEY_CRASH_0 = ReportProtocolCore.KEY_CRASH_0;
    public static final String KEY_CRASH_1 = ReportProtocolCore.KEY_CRASH_1;
    public static final String KEY_CRASH_2 = ReportProtocolCore.KEY_CRASH_2;

    /** 超过 10 分钟没有新回传就视为过期，失败计数按“未知”处理（0）。 */
    public static final long DEFAULT_TTL_MS = ReportProtocolCore.DEFAULT_TTL_MS;

    /**
     * 时间戳来自未来的容忍窗口：超过 5 分钟（时钟回拨/手动改时间）视为过期，
     * 不再当新鲜数据，避免旧的未来时间戳长期冻结失败统计。
     */
    public static final long MAX_FUTURE_SKEW_MS = ReportProtocolCore.MAX_FUTURE_SKEW_MS;

    /** 目标进程一次运行的标识；Provider 用它判断是否需要清理旧会话数据。 */
    public static final String PREFS_UPDATED_AT_KEY = ReportProtocolCore.KEY_META_UPDATED_AT;
    public static final String PREFS_SESSION_KEY = ReportProtocolCore.KEY_META_SESSION;
    /** 最后一次实际写入诊断数据的通道，供诊断页显示 socket/Provider。 */
    public static final String PREFS_SOURCE_KEY = "meta.source";
    public static final String SOURCE_SOCKET = "socket";
    public static final String SOURCE_PROVIDER = "provider";

    public static final int MAX_KEY = ReportProtocolCore.MAX_KEY;
    public static final int MAX_VALUE = ReportProtocolCore.MAX_VALUE;

    /**
     * QuietMap 的完整白名单：每个 Feature 的 reg/hit/entry/skip/crit +
     * crash0/1/2（22 × 5 + 3 = 113 个键）。
     *
     * <p>必须用 {@link ReportProtocolCore#configure(Set)} 精确配置，不能再用
     * configure(featureKeys, layout) 的前缀生成：后者会额外放行 err.* 与
     * meta.session/meta.updated_at，而 QM 协议既不发送也不接收这些键。
     */
    private static final Set<String> ALLOWED_KEYS = buildAllowedKeys();

    static {
        // core 的进程级协议配置只在这里设置一次；显式白名单保证 validKey
        // 与目标进程实际发送的键集合完全一致。
        ReportProtocolCore.configure(ALLOWED_KEYS);
    }

    private ReportProtocol() {
    }

    private static Set<String> buildAllowedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Feature f : Feature.values()) {
            keys.add(PREFIX_REG + f.key);
            keys.add(PREFIX_HIT + f.key);
            keys.add(PREFIX_ENTRY + f.key);
            keys.add(PREFIX_SKIP + f.key);
            keys.add(PREFIX_CRIT + f.key);
        }
        keys.add(KEY_CRASH_0);
        keys.add(KEY_CRASH_1);
        keys.add(KEY_CRASH_2);
        return Collections.unmodifiableSet(keys);
    }

    /** 只接受白名单键；非空、长度上限一并校验，其他一律拒绝。 */
    public static boolean validKey(String key) {
        return ReportProtocolCore.validKey(key);
    }

    /** 值只校验非 null 与长度上限；计数 key 的可解析性由调用方再校验。 */
    public static boolean isValidValue(String value) {
        return ReportProtocolCore.isValidValue(value);
    }

    /**
     * 解析 hit./entry. 计数文本；非法、空、溢出或带空白一律返回 null，由调用方跳过。
     * 协议不使用 JSON，这里等价覆盖「畸形文本」输入。
     */
    public static Long parseHit(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 计数合并：多进程各自回传本进程计数，历史值取最大，避免重复累加，
     * 也避免较大计数被较小值覆盖。负数（异常输入）按 0 处理。
     */
    public static long mergeHit(long prev, long next) {
        return ReportProtocolCore.mergeHit(prev, next);
    }

    /** crit 值固定为 "1"；空串/缺失/其他文本都按“非 critical”处理。 */
    public static boolean isCritical(String value) {
        return ReportProtocolCore.isCritical(value);
    }

    /**
     * 某个 key 的新值是否需要落盘：hit./entry. 只在新值更大时需要；其他 key
     * 文本不同才需要。未实际变化时不更新 meta.updated_at，避免旧 skip/reg 被
     * 盖上当前时间戳后伪装成新鲜数据。
     */
    public static boolean valueChanged(String key, String existing, String incoming) {
        if (incoming == null) {
            return false;
        }
        if (isCountKey(key)) {
            Long next = parseHit(incoming);
            if (next == null) {
                return false;
            }
            Long prev = parseHit(existing);
            long prevValue = prev == null ? 0L : prev;
            return mergeHit(prevValue, next) > prevValue;
        }
        return !incoming.equals(existing);
    }

    /** hit./entry. 都是非负计数，共用解析、合并与「只增不减」写入规则。 */
    public static boolean isCountKey(String key) {
        return key != null
                && (key.startsWith(PREFIX_HIT) || key.startsWith(PREFIX_ENTRY));
    }

    /**
     * 进程级写入白名单：主进程可写全部键；非主进程只允许合并 hit./entry.，
     * reg./skip./crit. 一律丢弃。Report 现在只在主进程启动，这里兜住旧版本/
     * 其他路径仍在回传的子进程数据，避免“子进程缺少 UI 钩子”的 skip 被
     * failedFeatureCount 误算成失败。
     */
    public static boolean acceptFromProcess(boolean mainProcess, String key) {
        return mainProcess || isCountKey(key);
    }

    /**
     * 已启用功能是否有回传：reg/skip 非空、critical 标记或 hit/entry > 0。
     * 没有回传的功能不参与失败统计，避免刚安装或目标应用未启动时误报。
     */
    public static boolean reported(String reg, String skip, boolean critical,
                                   long hit, long entry) {
        return !isEmpty(reg) || !isEmpty(skip) || critical || hit > 0 || entry > 0;
    }

    /**
     * 已启用且有回传的功能是否明确失败：reg 为空（没有任何成功挂载点）
     * 或 crit=1（主锚点整体失效）。
     *
     * <p>core 的失败判定把 errors 作为 hook 体异常维度；QuietMap 的 entry 是
     * “hooker 被调用次数”，不能当成错误次数传入（否则 entry&gt;0 必然失败）。
     * 因此 core 判定之外只需补一条：只有 entry 回传、没有 reg/skip/crit/hit 的
     * 功能同样属于“已回传”，按 reg 为空/crit 判定。
     *
     * 普通 skip 属于可选方法缺失，只用于诊断页展示，不再计入失败横幅。
     */
    public static boolean featureFailed(String reg, String skip, boolean critical,
                                        long hit, long entry) {
        if (ReportProtocolCore.featureFailed(reg, skip, critical, hit, 0L)) {
            return true;
        }
        return entry > 0 && (isEmpty(reg) || critical);
    }

    /**
     * 回传数据是否仍新鲜。updatedAt <= 0 表示从未回传或旧版本没有时间戳；
     * 正常 TTL 逻辑保留；now 落后 updatedAt 5 分钟以内（小幅时钟偏差）按新鲜
     * 处理，超过 {@link #MAX_FUTURE_SKEW_MS} 的未来时间戳（时钟回拨/手动改
     * 时间）视为过期，不再当新鲜数据。
     */
    public static boolean isFresh(long updatedAt, long now, long ttlMs) {
        return ReportProtocolCore.isFresh(updatedAt, now, ttlMs);
    }

    /**
     * 只有主进程在新 session 第一次回传时才允许清理旧会话数据。
     * 子进程 session 不同（每个进程各有一个 UUID），不能让它们互相清空。
     *
     * 会话只按“身份”比较：session 字符串不同即新会话（清空旧数据），相同即
     * 同进程继续合并。不比较启动毫秒——设备时钟回拨后新会话的时间戳反而更早，
     * 按毫秒排序会把新会话整批当“迟到”丢弃，诊断页会冻结在上一轮数据。
     * 旧版本格式（纯数字/纯 UUID）同样按“不同即新会话”处理。
     */
    public static boolean shouldResetSession(String storedSession, String incomingSession,
                                             boolean mainProcess) {
        return mainProcess
                && incomingSession != null
                && !incomingSession.isEmpty()
                && !incomingSession.equals(storedSession);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
