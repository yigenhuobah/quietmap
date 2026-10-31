package com.quietmap.app.dexkit;

/**
 * DexKit 扫描锚点定义（混淆类抗改版定位用）。
 * 严格禁止引用任何 androidx 类型。
 *
 * 锚点只取自 dexsql 对目标 APK 的实测（方法体 method_strings 反查），
 * 不来自任何第三方模块的代码或文档。
 */
public final class Anchors {

    private Anchors() {
    }

    /**
     * 开屏广告请求调度的特征字符串：Lah5;->attemptRequestData(IZ)V 方法体内
     * 含 "不发送请求：…" 系列日志串（dexsql method_strings 实测，17.02.0.2010
     * 复测仍在；旧版本该类名为 Lze5;）。
     * 注意 Lzg5;->run()V 也含 "不发送请求：拿不到锁" 一个同首串（旧版 Lye5;），
     * 所以锚定命中后必须再用方法名/参数消歧（见 DexKitLocator 的两次尝试）。
     */
    public static final String STR_SPLASH_REQ_NO_SEND = "不发送请求";

    /**
     * 营销图文消息素材下载任务 Ln8;->run()V 的特征字符串（dexsql method_strings 实测，
     * 17.02.0.2010 复测仍在；旧版本该类名为 La8;）：
     * "invalid download item: " 是该 Runnable 独有的失败日志，配合类名串
     * "ImageTextMessageDownloader" 与 void()V 签名可唯一消歧。
     */
    public static final String STR_IMAGE_TEXT_DL = "invalid download item: ";
    public static final String STR_IMAGE_TEXT_DL_CLASS = "ImageTextMessageDownloader";
}
