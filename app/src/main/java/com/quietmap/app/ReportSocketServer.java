package com.quietmap.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模块 App 侧的诊断回传服务端：抽象命名空间 Unix domain socket。
 *
 * 目标进程（高德）通过 hooks/ReportSocketSink 主动连接；本服务端只负责收帧、
 * 校验、解析快照，并复用 {@link StatusProvider} 的 SharedPreferences 合并逻辑
 * （hit 取最大、reg/skip 覆盖、主进程新 session 清理旧数据）。不依赖存储权限，
 * 也不受 Android 11+ 分区存储/包可见性影响。
 *
 * 启动点：{@link QuietMapApp#onCreate()}；同时 {@link StatusProvider#onCreate()}
 * 会再调用一次 {@link #start(Context)} 作为兜底（AtomicBoolean 保证只启动一次）。
 * 端口被占用/被 SELinux 拒绝时按 1s/5s/30s 退避重建，绝不崩溃、不阻塞主线程。
 */
public final class ReportSocketServer {

    private static final String TAG = "QuietMap";

    /** 绑定失败后的退避间隔；持续失败时停在 30 秒，避免刷日志。 */
    private static final long[] BACKOFF_MS = {1_000L, 5_000L, 30_000L};

    /** 单个客户端读超时；对端写完即关，2 秒足够。 */
    private static final int READ_TIMEOUT_MS = 2_000;

    private static final long ACCEPT_LOG_INTERVAL_MS = 60_000L;
    private static final int ACCEPT_LOG_FIRST = 5;

    /**
     * peer 校验结果缓存：抽象命名空间 socket 对本机所有应用可见，恶意应用可以
     * 循环 connect；不缓存的话每次校验都要做一次 getPackagesForUid binder 查询
     * （还会唤醒被冻结的模块进程）。UID → 包列表只在安装/卸载时变化，60 秒 TTL
     * 足够新鲜；容量上限防多个 uid 撑爆内存。
     */
    private static final long PEER_CACHE_TTL_MS = 60_000L;
    private static final int PEER_CACHE_MAX = 64;
    private static final ConcurrentHashMap<Integer, PeerDecision> sPeerCache =
            new ConcurrentHashMap<>();

    /** 帧处理完成后的 1 字节确认值；发送端只认它，其余（EOF/超时）按失败降级。 */
    public static final byte ACK_BYTE = '1';

    private static final AtomicBoolean sStarted = new AtomicBoolean();

    /** Application context；只存 app context，不持有 Activity。 */
    @SuppressLint("StaticFieldLeak")
    private static volatile Context sContext;
    private static volatile long sLastAcceptLog;
    private static int sAcceptLogs;
    private static volatile long sLastAcceptErrorLog;
    private static int sAcceptErrors;
    private static volatile long sLastRejectLog;
    private static int sRejectLogs;

    private ReportSocketServer() {
    }

    /** 幂等；可在任意进程调用，但只有模块 App 进程会真正绑定成功。 */
    public static void start(Context context) {
        if (context == null) {
            return;
        }
        Context app;
        try {
            app = context.getApplicationContext();
        } catch (Throwable ignored) {
            app = null;
        }
        sContext = app != null ? app : context;
        if (!sStarted.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(ReportSocketServer::serve, "QuietMap-ReportSocket");
        thread.setDaemon(true);
        thread.start();
    }

    private static void serve() {
        int attempt = 0;
        while (!Thread.currentThread().isInterrupted()) {
            LocalServerSocket server = null;
            try {
                // LocalServerSocket(String) 内部用 LocalSocketAddress 的默认
                // ABSTRACT 命名空间绑定；API 没有接收 LocalSocketAddress 的构造器。
                server = new LocalServerSocket(ReportSocketProtocol.SOCKET_NAME);
                attempt = 0;
                logInfo("qmreport socket server started name="
                        + ReportSocketProtocol.SOCKET_NAME + " ns=abstract");
                acceptLoop(server);
            } catch (Throwable t) {
                logWarn("qmreport socket server failed: " + describe(t));
            } finally {
                closeQuietly(server);
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            sleep(backoff(attempt++));
        }
    }

    private static void acceptLoop(LocalServerSocket server) throws IOException {
        int failures = 0;
        while (!Thread.currentThread().isInterrupted()) {
            LocalSocket socket = null;
            try {
                socket = server.accept();
                failures = 0;
                handle(socket);
            } catch (Throwable t) {
                logAcceptFailure(t);
                if (++failures >= 3) {
                    throw new IOException("accept failed repeatedly", t);
                }
                sleep(1_000L);
            } finally {
                closeQuietly(socket);
            }
        }
    }

    private static void handle(LocalSocket socket) {
        Context context = sContext;
        if (context == null) {
            logWarn("qmreport socket reject reason=no context");
            return;
        }
        if (!isAllowedPeer(socket, context)) {
            logPeerReject();
            return;
        }
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
        } catch (Throwable t) {
            // 设不了读超时就直接断开：单线程 accept 循环不能赌“对端写完会关”，
            // 否则一次挂起的 read 会停掉整个回传服务端，之后所有进程都进不来。
            logWarn("qmreport socket reject reason=so timeout failed: " + describe(t));
            return;
        }
        try {
            InputStream in = socket.getInputStream();
            byte[] header = new byte[ReportSocketProtocol.HEADER_BYTES];
            if (!readFully(in, header, header.length)) {
                return;
            }
            int length = ReportSocketProtocol.frameLength(header);
            if (length < 0) {
                logWarn("qmreport socket reject reason=bad length");
                return;
            }
            byte[] payload = new byte[length];
            if (!readFully(in, payload, length)) {
                logWarn("qmreport socket reject reason=truncated length=" + length);
                return;
            }
            ReportSnapshot snapshot = ReportSocketProtocol.parseMessage(payload);
            if (snapshot == null) {
                logWarn("qmreport socket reject reason=invalid json length=" + length);
                return;
            }
            boolean mainProcess = Boolean.TRUE.equals(snapshot.mainProcess());
            boolean applied = StatusProvider.applyReport(context, snapshot.data(),
                    snapshot.session(), mainProcess, ReportProtocol.SOURCE_SOCKET);
            logAccept(snapshot, mainProcess, applied);
            writeAck(socket);
        } catch (Throwable t) {
            logWarn("qmreport socket read failed: " + describe(t));
        }
    }

    /**
     * 只接受高德目标进程或模块自身的连接。抽象命名空间 socket 对本机所有应用可见，
     * 不校验 UID 时任意应用都能伪造/清空诊断数据。
     *
     * <p>非本进程 uid 的校验结果进 {@link #sPeerCache}（TTL 内直接复用）：
     * 循环 connect 的 binder 查询开销从「每次一条」降为「每个 uid 每 TTL 一条」。
     * binder 查询抛异常时不缓存——瞬态失败不能把错误的答案钉住一个 TTL。
     */
    static boolean isAllowedPeer(LocalSocket socket, Context context) {
        try {
            Credentials credentials = socket.getPeerCredentials();
            if (credentials == null) {
                return false;
            }
            int uid = credentials.getUid();
            if (uid == Process.myUid()) {
                return true;
            }
            long now = System.currentTimeMillis();
            PeerDecision cached = sPeerCache.get(uid);
            if (isFresh(cached, now)) {
                return cached.allowed;
            }
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            boolean allowed = isAllowedPackage(packages, context.getPackageName());
            cachePeerDecision(uid, allowed, now);
            return allowed;
        } catch (Throwable t) {
            logWarn("qmreport socket peer credentials failed: " + describe(t));
            return false;
        }
    }

    /** uid 的一次性校验结果；包内可见供单测。 */
    static final class PeerDecision {
        final boolean allowed;
        final long atMs;

        PeerDecision(boolean allowed, long atMs) {
            this.allowed = allowed;
            this.atMs = atMs;
        }
    }

    /** 缓存是否仍新鲜；时钟回拨（now &lt; atMs）按过期处理，宁可多查一次 binder。包内可见供单测。 */
    static boolean isFresh(PeerDecision decision, long nowMs) {
        return decision != null
                && nowMs >= decision.atMs
                && nowMs - decision.atMs < PEER_CACHE_TTL_MS;
    }

    private static void cachePeerDecision(int uid, boolean allowed, long nowMs) {
        if (sPeerCache.size() >= PEER_CACHE_MAX) {
            // 简单防膨胀：极端情况下整表清空重查一轮，代价远小于不缓存。
            sPeerCache.clear();
        }
        sPeerCache.put(uid, new PeerDecision(allowed, nowMs));
    }

    static boolean isAllowedPackage(String[] packages, String modulePackage) {
        if (packages == null) {
            return false;
        }
        for (String pkg : packages) {
            if (ReportPaths.TARGET_PACKAGE.equals(pkg)
                    || (modulePackage != null && modulePackage.equals(pkg))) {
                return true;
            }
        }
        return false;
    }

    /** 首次 5 条 + 之后每 60 秒一条；三个目标进程轮流上报时不刷屏。 */
    private static void logAccept(ReportSnapshot snapshot, boolean mainProcess,
                                  boolean applied) {
        long now = System.currentTimeMillis();
        if (sAcceptLogs >= ACCEPT_LOG_FIRST
                && now >= sLastAcceptLog
                && now - sLastAcceptLog < ACCEPT_LOG_INTERVAL_MS) {
            return;
        }
        sAcceptLogs++;
        sLastAcceptLog = now;
        logInfo("qmreport socket accept keys=" + snapshot.data().size()
                + " session=" + shortSession(snapshot.session())
                + " main=" + mainProcess + " applied=" + applied);
    }

    /**
     * peer 校验失败的日志节流：与 logAccept 相同（前 5 条 + 之后每 60 秒一条）。
     * 抽象命名空间 socket 对本机所有应用可见，恶意应用可以循环 connect；不节流
     * 的话每次拒绝都写一条日志。binder 查询另由 peer 缓存按 TTL 收敛。
     */
    static boolean shouldLogPeerReject(int logged, long lastLogMs, long nowMs) {
        return logged < ACCEPT_LOG_FIRST
                || nowMs < lastLogMs
                || nowMs - lastLogMs >= ACCEPT_LOG_INTERVAL_MS;
    }

    private static void logPeerReject() {
        long now = System.currentTimeMillis();
        if (!shouldLogPeerReject(sRejectLogs, sLastRejectLog, now)) {
            return;
        }
        sRejectLogs++;
        sLastRejectLog = now;
        logWarn("qmreport socket reject reason=peer uid");
    }

    /**
     * 帧处理完成后的 1 字节确认：发送端 {@link com.quietmap.app.hooks.ReportSocketSink}
     * 收到 {@link #ACK_BYTE} 才算成功，否则降级 Provider/文件通道。旧版本发送端
     * 不读该字节（写失败在此吞掉，不影响已落盘的数据）；拒绝路径（peer/长度/JSON）
     * 不回写，发送端读到 EOF 按失败处理。
     */
    private static void writeAck(LocalSocket socket) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write(ACK_BYTE);
            out.flush();
        } catch (Throwable ignored) {
            // 发送端可能已经关闭（旧版本写完立即 close）。
        }
    }

    private static String shortSession(String session) {
        if (session == null || session.isEmpty()) {
            return "-";
        }
        return session.length() <= 8 ? session : session.substring(0, 8);
    }

    /** accept 连续失败时前 3 条 + 之后每 60 秒一条。 */
    private static void logAcceptFailure(Throwable t) {
        long now = System.currentTimeMillis();
        if (sAcceptErrors < 3 || now < sLastAcceptErrorLog
                || now - sLastAcceptErrorLog >= ACCEPT_LOG_INTERVAL_MS) {
            sAcceptErrors++;
            sLastAcceptErrorLog = now;
            logWarn("qmreport socket accept failed: " + describe(t));
        }
    }

    private static boolean readFully(InputStream in, byte[] buffer, int length)
            throws IOException {
        int offset = 0;
        while (offset < length) {
            int read = in.read(buffer, offset, length - offset);
            if (read < 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    private static long backoff(int attempt) {
        if (attempt < 0) {
            attempt = 0;
        }
        return BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(LocalServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(LocalSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "null";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }

    private static void logInfo(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    private static void logWarn(String message) {
        try {
            Log.w(TAG, message);
        } catch (Throwable ignored) {
        }
    }
}
