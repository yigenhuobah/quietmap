package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.dexkit.Anchors;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * msg_image_text 营销图文消息素材下载断源。
 *
 * dexsql 复核与调研清单的出入（以 dexsql 为准）：
 * - 调研写的 ImageTextMessageDownloader 类实测只有静态方法 a(File)Z（递归删除缓存文件），
 *   不是下载入口。
 * - 真正的下载入口是混淆 Runnable Ln8;->run()V（17.01.0.2012 为 La8;，17.02.0.2010
 *   改名，方法体与锚点串不变）：方法体含
 *   "ImageTextMessageDownloader" / "start" / "invalid download item: " 等字符串，
 *   先清理超过 28 天的 image_text_message 缓存，再构造 DownloadRequest +
 *   ImageTextMessageDownloader$FinishCallback（通过 imagetextmessage/a 回调包装），
 *   最后调用 IHttpService.download(...)。不调用 run() 即不发起素材下载。
 *   （清理逻辑随之跳过，仅留下过期缓存，不影响功能。）
 *
 * Ln8 是短名混淆类，定位策略与批 2 的 Lah5（旧 Lze5）一致：
 *   类名列表 ["n8"] 优先（描述符 Ln8; → Java 名 n8，Class.forName 毫秒级命中，
 *   同步落钩）；命中后必须校验 void()V 签名 + Runnable 类型 + 非框架类，与
 *   DexKit 兜底路径的强校验对齐——混淆短名会被版本升级重排复用（0.8.3
 *   CHANGELOG 已记录 Lso2; 被无关类占用，全 APK 有数千个无参 run()V），校验
 *   不过就放弃该名字，绝不把无关后台任务整段跳过还显示 hit；
 *   DexKit 按方法体字符串锚定（"invalid download item: " +
 *   "ImageTextMessageDownloader"，void()V 签名强校验）仅作兜底，且「查询 +
 *   落钩」整体挪到后台延迟执行（native 加载 + 全 dex 扫描实测秒级，同步跑会
 *   把 onPackageReady 启动关键路径阻塞数秒，见 finding #88）。
 * 两路都失败记 skip，fail-open。
 */
final class MsgImageTextHooks {

    /** 兜底类名：高德升级后先用 check-mounts.sh 体检 + dexsql 字符串反查，再追加新名。 */
    private static final String[] DOWNLOAD_CLASS_NAMES = {"n8"};
    private static final String DOWNLOAD_METHOD = "run";
    /** 挂载标签；类名列表与 DexKit 兜底两条路共用，诊断页 reg 才能对上。 */
    private static final String DOWNLOAD_LABEL = "Ln8#run";

    private MsgImageTextHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.MSG_IMAGE_TEXT;
        try {
            installDownloadRun(module, f, cl);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * 下载任务定位与落钩：类名列表优先（毫秒级，命中需过 {@link #isDownloaderRun}
     * 校验，同步落钩）；miss/校验不过后的 DexKit 锚点扫描转
     * {@link HookKit#installByAnchorLate} 后台执行，本方法在启动关键路径上不再
     * 等待扫描结果。
     */
    private static void installDownloadRun(XposedModule module, Feature f, ClassLoader cl) {
        // 优先：类名列表（check-mounts 体检出新名后追加），避免冷启动白付 DexKit 全量扫描。
        for (String name : DOWNLOAD_CLASS_NAMES) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                Method m = HookKit.findMethodQuiet(cls, DOWNLOAD_METHOD);
                if (m != null && isDownloaderRun(m)) {
                    HookKit.info(module, f.id
                            + " image-text downloader located via class-name list: " + name);
                    HookKit.install(module, f, DOWNLOAD_LABEL, m,
                            HookKit.skipVoid(module, f, DOWNLOAD_LABEL));
                    return;
                }
                if (m != null) {
                    // 短名被复用成别的类：不整段跳过无关任务，降级到锚点串定位。
                    HookKit.warn(module, f.id + " class-name hit " + name
                            + "#run failed validation (void()/Runnable/non-framework), "
                            + "dexkit anchor deferred to background");
                }
            } catch (Throwable ignored) {
            }
        }
        HookKit.warn(module, f.id + " class-name list miss, dexkit anchor deferred to background");

        // 兜底：DexKit 按方法体特征串定位（类名改了也能找到），整体放后台。
        HookKit.installByAnchorLate(module, f, DOWNLOAD_LABEL, cl, null, DOWNLOAD_METHOD,
                void.class, new Class<?>[0],
                new String[]{Anchors.STR_IMAGE_TEXT_DL, Anchors.STR_IMAGE_TEXT_DL_CLASS});
    }

    /**
     * 快路径命中校验（与 DexKit 兜底的 void()V 强校验对齐）：
     * run() 必须是 void 无参（跳过会整段不执行，签名必须与下载任务一致）、
     * 声明类是 Runnable（下载任务是提交给执行器的 Runnable）、且不是框架类
     * （HookKit 黑名单兜一道）。混淆短名被复用成无关类时在此拒收，
     * 宁可降级到 DexKit 锚点串定位，也不把无关 run() 挂成 hit。
     */
    static boolean isDownloaderRun(Method m) {
        if (m == null || m.getReturnType() != void.class || m.getParameterTypes().length != 0) {
            return false;
        }
        Class<?> cls = m.getDeclaringClass();
        if (HookKit.isFrameworkClass(cls.getName()) || !Runnable.class.isAssignableFrom(cls)) {
            return false;
        }
        return true;
    }
}
