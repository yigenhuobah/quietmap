package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.HookState;
import com.quietmap.app.dexkit.Anchors;
import com.quietmap.app.dexkit.DexKitLocator;

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
 *   类名列表 ["n8"] 优先（描述符 Ln8; → Java 名 n8，Class.forName 毫秒级命中）；
 *   DexKit 按方法体字符串锚定（"invalid download item: " +
 *   "ImageTextMessageDownloader"，void()V 签名强校验）仅作兜底，避免冷启动
 *   白付 native 加载 + 全 dex 扫描。
 * 两路都失败记 skip，fail-open。
 */
final class MsgImageTextHooks {

    /** 兜底类名：高德升级后先用 check-mounts.sh 体检 + dexsql 字符串反查，再追加新名。 */
    private static final String[] DOWNLOAD_CLASS_NAMES = {"n8"};
    private static final String DOWNLOAD_METHOD = "run";

    private MsgImageTextHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.MSG_IMAGE_TEXT;
        try {
            Method run = resolveDownloadRun(module, f, cl);
            HookKit.install(module, f, "Ln8#run", run,
                    HookKit.skipVoid(module, f, "Ln8#run"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /** 混淆类定位：类名列表优先（毫秒级），DexKit 字符串锚定兜底，都失败返回 null。 */
    private static Method resolveDownloadRun(XposedModule module, Feature f, ClassLoader cl) {
        // 优先：类名列表（check-mounts 体检出新名后追加），避免冷启动白付 DexKit 全量扫描。
        for (String name : DOWNLOAD_CLASS_NAMES) {
            try {
                Class<?> cls = Class.forName(name, false, cl);
                Method m = HookKit.findMethodQuiet(cls, DOWNLOAD_METHOD);
                if (m != null) {
                    HookKit.info(module, f.id
                            + " image-text downloader located via class-name list: " + name);
                    return m;
                }
            } catch (Throwable ignored) {
            }
        }
        HookKit.warn(module, f.id + " class-name list miss, fallback to dexkit anchor");

        // 兜底：DexKit 按方法体特征串定位（类名改了也能找到）。
        try {
            Method m = DexKitLocator.findMethod(cl, null, DOWNLOAD_METHOD,
                    void.class, new Class<?>[0],
                    Anchors.STR_IMAGE_TEXT_DL, Anchors.STR_IMAGE_TEXT_DL_CLASS);
            if (m != null) {
                HookKit.info(module, f.id + " image-text downloader located via dexkit anchor: "
                        + m.getDeclaringClass().getName());
                return m;
            }
            HookKit.warn(module, f.id + " dexkit anchor miss");
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " dexkit anchor error: " + t.getMessage());
        }

        HookKit.warn(module, f.id + " skip: image-text downloader not found (class-name list "
                + java.util.Arrays.toString(DOWNLOAD_CLASS_NAMES) + " + dexkit anchor)");
        HookState.markNotFound(f, "Ln8#run 锚定失败");
        return null;
    }
}
