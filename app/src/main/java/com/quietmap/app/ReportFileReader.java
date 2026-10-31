package com.quietmap.app;

import android.content.Context;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 模块 App 侧的诊断数据读取入口。
 *
 * 主通道是 socket：目标进程把快照发到模块 App 的 ReportSocketServer，统一写入
 * {@link StatusProvider} 的 SharedPreferences（qm_report）。这里先读 prefs
 * （来源可能是 socket 或 Provider），再把文件作为**调试/旧系统兜底**按 key
 * 双向合并（语义见 {@link ReportState#pick}）：两源的 key 取并集，文件独有的
 * key 无论文件新旧都保留；两源都有的 key 由较新一侧决定——hit/entry 取最大，
 * reg/skip 在较新值为空时回退较旧的非空值，crit 严格取最新值（空串用于清除
 * 旧的 overall-failure 标记）。
 *
 * Android 11+ 模块 App 通常读不到目标进程写的 Android/media 文件；读不到时只记
 * 日志，绝不影响 socket/Provider 数据。
 *
 * 会话过滤：文件快照带 session。若 prefs 里已有主进程当前 session，而文件
 * session 与它不一致（双方都有值），该文件来自旧会话，直接忽略；旧版本文件
 * 没有 session 时按兼容处理，仍可参与合并。
 *
 * 读侧只做尽力而为：任何路径/权限/解析失败都只打日志，不阻塞诊断页。
 */
public final class ReportFileReader {

    private static final String TAG = "QuietMap";

    private ReportFileReader() {
    }

    /** prefs（socket/Provider）为主；文件有效且 session 一致时按 key 双向合并（并集）。 */
    public static ReportState read(Context context) {
        if (context == null) {
            return ReportState.none();
        }
        ReportState provider = StatusProvider.providerState(context);
        String currentSession = provider.session();
        long now = System.currentTimeMillis();
        ReportFileStore.Entry best = null;
        List<File> candidates = candidates(context);
        for (File file : candidates) {
            ReportFileStore.Probe probe;
            try {
                probe = ReportFileStore.probe(file, now, ReportFileStore.MAX_AGE_MS);
            } catch (Throwable t) {
                Log.w(TAG, "qmreport file sink read error path=" + file, t);
                continue;
            }
            if (probe.entry != null) {
                if (!ReportState.sessionCompatible(currentSession,
                        probe.entry.snapshot.session())) {
                    // 旧会话文件即使 mtime 很新也不能合并：它的 reg/skip 属于上一轮运行。
                    Log.i(TAG, "qmreport file sink read skip path=" + file
                            + " reason=session mismatch file="
                            + shortSession(probe.entry.snapshot.session())
                            + " current=" + shortSession(currentSession));
                    continue;
                }
                Log.i(TAG, "qmreport file sink read ok path=" + file
                        + " age=" + (now - probe.entry.mtime) + "ms"
                        + " canRead=" + file.canRead());
                if (best == null || probe.entry.effectiveTime() > best.effectiveTime()) {
                    best = probe.entry;
                }
            } else if (file.exists()) {
                // 存在但不可用（权限/过期/损坏）：真机排查时这条最关键。
                Log.w(TAG, "qmreport file sink read skip path=" + file
                        + " reason=" + probe.reason
                        + " canRead=" + file.canRead());
            } else {
                Log.d(TAG, "qmreport file sink read skip path=" + file
                        + " reason=" + probe.reason);
            }
        }
        ReportState picked = ReportState.pick(best, provider);
        if (!picked.hasData()) {
            // 没有新鲜数据也没有 prefs：展示最后一次有效文件数据 + 时间，
            // 而不是直接空白；页面会用 FILE_STALE 标注“已过期”。
            // 旧会话文件同样不参与兜底，避免展示上一轮运行的残留数据。
            ReportFileStore.Entry lastKnown = lastKnown(candidates, currentSession);
            if (lastKnown != null) {
                Log.i(TAG, "qmreport file sink stale fallback path=" + lastKnown.file
                        + " age=" + (now - lastKnown.effectiveTime()) + "ms");
                return ReportState.fromStaleFile(lastKnown);
            }
            Log.i(TAG, "qmreport file sink no data source=none");
        }
        return picked;
    }

    /** 不复用新鲜度检查：取最近一份结构完整、且属于当前会话的旧快照（可能已超过 TTL）。 */
    private static ReportFileStore.Entry lastKnown(List<File> candidates,
                                                   String currentSession) {
        ReportFileStore.Entry best = null;
        for (File file : candidates) {
            ReportFileStore.Entry entry;
            try {
                entry = ReportFileStore.readLastKnown(file);
            } catch (Throwable t) {
                continue;
            }
            if (entry == null
                    || !ReportState.sessionCompatible(currentSession, entry.snapshot.session())) {
                continue;
            }
            if (best == null || entry.effectiveTime() > best.effectiveTime()) {
                best = entry;
            }
        }
        return best;
    }

    private static String shortSession(String session) {
        if (session == null || session.isEmpty()) {
            return "-";
        }
        return session.length() <= 8 ? session : session.substring(0, 8);
    }

    /** 与写入侧一致的候选路径；模块读侧读不到目标 App 的 Android/data，仅尽力尝试。 */
    public static List<File> candidates(Context context) {
        File root = null;
        try {
            root = Environment.getExternalStorageDirectory();
        } catch (Throwable ignored) {
            // 外部存储未挂载：只剩空列表，调用方回退 Provider。
        }
        return ReportPaths.candidates(root, null,
                context.getPackageName(), ReportPaths.TARGET_PACKAGE);
    }

    /**
     * 模块 App 启动时预创建自己的 Android/media/&lt;模块包名&gt;/qm_report.json 占位文件。
     *
     * path a 只由模块 App 自己维护：不再 chmod、不要求目标进程写入，也不把
     * 文件设成 world-readable/writable。模块能否读到目标进程写的 path b 由
     * 日志里的 canRead/canWrite 决定；读不到时诊断页直接使用 socket/Provider
     * 写入的 prefs，文件通道仅用于调试/旧系统。
     * 本方法所有失败都只记日志。
     */
    public static void ensureSharedFile(Context context) {
        if (context == null) {
            return;
        }
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root == null) {
                Log.w(TAG, "qmreport file sink ensure: no external storage");
                return;
            }
            File dir = new File(new File(root, "Android/media"), context.getPackageName());
            boolean created = dir.isDirectory() || dir.mkdirs();

            File file = new File(dir, ReportPaths.FILE_NAME);
            boolean fileCreated = false;
            if (!file.exists()) {
                File tmp = new File(dir, ReportPaths.FILE_NAME + "."
                        + Process.myPid() + ReportPaths.TEMP_SUFFIX);
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(tmp, false);
                    out.write(ReportSnapshot.emptyJson().getBytes(StandardCharsets.UTF_8));
                    try {
                        out.getFD().sync();
                    } catch (Throwable ignored) {
                        // sync 失败不影响占位文件本身。
                    }
                } finally {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (tmp.renameTo(file)) {
                    fileCreated = true;
                } else {
                    tmp.delete();
                }
            }
            File targetDir = new File(new File(root, "Android/media"),
                    ReportPaths.TARGET_PACKAGE);
            File targetFile = new File(targetDir, ReportPaths.FILE_NAME);
            File targetDataFile = new File(new File(new File(new File(root, "Android/data"),
                    ReportPaths.TARGET_PACKAGE), "files"), ReportPaths.FILE_NAME);
            Log.i(TAG, "qmreport file sink ensure"
                    + " moduleDir=" + dir + " created=" + created
                    + " canRead=" + dir.canRead() + " canWrite=" + dir.canWrite()
                    + " moduleFile=" + file + " created=" + fileCreated
                    + " exists=" + file.exists()
                    + " canRead=" + file.canRead() + " canWrite=" + file.canWrite()
                    + " mediaFile(b)=" + targetFile + " exists=" + targetFile.exists()
                    + " canRead=" + targetFile.canRead()
                    + " canWrite=" + targetFile.canWrite()
                    + " dataFile(c)=" + targetDataFile + " exists=" + targetDataFile.exists()
                    + " canRead=" + targetDataFile.canRead()
                    + " canWrite=" + targetDataFile.canWrite());
        } catch (Throwable t) {
            Log.w(TAG, "qmreport file sink ensure failed", t);
        }
    }
}
