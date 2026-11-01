package com.quietmap.app;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 诊断页的一次读取结果：数据来源、最后更新时间、文件路径、会话标识与键值。
 *
 * 纯 Java，便于单测锁定「文件与 prefs 按 key 合并、较新且非空者优先」的
 * 选择逻辑。session 用于识别旧会话文件：文件 session 与当前主进程 session
 * 不一致时不得参与合并。FILE_STALE 表示文件已超过 TTL，仅用于展示
 * “最近一次数据 + 时间”，不参与失败统计。
 */
public final class ReportState {

    public enum Source {
        NONE,
        FILE,
        FILE_STALE,
        PROVIDER,
        SOCKET
    }

    private static final ReportState EMPTY = new ReportState(
            Source.NONE, 0L, null, null, Collections.emptyMap());

    private final Source source;
    private final long updatedAt;
    private final String path;
    private final String session;
    private final Map<String, String> data;

    private ReportState(Source source, long updatedAt, String path, String session,
                        Map<String, String> data) {
        this.source = source;
        this.updatedAt = updatedAt;
        this.path = path;
        this.session = session;
        this.data = Collections.unmodifiableMap(new TreeMap<>(
                data != null ? data : Collections.emptyMap()));
    }

    public static ReportState none() {
        return EMPTY;
    }

    public static ReportState fromFile(ReportFileStore.Entry entry) {
        if (entry == null) {
            return EMPTY;
        }
        return new ReportState(Source.FILE, entry.snapshot.updatedAt(),
                entry.file != null ? entry.file.getPath() : null,
                entry.snapshot.session(), entry.snapshot.data());
    }

    /** 文件已过期，但仍展示最后一份有效快照及其时间。 */
    public static ReportState fromStaleFile(ReportFileStore.Entry entry) {
        if (entry == null) {
            return EMPTY;
        }
        return new ReportState(Source.FILE_STALE, entry.snapshot.updatedAt(),
                entry.file != null ? entry.file.getPath() : null,
                entry.snapshot.session(), entry.snapshot.data());
    }

    public static ReportState fromProvider(Map<String, String> data, long updatedAt) {
        return fromProvider(data, updatedAt, null);
    }

    public static ReportState fromProvider(Map<String, String> data, long updatedAt,
                                           String session) {
        return new ReportState(Source.PROVIDER, updatedAt, null, session,
                ReportFileStore.sanitize(data));
    }

    /** socket 通道写入的 prefs 数据；与 Provider 数据同 schema，只区来源。 */
    public static ReportState fromSocket(Map<String, String> data, long updatedAt) {
        return fromSocket(data, updatedAt, null);
    }

    public static ReportState fromSocket(Map<String, String> data, long updatedAt,
                                         String session) {
        return new ReportState(Source.SOCKET, updatedAt, null, session,
                ReportFileStore.sanitize(data));
    }

    /**
     * 文件 session 是否属于当前主进程会话。任一侧缺失时按兼容处理：
     * 旧文件没有 session、或尚未收到主进程 session 时仍可使用；
     * 双方都有值时必须完全相等，否则该文件来自旧会话，必须忽略。
     */
    public static boolean sessionCompatible(String currentSession, String fileSession) {
        if (currentSession == null || currentSession.isEmpty()
                || fileSession == null || fileSession.isEmpty()) {
            return true;
        }
        return currentSession.equals(fileSession);
    }

    /**
     * 文件与 prefs 按 key 合并，而不是整源二选一：
     * - 两个源都有的 key：hit/entry 计数取最大值；reg/skip 取较新源的值，
     *   较新值为空时回退较旧的非空值（更完整）；crit 与 crash0/1/2 严格取
     *   较新源的值（空串也必须覆盖旧值，否则旧 crit=1 / 旧崩溃清不掉）；
     * - 只有一个源有的 key：保留该值；
     * - 返回状态的 source/path/updatedAt/session 取时间较新的源。
     * - 文件 session 与 prefs（当前主进程）session 不一致时，文件整份忽略，
     *   避免旧会话的 reg/skip 被合并进当前会话。
     */
    public static ReportState pick(ReportFileStore.Entry file, ReportState provider) {
        ReportState current = provider != null ? provider : EMPTY;
        if (file != null && !sessionCompatible(current.session(), file.snapshot.session())) {
            file = null;
        }
        if (file == null) {
            return current.hasData() ? current : EMPTY;
        }
        if (!current.hasData()) {
            return fromFile(file);
        }
        boolean fileNewer = current.updatedAt() <= 0L
                || file.effectiveTime() >= current.updatedAt();
        Map<String, String> fileData = file.snapshot.data();
        Map<String, String> providerData = current.data;
        Set<String> keys = new TreeSet<>(fileData.keySet());
        keys.addAll(providerData.keySet());

        Map<String, String> merged = new TreeMap<>();
        for (String key : keys) {
            String fromFile = fileData.get(key);
            String fromProvider = providerData.get(key);
            if (fromFile == null) {
                merged.put(key, fromProvider);
            } else if (fromProvider == null) {
                merged.put(key, fromFile);
            } else if (fromFile.equals(fromProvider)) {
                merged.put(key, fromFile);
            } else if (ReportProtocol.isCountKey(key)) {
                merged.put(key, newerCount(fromFile, fromProvider));
            } else if (key.startsWith(ReportProtocol.PREFIX_CRIT)) {
                // crit 必须完全按“最新值覆盖”：较新来源的空串代表功能已恢复，
                // 不能像 reg/skip 那样回退旧的非空值，否则旧 crit=1 永远清不掉。
                merged.put(key, fileNewer ? fromFile : fromProvider);
            } else if (isCrashKey(key)) {
                // crash0/1/2 与 crit 同语义：写侧始终发送、无崩溃发空串
                // （见 hooks/Report.snapshot），空串必须能清掉上一轮残留的崩溃，
                // 所以同样严格取较新值，不回退旧的非空值。
                merged.put(key, fileNewer ? fromFile : fromProvider);
            } else if (fileNewer) {
                merged.put(key, preferNonEmpty(fromFile, fromProvider));
            } else {
                merged.put(key, preferNonEmpty(fromProvider, fromFile));
            }
        }
        Source source = fileNewer ? Source.FILE : current.source();
        long updatedAt = fileNewer ? file.snapshot.updatedAt() : current.updatedAt();
        String path = fileNewer && file.file != null ? file.file.getPath() : null;
        String fileSession = file.snapshot.session();
        String session = fileNewer
                ? (fileSession != null && !fileSession.isEmpty()
                        ? fileSession : current.session())
                : current.session();
        return new ReportState(source, updatedAt, path, session, merged);
    }

    private static String newerCount(String first, String second) {
        Long a = ReportProtocol.parseHit(first);
        Long b = ReportProtocol.parseHit(second);
        long left = a == null ? 0L : a;
        long right = b == null ? 0L : b;
        return String.valueOf(ReportProtocol.mergeHit(left, right));
    }

    /** crash0/1/2 是无前缀的固定键；与 crit 一样按“最新值覆盖”合并。 */
    private static boolean isCrashKey(String key) {
        return ReportProtocol.KEY_CRASH_0.equals(key)
                || ReportProtocol.KEY_CRASH_1.equals(key)
                || ReportProtocol.KEY_CRASH_2.equals(key);
    }

    /** 较新值优先；较新值为空串时用较旧的非空值，避免“较新”把已有信息清空。 */
    private static String preferNonEmpty(String preferred, String fallback) {
        if (preferred == null || preferred.isEmpty()) {
            return fallback;
        }
        return preferred;
    }

    public Source source() {
        return source;
    }

    /** 数据实际生成时间（文件内 updated_at）；0 表示未知。 */
    public long updatedAt() {
        return updatedAt;
    }

    /** 仅 Source.FILE / FILE_STALE 时非 null。 */
    public String path() {
        return path;
    }

    /**
     * 数据所属会话：FILE/FILE_STALE 来自文件快照，PROVIDER/SOCKET 来自 prefs
     * meta.session；旧文件或尚未收到主进程 session 时为 null。
     */
    public String session() {
        return session;
    }

    public boolean hasData() {
        return !data.isEmpty();
    }

    public String value(String prefix, String key) {
        return data.get(prefix + key);
    }

    public String reg(String key) {
        return value(ReportProtocol.PREFIX_REG, key);
    }

    public String skip(String key) {
        return value(ReportProtocol.PREFIX_SKIP, key);
    }

    /** crit 原始值："1" 表示整体失效，空串/null 表示正常或未回传。 */
    public String crit(String key) {
        return value(ReportProtocol.PREFIX_CRIT, key);
    }

    /** 该功能是否没有任何成功安装的挂载点（主锚点整体失效）。 */
    public boolean critical(String key) {
        return ReportProtocol.isCritical(crit(key));
    }

    public long hit(String key) {
        Long value = ReportProtocol.parseHit(
                data.get(ReportProtocol.PREFIX_HIT + key));
        return value == null ? 0L : Math.max(0L, value);
    }

    /** 入口计数；负数与非法值按 0 处理，与 hit 共用同一套规则。 */
    public long entry(String key) {
        Long value = ReportProtocol.parseHit(
                data.get(ReportProtocol.PREFIX_ENTRY + key));
        return value == null ? 0L : Math.max(0L, value);
    }
}
