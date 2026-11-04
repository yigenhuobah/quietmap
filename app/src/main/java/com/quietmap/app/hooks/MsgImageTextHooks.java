package com.quietmap.app.hooks;

import com.quietmap.app.Feature;
import com.quietmap.app.anchor.AnchorKeys;
import com.quietmap.app.anchor.AnchorTable;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * msg_image_text 营销图文消息素材下载断源。
 *
 * dexsql 复核与调研清单的出入（以 dexsql 为准）：
 * - 调研写的 ImageTextMessageDownloader 类实测只有静态方法 a(File)Z（递归删除缓存文件），
 *   不是下载入口。
 * - 真正的下载入口是混淆 Runnable（旧版 La8 -> 17.02 的 n8，方法名/签名与锚点串不变）：
 *   方法体含 "ImageTextMessageDownloader" / "start" / "invalid download item: " 等字符串，
 *   先清理超过 28 天的 image_text_message 缓存，再构造 DownloadRequest +
 *   ImageTextMessageDownloader$FinishCallback（通过 imagetextmessage/a 回调包装），
 *   最后调用 IHttpService.download(...)。不调用 run() 即不发起素材下载。
 *   （清理逻辑随之跳过，仅留下过期缓存，不影响功能。）
 *
 * 定位策略：anchors.json 的 candidates 优先（Class.forName 毫秒级命中，同步落钩）；
 * 命中后必须校验 void()V 签名 + Runnable 类型 + 非框架类，与 DexKit 兜底路径的
 * 强校验对齐——混淆短名会被版本升级重排复用（0.8.3 CHANGELOG 已记录 Lso2; 被
 * 无关类占用，全 APK 有数千个无参 run()V），校验不过就放弃该名字，绝不把无关
 * 后台任务整段跳过还显示 hit；DexKit 按方法体字符串锚定仅作兜底，且「查询 +
 * 落钩」整体挪到后台延迟执行（native 加载 + 全 dex 扫描实测秒级）。
 * 两路都失败记 skip，fail-open。
 */
final class MsgImageTextHooks {

    private MsgImageTextHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.MSG_IMAGE_TEXT;
        try {
            AnchorTable anchors = HookKit.loadAnchors(module, f, cl);
            if (anchors == null) {
                return;
            }
            installDownloadRun(module, f, cl, anchors);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
        }
    }

    /**
     * 下载任务定位与落钩：anchors.json 的 candidates 类名快路径优先（毫秒级，
     * 命中需过 {@link #isDownloaderRun} 校验，同步落钩）；miss/校验不过后的
     * DexKit 锚点扫描转 {@link HookKit#installByAnchorLate} 后台执行，本方法在
     * 启动关键路径上不再等待扫描结果。
     */
    private static void installDownloadRun(XposedModule module, Feature f, ClassLoader cl,
                                           AnchorTable anchors) {
        Method m = anchors.methodForCandidates(AnchorKeys.MSG_IMAGE_TEXT_DOWNLOAD_RUN);
        if (m != null && isDownloaderRun(m)) {
            HookKit.info(module, f.id
                    + " image-text downloader located via anchor candidates: "
                    + m.getDeclaringClass().getName());
            HookKit.install(module, f, AnchorKeys.MSG_IMAGE_TEXT_DOWNLOAD_RUN, m,
                    HookKit.skipVoid(module, f, AnchorKeys.MSG_IMAGE_TEXT_DOWNLOAD_RUN));
            return;
        }
        if (m != null) {
            // 短名被复用成别的类：不整段跳过无关任务，降级到锚点串定位。
            HookKit.warn(module, f.id + " anchor candidate "
                    + m.getDeclaringClass().getName()
                    + "#run failed validation (void()/Runnable/non-framework), "
                    + "dexkit anchor deferred to background");
        } else {
            HookKit.warn(module, f.id
                    + " anchor candidates miss, dexkit anchor deferred to background");
        }

        // 兜底：DexKit 按方法体特征串定位（类名改了也能找到），整体放后台。
        HookKit.installByAnchorLate(module, f, AnchorKeys.MSG_IMAGE_TEXT_DOWNLOAD_RUN, cl,
                null,
                anchors.methodName(AnchorKeys.MSG_IMAGE_TEXT_DOWNLOAD_RUN),
                void.class, new Class<?>[0],
                anchors.dexkit(AnchorKeys.MSG_IMAGE_TEXT_DOWNLOAD_RUN));
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
