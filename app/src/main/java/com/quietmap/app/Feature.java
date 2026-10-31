package com.quietmap.app;

/**
 * 功能清单。
 *
 * id 是统一功能编号（AD/UI/PV/EN/EX-NN），用于日志前缀、诊断页与文档；
 * key 同时是模块 App 内 remote prefs 的键，也是目标进程
 * getRemotePreferences("config") 的键，两处必须一致。id 可随重编号调整，
 * key 发布后不可改（改了会让老用户开关全部失效）。
 *
 * 默认值遵循统一策略：AD 广告净化、UI 界面精简默认开；
 * PV 隐私保护默认关（可能影响登录、统计、推送、风控）。
 *
 * 枚举顺序 = hook 安装顺序：开屏三键放最前（启动路径最敏感，尽早落钩），
 * 批 3 首页三键随后（首页路径，不参与启动链路），批 5 的弹窗/统计/摇一摇
 * 三键紧随其后，批 6 的运营活动/通知引导/消息中心三键放最后（收尾净化，
 * 不参与启动链路）。设置页按 group 重新分组显示，因此这里的顺序不必等于
 * 页面显示顺序。
 */
public enum Feature {

    /** 开屏闸门（canShowSplash 返回 Boolean.FALSE + tryShowSplashView 不调用，双保险）。 */
    SPLASH_GATE("AD-01", "splash_gate", R.string.f_splash_gate_title,
            R.string.f_splash_gate_summary, true, Group.AD),
    /** 开屏广告请求断源（sendSplashScreen + Lah5#attemptRequestData 不调用，旧版 Lze5）。 */
    SPLASH_REQUEST("AD-02", "splash_request", R.string.f_splash_request_title,
            R.string.f_splash_request_summary, true, Group.AD),
    /** 开屏摇一摇禁用（SplashButtonInfo 摇一摇字段返回空/false）。 */
    SPLASH_SHAKE("AD-03", "splash_shake", R.string.f_splash_shake_title,
            R.string.f_splash_shake_summary, true, Group.AD),
    /**
     * 后台拉取营销消息 + 亮屏触发源（MessageBoxManager#getMessageInBackground +
     * BackgroundMsgScreenReceiver）；另挂 handlePush、BackgroundMsgManager#a、
     * fetchMessageFromMainMap 三个推送处理/主图拉取入口。
     */
    BG_MSG_FETCH("AD-04", "bg_msg_fetch", R.string.f_bg_msg_fetch_title,
            R.string.f_bg_msg_fetch_summary, true, Group.AD),
    /** 唤起端上报（AwakenRequestHolder#sendAwakenPage）。 */
    BG_AWAKEN_REPORT("PV-01", "bg_awaken_report", R.string.f_bg_awaken_report_title,
            R.string.f_bg_awaken_report_summary, false, Group.PV),
    /** 淘宝客广告 SDK 冷启动注册（AfcTkManager#registerTkFlag）。 */
    BG_AFC_TAOKE("AD-05", "bg_afc_taoke", R.string.f_bg_afc_taoke_title,
            R.string.f_bg_afc_taoke_summary, true, Group.AD),
    /** 设备指纹上传（UtdidUploadTask upload/uploadFromCache/reqServer）。 */
    BG_UTDID_UPLOAD("PV-02", "bg_utdid_upload", R.string.f_bg_utdid_upload_title,
            R.string.f_bg_utdid_upload_summary, false, Group.PV),
    /** H5 静默下载安装 APK（DeviceInfo#installApk）。 */
    H5_INSTALL_APK("PV-03", "h5_install_apk", R.string.f_h5_install_apk_title,
            R.string.f_h5_install_apk_summary, false, Group.PV),
    /** 升级弹窗（AppUpgradeController#q）。 */
    POPUP_UPGRADE("UI-01", "popup_upgrade", R.string.f_popup_upgrade_title,
            R.string.f_popup_upgrade_summary, true, Group.UI),
    /** 首页 Banner 断源（BannerRequestHolder#sendList 全重载 + BannerManager#b）。 */
    HOME_BANNER("AD-06", "home_banner", R.string.f_home_banner_title,
            R.string.f_home_banner_summary, true, Group.AD),
    /** 首页运营活动条与地图消息气泡（refreshSplashBar 不调用 + showTips 返回 false）。 */
    HOME_OPERATE_BAR("AD-07", "home_operate_bar", R.string.f_home_operate_bar_title,
            R.string.f_home_operate_bar_summary, true, Group.AD),
    /**
     * 首页消息红点体系（数据层断源，白名单式取舍）：
     * getMessages 全重载 + retrieveRemoteMessages 不调用（营销红点拉取断源）、
     * IDeliveryDisplay#start 不调用（主图投放）、
     * updateAppBadge 在 count>0 时不调用、count<=0 放行（清角标），
     * setBadgeDisplay 不调用；同一策略同时覆盖 AjxModuleBadge 与
     * 启动器角标路径 BadgeSystemManager#updateAppBadge。
     */
    HOME_MSG_FILTER("UI-02", "home_msg_filter", R.string.f_home_msg_filter_title,
            R.string.f_home_msg_filter_summary, true, Group.UI),
    /** 搜索热词断源（requestPreSetWord + PreSetWordManager e/d + AJX 回填，不动 sugg）。 */
    SEARCH_HOTWORD("UI-03", "search_hotword", R.string.f_search_hotword_title,
            R.string.f_search_hotword_summary, true, Group.UI),
    /** H5 广告点击/曝光监测断源（AlimamaRequestHolder#sendH5Log 全重载）。 */
    H5_AD_LOG("AD-08", "h5_ad_log", R.string.f_h5_ad_log_title,
            R.string.f_h5_ad_log_summary, true, Group.AD),
    /** 地图运营浮层断源（FloatingLayerManager#a 返回空串，不注册浮层）。 */
    MAP_FLOAT_LAYER("AD-09", "map_float_layer", R.string.f_map_float_layer_title,
            R.string.f_map_float_layer_summary, true, Group.AD),
    /** 营销图文消息素材下载断源（La8#run 不执行；类名兜底 a8 优先，DexKit 字符串锚定仅兜底）。 */
    MSG_IMAGE_TEXT("AD-10", "msg_image_text", R.string.f_msg_image_text_title,
            R.string.f_msg_image_text_summary, true, Group.AD),
    /**
     * 弹窗净化（升级提示配置 + 首页弹窗冲突队列）：
     * UpdateHintConfigDownloader#a 不调用、IDialogConflictMgr 实现类
     * Lry1#addToQueue（旧版 Lcx1）返回 false。后者中风险（可能误伤业务弹窗），可单关。
     */
    POPUP_CLEAN("UI-04", "popup_clean", R.string.f_popup_clean_title,
            R.string.f_popup_clean_summary, true, Group.UI),
    /** 统计上报净化（开屏埋点 track.b e/f + SplashLogManager + UTTracker send/sendLog）。 */
    TRACK_SUPPRESS("PV-04", "track_suppress", R.string.f_track_suppress_title,
            R.string.f_track_suppress_summary, false, Group.PV),
    /** H5/小程序摇一摇净化（H5ShakePlugin watchShake + H5SensorPlugin + 小程序扩展点）。 */
    SHAKE_H5("AD-11", "shake_h5", R.string.f_shake_h5_title,
            R.string.f_shake_h5_summary, true, Group.AD),
    /**
     * 运营活动弹窗净化：关闭运营活动模块开关（AppInitServiceImpl#getOperationModuleSwitch），
     * 并拦截活动打开入口（ActivitiesService#openOpetationsActivities 3 参/4 参重载）。
     */
    OPS_ACTIVITY("AD-12", "ops_activity", R.string.f_ops_activity_title,
            R.string.f_ops_activity_summary, true, Group.AD),
    /**
     * 通知权限引导净化：顶部条（so2#i 把 permissionType 改写为 location）、
     * 底部浮条（PermissionGuideFloatShow#showNotificationGuide）与系统浮条
     * （SysPermissionGuideFloatMode#showFloatWithSysCore 仅命中通知权限时跳过）。
     */
    NOTIFY_GUIDE("UI-05", "notify_guide", R.string.f_notify_guide_title,
            R.string.f_notify_guide_summary, true, Group.UI),
    /**
     * 消息中心净化（AJX bridge 数据层断源）：
     * AjxModuleBadge#getAllSessionList 回调空会话列表、
     * AjxModuleBadge#getBadgeInfo("messagetab") 角标归零、
     * AjxModuleLocalStorage#getItemSync("message_tab", "recentlyMsgList-*")
     * 返回空数组（setItemSync 同 key 不落盘）。
     * 只作用于消息页与我的页；首页/搜索等页面不受影响。
     */
    MSG_CENTER("UI-06", "msg_center", R.string.f_msg_center_title,
            R.string.f_msg_center_summary, true, Group.UI);

    /** 统一功能编号，格式 AD/UI/PV/EN/EX-NN；只用于展示、日志与文档。 */
    public final String id;
    /** remote prefs 键，发布后不可改。 */
    public final String key;
    public final int titleRes;
    public final int summaryRes;
    /** 编译期内置默认值：无 PROP_CAP_REMOTE 或 remote prefs 读不到时用。 */
    public final boolean defaultEnabled;
    /** 设置页与诊断页的分组。 */
    public final Group group;
    /** blocked 原因；null 表示功能可用。 */
    public final String blockedReason;

    Feature(String id, String key, int titleRes, int summaryRes, boolean defaultEnabled,
            Group group) {
        this(id, key, titleRes, summaryRes, defaultEnabled, group, null);
    }

    Feature(String id, String key, int titleRes, int summaryRes, boolean defaultEnabled,
            Group group, String blockedReason) {
        this.id = id;
        this.key = key;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.defaultEnabled = defaultEnabled;
        this.group = group;
        this.blockedReason = blockedReason;
    }

    /** blocked 功能保留编号与行位，但默认关、不注册 hook、UI 置灰。 */
    public boolean blocked() {
        return blockedReason != null;
    }

    /** 分组顺序固定 AD -> UI -> PV -> EN -> EX；没有功能的分组不显示。 */
    public enum Group { AD, UI, PV, EN, EX }
}
