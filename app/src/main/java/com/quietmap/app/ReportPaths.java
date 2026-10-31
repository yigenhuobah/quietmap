package com.quietmap.app;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 诊断文件兜底的候选路径（纯 Java，可在 JVM 单测里锁定优先级）。
 *
 * 读取优先级：
 *   1. Android/media/&lt;模块包名&gt;/qm_report.json      —— 仅模块 App 自己维护，不加宽权限
 *   2. Android/media/&lt;高德包名&gt;/qm_report.json      —— 目标进程写入，模块读侧尽力尝试
 *   3. Android/data/&lt;高德包名&gt;/files/qm_report.json —— 目标进程 getExternalFilesDir(null)，
 *      模块 App 在 Android 11+ 通常读不到，仅作为写入侧最后兜底
 *
 * 写入优先级（目标进程）：b → c。path a 不再要求目标进程写入，也不做
 * world-readable/writable 权限放宽；模块读不到 path b 时回退 Provider/prefs。
 */
public final class ReportPaths {

    public static final String FILE_NAME = "qm_report.json";
    public static final String TEMP_SUFFIX = ".tmp";

    /**
     * 目标包名的全仓唯一声明：文件路径、socket peer 校验（StatusProvider/
     * ReportSocketServer）、模块入口包名过滤（QuietMapModule）与设置/关于页
     * 统一引用本常量，避免多处字面量「改一处漏一处」让某条通道静默失效。
     */
    public static final String TARGET_PACKAGE = "com.autonavi.minimap";

    private ReportPaths() {
    }

    /**
     * @param externalRoot  外部存储根（通常是 /storage/emulated/0），可为 null
     * @param targetFilesDir 目标进程的 getExternalFilesDir(null)，可为 null；
     *                       为 null 时按标准布局推导 Android/data/&lt;target&gt;/files
     */
    public static List<File> candidates(File externalRoot, File targetFilesDir,
                                        String modulePackage, String targetPackage) {
        List<File> out = new ArrayList<>(3);
        File media = externalRoot != null ? new File(externalRoot, "Android/media") : null;
        if (media != null && modulePackage != null && !modulePackage.isEmpty()) {
            out.add(new File(new File(media, modulePackage), FILE_NAME));
        }
        if (media != null && targetPackage != null && !targetPackage.isEmpty()) {
            out.add(new File(new File(media, targetPackage), FILE_NAME));
        }
        if (targetFilesDir != null) {
            out.add(new File(targetFilesDir, FILE_NAME));
        } else if (externalRoot != null && targetPackage != null && !targetPackage.isEmpty()) {
            out.add(new File(new File(new File(new File(externalRoot, "Android/data"),
                    targetPackage), "files"), FILE_NAME));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 目标进程可写的候选路径：只包含目标 App 自己的 media 目录（b）与
     * external files 目录（c）。模块的 media 目录（a）由模块 App 自己维护，
     * 不再让目标进程写入，避免为了跨 UID 写文件而放宽权限。
     */
    public static List<File> writeCandidates(File externalRoot, File targetFilesDir,
                                             String targetPackage) {
        List<File> out = new ArrayList<>(2);
        File media = externalRoot != null ? new File(externalRoot, "Android/media") : null;
        if (media != null && targetPackage != null && !targetPackage.isEmpty()) {
            out.add(new File(new File(media, targetPackage), FILE_NAME));
        }
        if (targetFilesDir != null) {
            out.add(new File(targetFilesDir, FILE_NAME));
        } else if (externalRoot != null && targetPackage != null && !targetPackage.isEmpty()) {
            out.add(new File(new File(new File(new File(externalRoot, "Android/data"),
                    targetPackage), "files"), FILE_NAME));
        }
        return Collections.unmodifiableList(out);
    }
}
