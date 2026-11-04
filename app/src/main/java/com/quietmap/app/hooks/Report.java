package com.quietmap.app.hooks;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;
import com.quietmap.app.QuietMapModule;
import com.quietmap.app.ReportProtocol;
import com.quietmap.app.ReportSocketProtocol;
import com.quietmap.app.StatusProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 目标进程 → 模块 App 的诊断回传（registered / entry N / hit N / skip / critical）。
 *
 * HyperOS 上 ContentResolver.call 抛 Unknown authority，Provider 不可用；
 * Android 11+ 模块 App 也读不到目标进程写的 Android/media 文件。因此主通道改为
 * 抽象命名空间 LocalSocket（见 ReportSocketSink / ReportSocketServer）：
 * 不依赖存储权限，也不受包可见性影响。通道顺序固定为
 * socket → Provider → 文件，前一条成功即停止；日志统一带 transport=。
 *
 * 节流：后台单线程每 3 秒把 HookState 快照打平上报一次，内容没变不发；
 * 三条通道全部失败时退避 60 秒，不刷日志。socket/Provider 都是进程外通道，
 * 即使快照没变也每 60 秒重发一次：模块 App 可能在目标进程存活期间清空/重建
 * prefs，心跳能把当前会话的数据补回去。去重条件同时包含 session。
 *
 * 文件通道已降级为调试/旧系统兜底：只有 socket 与 Provider 都失败时才写，
 * 并且仍受 45 秒变化节流 / 5 分钟心跳限制。Android 11+ 通常不可用。
 *
 * 进程边界：本类只在目标 App 主进程启动（见 Hooks.shouldStartReport）。
 * 子进程只承担后台组件，继续上报只会用不完整的 reg/skip 污染诊断；
 * StatusProvider 对旧版本子进程回传的 reg./skip. 也会忽略，只合并计数键。
 *
 * session：主进程每次启动生成 "t&lt;启动毫秒&gt;-&lt;uuid&gt;"，随快照一起写入。
 * 同一 session 的旧文件才合并，不同 session 直接替换。session 只做身份比较
 * （不同即新会话），不比较启动时间：时钟回拨后新会话的时间戳反而更早，接收端
 * 按毫秒排序会整批拒绝并冻结诊断页。主进程会把 session/是否主进程一并回传，
 * 供服务端在新会话开始时清理旧数据。
 */
public final class Report {

    private static final String TAG = "QuietMap";
    private static final int MAX_VALUE = 256;

    /** socket/Provider 内容未变化时的强制重发周期（兜底主进程清理后的子进程数据）。 */
    static final long TRANSPORT_HEARTBEAT_MS = 60_000L;
    /** 三条通道全部失败后的退避。 */
    static final long TRANSPORT_RETRY_MS = 60_000L;

    /** 快照未变化时的文件心跳周期：至少每 5 分钟重写一次。 */
    static final long FILE_HEARTBEAT_MS = 5 * 60_000L;
    /** 快照变化后的常规文件写入最小间隔，避免命中计数每 3 秒刷盘。 */
    static final long FILE_MIN_INTERVAL_MS = 45_000L;

    /**
     * 本主进程运行的唯一标识；跨进程写同一文件时用于判断是否合并。
     * 格式 "t&lt;启动毫秒&gt;-&lt;uuid&gt;"（本模块唯一生成点）：接收端只做身份
     * 比较，毫秒段仅便于人工排查，不参与“迟到会话”排序；uuid 后缀保证时钟
     * 回拨/回绕后同毫秒启动的两个进程也是不同会话。旧格式（纯数字/纯 UUID）
     * 按“不同即新会话”兼容处理。
     */
    static final String SESSION = "t" + System.currentTimeMillis() + "-"
            + UUID.randomUUID();

    private static volatile boolean sStarted;
    private static volatile Context sContext;
    private static volatile Map<String, String> sLast = new TreeMap<>();
    private static volatile String sLastSession;
    private static volatile long sLastSentAt;
    private static volatile Map<String, String> sLastFile = new TreeMap<>();
    private static volatile long sNextTry;
    private static volatile long sNextFileTry;
    private static volatile long sLastFileWriteMs;
    private static volatile String sLastTransport;
    private static volatile long sLastTransportLog;
    private static volatile int sErrors;

    private Report() {
    }

    /** 仅在主进程调用；重复调用幂等。 */
    static synchronized void start() {
        if (sStarted) {
            return;
        }
        sStarted = true;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QuietMap-Report");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            try {
                flush();
            } catch (Throwable ignored) {
            }
        }, 2, 3, TimeUnit.SECONDS);
    }

    private static void flush() {
        flush(false, false);
    }

    /**
     * 崩溃路径的同步 flush（ALL-CRASH-01）：跳过去重/心跳与 60 秒失败退避，
     * 立刻把当前快照（含刚记录的 crash0）投递一次。
     *
     * <p>落盘语义：socket 等待服务端 ACK；Provider 通道以 sync=true 调用，
     * 模块 App 侧改用 {@code SharedPreferences.commit()} 同步落盘，commit 失败
     * 会在结果 Bundle 中返回 ok=false，本方法继续走文件通道 fsync 后原子 rename
     * 的兜底。常规 3 秒心跳仍用 apply()，不阻塞目标进程。任何通道异常都在
     * {@link #deliver} 内部吞掉；本方法再整体兜一层，保证崩溃处理链绝不会因
     * 诊断回传抛异常。
     *
     * <p>生产调用点只有 {@code QuietMapModule} 的 UEH：
     * {@code CrashFlush.runBestEffort(() -> Report.flushNow(), 200, logger, TAG)}。
     */
    public static void flushNow() {
        try {
            // force=true 跳过节流；sync=true 要求 Provider 用 commit() 同步落盘。
            flush(true, true);
        } catch (Throwable ignored) {
            // 崩溃路径 fail-safe：诊断回传失败不能影响原异常处理。
        }
    }

    private static void flush(boolean force, boolean sync) {
        Map<String, String> batch = snapshot();
        long now = System.currentTimeMillis();
        if (!force && !shouldSend(batch, sLast, SESSION, sLastSession, now, sLastSentAt)) {
            return;
        }
        if (!force && now < sNextTry) {
            return;
        }
        String json = ReportSocketProtocol.message(now, SESSION, true, batch);
        String used = sync
                // 崩溃路径：文件 fsync+rename 是最可靠的落盘方式，优先执行；
                // socket 的 ACK 读超时 1500ms 远大于 CrashFlush 的 200ms 预算。
                ? deliver(
                        () -> sendFile(batch, now, true),
                        () -> sendProvider(batch, true),
                        () -> ReportSocketSink.write(json))
                : deliver(
                        () -> ReportSocketSink.write(json),
                        () -> sendProvider(batch, false),
                        () -> sendFile(batch, now, force));
        if ("socket".equals(used) || "provider".equals(used)) {
            sLast = batch;
            sLastSession = SESSION;
            sLastSentAt = now;
            sNextTry = 0L;
            logTransport(used, batch.size(), now);
            return;
        }
        // socket + Provider 都失败：文件仅是调试兜底，不更新 sLast；60 秒后重试网络通道。
        sNextTry = now + TRANSPORT_RETRY_MS;
        logTransport(used != null ? used : "none", batch.size(), now);
    }

    /**
     * 是否值得发起一轮上报。内容变化、session 变化（新进程/热重载）、或距上次
     * 成功超过 {@link #TRANSPORT_HEARTBEAT_MS} 都返回 true——最后一条用于把
     * 主进程新 session 清理掉的子进程数据补回去。
     */
    static boolean shouldSend(Map<String, String> batch, Map<String, String> last,
                              String session, String lastSession,
                              long now, long lastSentAt) {
        if (batch == null || batch.isEmpty()) {
            return false;
        }
        if (!batch.equals(last)) {
            return true;
        }
        if (session == null || !session.equals(lastSession)) {
            return true;
        }
        if (lastSentAt <= 0L || now < lastSentAt) {
            return true;
        }
        return now - lastSentAt >= TRANSPORT_HEARTBEAT_MS;
    }

    /** 单通道发送动作；JVM 单测用它验证 socket → Provider → 文件的短路顺序。 */
    interface Channel {
        boolean send() throws Throwable;
    }

    /**
     * 固定通道顺序：socket 成功立即返回，不再调用 Provider/文件；socket 失败才
     * 尝试 Provider；Provider 失败才写文件。单个通道抛异常按失败处理并继续。
     *
     * @return 实际成功的通道名，全部失败返回 null。
     */
    static String deliver(Channel socket, Channel provider, Channel file) {
        if (trySend(socket)) {
            return "socket";
        }
        if (trySend(provider)) {
            return "provider";
        }
        if (trySend(file)) {
            return "file";
        }
        return null;
    }

    private static boolean trySend(Channel channel) {
        if (channel == null) {
            return false;
        }
        try {
            return channel.send();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Provider 通道；只有 call() 返回且结果 Bundle 的 ok=true 才视为成功。
     *
     * <p>sync=true（崩溃路径）要求模块 App 用 commit() 同步落盘。commit 失败时
     * call() 返回 ok=false，这里必须返回 false，让 {@link #deliver} 继续尝试文件
     * fsync+rename 兜底；不能因为拿到了非 null 的 Bundle 就当成已落盘。
     */
    private static boolean sendProvider(Map<String, String> batch, boolean sync) {
        Context context = context();
        if (context == null) {
            return false;
        }
        List<String> keys = new ArrayList<>(batch.keySet());
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            values.add(batch.get(key));
        }
        Bundle extras = new Bundle();
        extras.putStringArray("keys", keys.toArray(new String[0]));
        extras.putStringArray("values", values.toArray(new String[0]));
        extras.putString("session", SESSION);
        extras.putBoolean("main", true);
        extras.putBoolean(StatusProvider.EXTRA_SYNC, sync);
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + StatusProvider.AUTHORITY), "report", null, extras);
            if (result != null && result.getBoolean(StatusProvider.RESULT_OK, false)) {
                return true;
            }
            // result 为 null（Provider 不可达）或 ok=false（sync commit 失败/批次
            // 被拒）都按失败处理：不更新 sLast，继续交给文件兜底与 60 秒退避。
            if (sErrors++ < 3) {
                Log.w(TAG, "qmreport transport=provider failed: "
                        + (result == null ? "call returned null" : "provider reported not ok"));
            }
        } catch (Throwable t) {
            // 模块 App 未启动、被系统拦截等情况：不刷日志，交给外层退避。
            if (sErrors++ < 3) {
                Log.w(TAG, "qmreport transport=provider failed: " + describe(t));
            }
        }
        return false;
    }

    /** 文件调试兜底：仅 socket/Provider 都失败后调用，保留原节流规则。 */
    private static boolean sendFile(Map<String, String> batch, long now, boolean force) {
        boolean changed = !batch.equals(sLastFile);
        // 崩溃路径（force）必须落盘：进程即将退出，45 秒变化节流/60 秒退避都不能挡。
        if (!force && !fileWriteDue(changed, now, sLastFileWriteMs)) {
            return false;
        }
        if (!force && now < sNextFileTry) {
            return false;
        }
        Context context = context();
        if (context == null) {
            return false;
        }
        try {
            if (ReportFileSink.write(context, batch, SESSION)) {
                sLastFile = batch;
                sLastFileWriteMs = now;
                return true;
            }
            sNextFileTry = now + TRANSPORT_RETRY_MS;
        } catch (Throwable t) {
            sNextFileTry = now + TRANSPORT_RETRY_MS;
            Log.w(TAG, "qmreport transport=file failed: " + describe(t));
        }
        return false;
    }

    /** 成功通道变化或每 60 秒记一条，真机可直接 grep transport=。 */
    private static void logTransport(String transport, int keys, long now) {
        if (transport.equals(sLastTransport)
                && now >= sLastTransportLog
                && now - sLastTransportLog < 60_000L) {
            return;
        }
        sLastTransport = transport;
        sLastTransportLog = now;
        Log.i(TAG, "qmreport transport=" + transport + " keys=" + keys);
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    /**
     * 文件写入时机：首次立即写；快照变化后至少间隔 45 秒；未变化时每 5 分钟
     * 心跳重写。时钟回拨（now < last）时允许一次，避免永久停写。
     */
    static boolean fileWriteDue(boolean changed, long now, long lastWriteMs) {
        if (lastWriteMs <= 0L) {
            return true;
        }
        long age = now - lastWriteMs;
        if (age < 0L) {
            return true;
        }
        return age >= (changed ? FILE_MIN_INTERVAL_MS : FILE_HEARTBEAT_MS);
    }

    /**
     * HookState 快照 → 平铺的 key/value（key 用 reg./hit./entry./skip./crit.
     * 前缀，value 截断到 256）。
     *
     * reg/skip/crit 对每个 touched Feature 都必须发送，缺失时发空串：StatusProvider
     * 用最新值覆盖旧值，只有空串才能清掉上一次目标进程会话遗留的 skip/crit。
     * hit/entry 仍只在 >0 时发送，由 StatusProvider 取历史最大值，不会被空值覆盖。
     * crash0/1/2（0 最新，来自 QuietMapModule 的进程内崩溃环）始终发送、无崩溃发
     * 空串：与 reg/skip 同一清残留机制，单条已在记录时截断到 240（≤ MAX_VALUE）。
     */
    static Map<String, String> snapshot() {
        Map<String, String> out = new TreeMap<>();
        for (Feature f : HookState.touched()) {
            out.put(StatusProvider.PREFIX_REG + f.key,
                    truncate(join(HookState.labels(f))));
            long hits = HookState.hitCount(f);
            if (hits > 0) {
                out.put(StatusProvider.PREFIX_HIT + f.key, String.valueOf(hits));
            }
            long entries = HookState.entryCount(f);
            if (entries > 0) {
                out.put(StatusProvider.PREFIX_ENTRY + f.key, String.valueOf(entries));
            }
            out.put(StatusProvider.PREFIX_SKIP + f.key,
                    truncate(HookState.skipReason(f)));
            out.put(StatusProvider.PREFIX_CRIT + f.key,
                    HookState.hasCriticalSkip(f) ? "1" : "");
        }
        out.put(ReportProtocol.KEY_CRASH_0, QuietMapModule.crashAt(0));
        out.put(ReportProtocol.KEY_CRASH_1, QuietMapModule.crashAt(1));
        out.put(ReportProtocol.KEY_CRASH_2, QuietMapModule.crashAt(2));
        return out;
    }

    private static String join(List<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (String s : labels) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE);
    }

    private static Context context() {
        Context c = sContext;
        if (c != null) {
            return c;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            // anchor-allowlist: Android 框架引导反射（非宿主挂载点），用于兜底取 Application Context
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sContext = (Context) app;
                return sContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
