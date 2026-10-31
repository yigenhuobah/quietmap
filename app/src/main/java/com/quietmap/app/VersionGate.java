package com.quietmap.app;

/**
 * 目标应用版本门禁。
 *
 * 只声明“适配版本”这一件事：未知版本不阻断 hook（fail-open），
 * 由状态卡与异常横幅提示用户；hook 内部仍按 Feature 开关执行。
 */
public final class VersionGate {

    /** 高德地图 17.02.0.2010：当前全部挂载点的实测版本（63/63 OK，2026-10-31）。 */
    public static final String SUPPORTED_VERSION = "17.02.0.2010";

    private VersionGate() {
    }

    public static boolean isSupported(String versionName) {
        return SUPPORTED_VERSION.equals(versionName);
    }
}
