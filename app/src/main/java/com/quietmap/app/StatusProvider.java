package com.quietmap.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 目标进程（高德）→ 模块 App 的诊断状态通道。
 *
 * 原名 ReportProvider，统一核心类命名后改为 StatusProvider；authority 仍为
 * ${applicationId}.qmreport，避免已安装版本与目标进程回传地址失效。
 *
 * 方向不能反过来：remote prefs 在目标进程只读，openRemoteFile 也是只读，
 * 所以「哪些钩子注册了 / 命中了多少次」只能由目标进程主动 call 进来。
 *
 * 安全边界：provider 必须 exported 才能被目标进程跨应用调用，因此每次 call 都校验
 * 调用方 UID 必须属于 com.autonavi.minimap；key 只接受 ReportProtocol 白名单，
 * value 有长度上限。不存任何敏感信息。
 *
 * key 形如 reg.bg_msg_fetch / hit.bg_msg_fetch / entry.bg_msg_fetch /
 * skip.bg_msg_fetch / crit.bg_msg_fetch，value 对应注册标签、命中计数、
 * 入口计数、跳过原因与整体失效标记；另有固定键 crash0/crash1/crash2
 * （最近崩溃，crash0 最新），与 reg/skip 一样按最新值覆盖、空串清残留。
 *
 * 会话语义：新主进程 session 首次写入前清掉上一会话数据。session 只做身份
 * 比较（不同即新会话），不比较启动毫秒——时钟回拨后新会话的时间戳反而更早，
 * 按毫秒排序会把新会话整批丢弃并冻结诊断页。超过 {@link #REPORT_TTL_MS}
 * 没有新回传的数据视为过期，失败计数按 0。
 */
public final class StatusProvider extends ContentProvider {

    private static final String TAG = "QuietMap";

    public static final String AUTHORITY = "io.github.yigenhuobah.quietmap.qmreport";
    public static final String PREFS = "qm_report";

    /** Provider 通道最后写入时间；用于和文件兜底的 mtime 比较新旧。 */
    static final String PREFS_UPDATED_AT = ReportProtocol.PREFS_UPDATED_AT_KEY;
    /** 主进程最近一次运行的 session；变化时清理旧会话的 reg/skip/hit/entry。 */
    static final String PREFS_SESSION = ReportProtocol.PREFS_SESSION_KEY;

    public static final String PREFIX_REG = ReportProtocol.PREFIX_REG;
    public static final String PREFIX_HIT = ReportProtocol.PREFIX_HIT;
    public static final String PREFIX_ENTRY = ReportProtocol.PREFIX_ENTRY;
    public static final String PREFIX_SKIP = ReportProtocol.PREFIX_SKIP;
    public static final String PREFIX_CRIT = ReportProtocol.PREFIX_CRIT;

    /** 回传数据的新鲜度上限，与 ReportProtocol 的默认 TTL 保持一致。 */
    static final long REPORT_TTL_MS = ReportProtocol.DEFAULT_TTL_MS;

    /**
     * 内容未变化时刷新 meta.updated_at 的最短间隔。Report 每 60 秒心跳重发
     * 完整快照，这里每 5 分钟落一次时间戳，避免 TTL 把仍在运行的目标进程
     * 判成“未知”，也避免每次心跳都写 prefs。
     */
    static final long REPORT_HEARTBEAT_MS = 5 * 60_000L;

    /** 落盘时统一加 "v:" 前缀，避免与旧版本可能存在的同名键冲突。 */
    private static final String VALUE_PREFIX = "v:";

    /**
     * 同步落盘标记：崩溃路径的 flush 要求 Provider 用 commit() 而不是 apply()，
     * 确保 call() 返回时数据已经写入。普通心跳回传不设置，继续用 apply()。
     */
    public static final String EXTRA_SYNC = "sync";

    /** call() 结果 Bundle：Provider 是否成功接受并落盘（commit 失败时为 false）。 */
    public static final String RESULT_OK = "ok";
    /** call() 结果 Bundle：本批是否实际改变了存储（仅 RESULT_OK=true 时有效）。 */
    public static final String RESULT_CHANGED = "changed";

    private static final int MAX_ITEMS = 800;

    /** Provider.onCreate 在主线程；目录/占位文件 I/O 放到单线程后台初始化。 */
    private static final ExecutorService FILE_INIT_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qm-report-init");
                t.setDaemon(true);
                return t;
            });

    @Override
    public boolean onCreate() {
        final Context context = getContext();
        if (context != null) {
            // Application.onCreate 通常已经启动；这里再兜底一次，幂等。
            try {
                ReportSocketServer.start(context);
            } catch (Throwable t) {
                Log.w(TAG, "qmreport socket server start failed", t);
            }
        }
        // 模块 App 进程启动时后台预创建 Android/media/<模块包名>/qm_report.json。
        // 失败只记日志，不影响 Provider；onCreate 不阻塞主线程。
        if (context != null) {
            try {
                FILE_INIT_EXECUTOR.execute(() -> {
                    try {
                        ReportFileReader.ensureSharedFile(context);
                    } catch (Throwable t) {
                        Log.w(TAG, "qmreport file sink ensure failed", t);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "qmreport file sink init submit failed", t);
            }
        }
        return true;
    }

    /**
     * synchronized：高德 3 个目标进程的 binder 线程可能并发进来，
     * hit 的 getLong→compare→putLong 必须串行，否则较大计数会被较小值覆盖（P2-2）。
     */
    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (!"report".equals(method) || extras == null) {
            return null;
        }
        if (!isAllowedCaller(context, Binder.getCallingUid())) {
            return null;
        }
        String[] keys = extras.getStringArray("keys");
        String[] values = extras.getStringArray("values");
        if (keys == null || values == null || keys.length != values.length) {
            return null;
        }
        if (keys.length > MAX_ITEMS) {
            return null;
        }
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != null && values[i] != null) {
                data.put(keys[i], values[i]);
            }
        }
        Boolean applied = applyReport(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                data, extras.getString("session"),
                extras.getBoolean("main", false), ReportProtocol.SOURCE_PROVIDER,
                extras.getBoolean(EXTRA_SYNC, false));
        // 结果 Bundle 必须显式携带 ok：sync=true 时 commit() 失败会让 applied
        // 变成 null，调用方不能只凭“call 返回了非 null Bundle”就认为落盘成功。
        Bundle result = new Bundle();
        result.putBoolean(RESULT_OK, applied != null);
        if (applied != null) {
            result.putBoolean(RESULT_CHANGED, applied);
        }
        return result;
    }

    /**
     * 诊断数据落到 SharedPreferences 的唯一入口：Provider.call 与
     * {@link ReportSocketServer} 共用，保证两条通道的合并语义一致
     * （hit/entry 取历史最大、reg/skip/crit 最新值覆盖、主进程新 session
     * 清理旧数据；session 按身份比较，不比较启动时间）。crash0/1/2 属于
     * 文本键，同样按最新值覆盖、空串清掉旧崩溃残留。
     *
     * 进程边界：非主进程只允许合并 hit./entry.，reg./skip./crit. 与 crash 键
     * 直接丢弃。Report 现在只在主进程启动（UEH 也只装在主进程），这里兜住
     * 旧版本子进程仍在上报的数据，避免“子进程缺少 UI 钩子”的 skip 污染失败统计。
     *
     * 时间戳：主进程的实际变更会刷新 meta.updated_at；内容未变的 5 分钟心跳
     * 也刷新一次，使 failure TTL 不会把仍在正常运行的目标进程判成“未知”。
     * 子进程只合并计数，不刷新时间戳，避免旧 reg/skip 被计数“续命”。
     *
     * @param sync true 时用 {@code commit()} 同步落盘（崩溃路径），false 时用
     *             {@code apply()} 异步落盘（常规心跳）
     * @return 是否实际写入或执行了会话清理；仅记 session 不算。
     *         {@code sync=true} 且 commit 失败时返回 {@code null}——调用方必须
     *         据此继续走文件 fsync+rename 兜底，不能当成成功。
     */
    static synchronized Boolean applyReport(SharedPreferences sp, Map<String, String> data,
                                            String session, boolean mainProcess,
                                            String source, boolean sync) {
        if (sp == null || data == null || data.size() > MAX_ITEMS) {
            return false;
        }
        String storedSession = sp.getString(PREFS_SESSION, null);
        // session 只做身份比较：不同即新会话（下面 shouldResetSession 清理旧数据），
        // 相同即同进程继续合并。不再按启动毫秒拒绝“迟到”批次：时钟回拨后新会话
        // 时间戳更早，按毫秒排序会让每一批都被丢弃，诊断页冻结在上一轮数据。
        SharedPreferences.Editor editor = sp.edit();
        boolean reset = ReportProtocol.shouldResetSession(
                storedSession, session, mainProcess);
        if (reset) {
            // 目标 App 主进程已重启：旧会话的 reg/skip/hit/entry/crit 全部作废，
            // 避免上一轮运行的 skip 永久保留并伪装成新数据。
            editor.clear();
        }
        long now = System.currentTimeMillis();
        long storedUpdatedAt = sp.getLong(PREFS_UPDATED_AT, 0L);
        boolean changed = false;
        boolean accepted = false;
        for (Map.Entry<String, String> entry : data.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!ReportProtocol.validKey(key) || !ReportProtocol.isValidValue(value)) {
                continue;
            }
            if (!ReportProtocol.acceptFromProcess(mainProcess, key)) {
                // 子进程只贡献 hit/entry；reg/skip/crit 只信任主进程。
                continue;
            }
            accepted = true;
            if (ReportProtocol.isCountKey(key)) {
                // 命中/入口计数取历史最大值：高德多进程各自回传，取 max 不会重复累加，
                // 也不会被另一进程的低计数覆盖回去。新会话从 0 开始。
                long prev = reset ? 0L : sp.getLong(VALUE_PREFIX + key, 0L);
                Long next = ReportProtocol.parseHit(value);
                if (next == null) {
                    continue;
                }
                long merged = ReportProtocol.mergeHit(prev, next);
                if (merged > prev) {
                    editor.putLong(VALUE_PREFIX + key, merged);
                    changed = true;
                }
            } else {
                // reg/skip/crit 以最新值覆盖（空串也覆盖）：目标进程重开后，
                // 本次没有 skip/crit 的功能会回传空串，必须清掉上一次会话的旧值。
                String existing = reset ? null
                        : sp.getString(VALUE_PREFIX + key, null);
                if (ReportProtocol.valueChanged(key, existing, value)) {
                    editor.putString(VALUE_PREFIX + key, value);
                    changed = true;
                }
            }
        }
        if (mainProcess && session != null && !session.isEmpty()) {
            editor.putString(PREFS_SESSION, session);
        }
        // 实际写入（含会话切换清理）刷新时间戳；内容未变的主进程心跳每 5 分钟
        // 刷新一次，避免 TTL 把运行中的目标进程判成过期。
        boolean heartbeat = mainProcess && accepted
                && !ReportProtocol.isFresh(reset ? 0L : storedUpdatedAt,
                        now, REPORT_HEARTBEAT_MS);
        if (changed || heartbeat) {
            editor.putLong(PREFS_UPDATED_AT, now);
        }
        if (changed || reset) {
            // 记录最后一个真正写入数据的通道，诊断页据此显示 socket / Provider。
            editor.putString(ReportProtocol.PREFS_SOURCE_KEY,
                    source != null ? source : ReportProtocol.SOURCE_PROVIDER);
        }
        boolean wrote = changed || reset || heartbeat
                || (mainProcess && session != null && !session.isEmpty());
        if (wrote) {
            if (sync) {
                // 崩溃路径：call() 返回前必须确认已经落盘。commit 失败返回 null，
                // 让 Report 继续走文件 fsync+rename 兜底，绝不静默丢失数据。
                if (!editor.commit()) {
                    return null;
                }
            } else {
                editor.apply();
            }
        }
        return changed || reset;
    }

    /** Context 胶水：Provider.call 与 ReportSocketServer 共用的入口。 */
    static synchronized Boolean applyReport(Context context, Map<String, String> data,
                                            String session, boolean mainProcess,
                                            String source, boolean sync) {
        if (context == null) {
            return false;
        }
        return applyReport(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                data, session, mainProcess, source, sync);
    }

    /**
     * 诊断数据统一读入口：文件优先，Provider/SharedPreferences 兜底。
     * 文件比 prefs 新（或 prefs 没有时间戳）时使用文件。
     */
    public static ReportState readState(Context context) {
        return ReportFileReader.read(context);
    }

    /** 只读 SharedPreferences，供 ReportFileReader 做新旧比较与兜底。 */
    static ReportState providerState(Context context) {
        if (context == null) {
            return ReportState.none();
        }
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            Map<String, ?> all = sp.getAll();
            Map<String, String> data = new TreeMap<>();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (key == null || !key.startsWith(VALUE_PREFIX)) {
                    continue;
                }
                Object value = e.getValue();
                String plain = key.substring(VALUE_PREFIX.length());
                if (value instanceof String) {
                    data.put(plain, (String) value);
                } else if (value instanceof Long) {
                    data.put(plain, String.valueOf(value));
                }
            }
            long updatedAt = 0L;
            Object timestamp = all.get(PREFS_UPDATED_AT);
            if (timestamp instanceof Long) {
                updatedAt = (Long) timestamp;
            }
            // 主进程最近一次会话；文件读取侧据此忽略旧会话文件。
            String session = null;
            Object storedSession = all.get(PREFS_SESSION);
            if (storedSession instanceof String) {
                session = (String) storedSession;
            }
            Object source = all.get(ReportProtocol.PREFS_SOURCE_KEY);
            if (ReportProtocol.SOURCE_SOCKET.equals(source)) {
                return ReportState.fromSocket(data, updatedAt, session);
            }
            return ReportState.fromProvider(data, updatedAt, session);
        } catch (Throwable t) {
            return ReportState.none();
        }
    }

    /** 读取一个回传字符串（reg/skip）；没有数据或读取失败返回 null。 */
    public static String reportString(Context context, String prefix, String key) {
        if (context == null) {
            return null;
        }
        return readState(context).value(prefix, key);
    }

    /** 读取命中计数；没有数据或读取失败返回 0。 */
    public static long reportHit(Context context, String key) {
        if (context == null) {
            return 0L;
        }
        return readState(context).hit(key);
    }

    /** 失败统计用的开关查询；抽成接口后纯逻辑版本可直接单测。 */
    @FunctionalInterface
    interface EnabledLookup {
        boolean enabled(Feature feature);
    }

    /**
     * 统计“已启用且明确失败”的功能数，供异常横幅使用。
     *
     * 失败判定（见 {@link ReportProtocol#featureFailed}）：用户配置为开启；
     * 当前会话有回传（reg/skip/hit/entry 任一非空）；且 reg 为空（没有任何
     * 成功挂载点）或 crit=1（主锚点整体失效）。普通 skip 属于可选锚点缺失，
     * 只用于诊断页展示，不再计入横幅。
     *
     * 进程/会话边界：
     * - Report 只在目标 App 主进程启动，子进程不再回传 reg/skip/crit；
     * - {@link #applyReport} 对旧版本子进程上报的 reg./skip. 直接忽略，
     *   只合并 hit./entry.，因此子进程缺少 UI 钩子的 skip 不会被算成失败；
     * - FILE_STALE 只是过期文件的展示兜底，直接返回 0，不参与失败统计；
     * - 数据超过 {@link #REPORT_TTL_MS} 没有新回传时按“未知”处理，返回 0，
     *   避免旧会话长期误报横幅。
     */
    public static int failedFeatureCount(Context context) {
        if (context == null) {
            return 0;
        }
        ReportState state = readState(context);
        if (state.source() == ReportState.Source.FILE_STALE) {
            // 过期文件只用于诊断页展示，不计入失败数。
            return 0;
        }
        ConfigStore store;
        try {
            store = new ConfigStore(context);
        } catch (Throwable t) {
            store = null;
        }
        final ConfigStore configStore = store;
        return failedFeatureCount(state, f -> configStore != null
                ? configStore.get(f.key, f.defaultEnabled) : f.defaultEnabled);
    }

    /** 纯逻辑版本：FILE_STALE 与过期数据一律 0；只统计明确失败的功能。 */
    static int failedFeatureCount(ReportState state, EnabledLookup lookup) {
        return failedFeatureCount(state, lookup, System.currentTimeMillis());
    }

    /** 固定 now 的重载，便于 JVM 单测精确覆盖 TTL 边界。 */
    static int failedFeatureCount(ReportState state, EnabledLookup lookup, long now) {
        if (state == null || state.source() == ReportState.Source.FILE_STALE) {
            return 0;
        }
        if (!ReportProtocol.isFresh(state.updatedAt(), now, REPORT_TTL_MS)) {
            // 没有时间戳或超过 TTL：目标进程可能已经退出，按“未知”处理。
            return 0;
        }
        int failed = 0;
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            boolean enabled = lookup != null ? lookup.enabled(f) : f.defaultEnabled;
            if (!enabled) {
                continue;
            }
            if (ReportProtocol.featureFailed(
                    state.reg(f.key),
                    state.skip(f.key),
                    state.critical(f.key),
                    state.hit(f.key),
                    state.entry(f.key))) {
                failed++;
            }
        }
        return failed;
    }

    /** 只放行高德宿主 UID；模块自身与其他任意应用一律拒绝，不写盘也不抛异常。 */
    private static boolean isAllowedCaller(Context context, int uid) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (ReportPaths.TARGET_PACKAGE.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
