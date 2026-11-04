package com.quietmap.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.yigenhuobah.core.ConfigPolicy;

/**
 * 设置 tab：状态卡 + 异常横幅 + AD/UI/PV 分组开关 + 页脚。
 *
 * 设置页零诊断：这里不展示逐功能 registered/hit/skip，只保留状态卡和聚合横幅；
 * 细节统一放到 {@link DiagnosticsFragment}（底栏的“诊断”tab）。
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TAG = "QuietMap";

    /** 两次 refresh 的最小间隔，压掉 onCreatePreferences / onResume / 绑定回调三连。 */
    private static final long REFRESH_MIN_INTERVAL_MS = 1000L;

    /** 失败功能数来自 prefs/文件读取，短缓存避免状态卡刷新时重复读盘。 */
    private static final long BANNER_TTL_MS = 5_000L;

    /**
     * 状态卡的框架查询是同步 binder（getApiVersion/getScope/getRunningTargets），
     * 放后台单线程执行，避免框架卡顿时拖住设置页首帧。
     */
    private static final ExecutorService STATUS_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qm-settings-status");
                t.setDaemon(true);
                return t;
            });

    /**
     * ConfigStore 构造会同步拿 remote prefs（首次 binder），初始值读取也可能走
     * binder；与状态查询分开用一条后台线程，避免状态查询排队拖慢开关渲染。
     */
    private static final ExecutorService CONFIG_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qm-settings-config");
                t.setDaemon(true);
                return t;
            });

    private ConfigStore mStore;
    /** mStore 对应的 service 实例；同一实例内 remote prefs 不变，可复用。 */
    private XposedService mStoreService;
    private long mLastRefreshMs;
    private boolean mHasRefreshed;
    private XposedService mLastRefreshService;
    /** 后台状态查询句柄：新查询提交前取消旧的，onDestroyView 取消全部。 */
    private Future<?> mStatusFuture;
    /** 后台失败功能数查询句柄；与状态卡查询共用单线程执行器。 */
    private Future<?> mBannerFuture;
    /** 后台 ConfigStore 构造/读取句柄；新 refresh 提交前取消旧的。 */
    private Future<?> mStoreFuture;
    /** 查询代次：旧查询即使无法中断，也不能覆盖新查询的结果。 */
    private int mStoreGeneration;
    /** 主线程渲染过的开关值，写失败回滚时不再同步读 remote prefs。 */
    private final Map<String, Boolean> mRenderedValues = new HashMap<>();
    /**
     * 每个 key 的写入代次（主线程读写）：失败回滚只认最新一笔写入，
     * 旧写入的失败回调不得覆盖用户随后拨动的开关值。
     */
    private final Map<String, Integer> mWriteGeneration = new HashMap<>();
    /** 失败功能数缓存：-1 表示尚未读取。 */
    private volatile int mFailedCount = -1;
    private volatile long mFailedCountAt;
    /** versionName 在 Fragment 生命周期内不变，只读一次 PackageManager。 */
    private String mVersionName;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        bindFeatures();
        bindPages();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        // view 已销毁，后台查询结果不再有用；cancel(true) 让阻塞中的 binder 尽快返回。
        if (mStatusFuture != null) {
            mStatusFuture.cancel(true);
            mStatusFuture = null;
        }
        if (mBannerFuture != null) {
            mBannerFuture.cancel(true);
            mBannerFuture = null;
        }
        if (mStoreFuture != null) {
            mStoreFuture.cancel(false);
            mStoreFuture = null;
        }
        mStoreGeneration++;
        // 二级页返回或旋转后 view 会重建：取消过的查询不能靠 1 秒节流挡掉，
        // 否则状态卡可能停在被取消前的旧状态。
        mHasRefreshed = false;
        super.onDestroyView();
    }

    /** 把 Feature 的标题/说明/开关接到 XML 声明的偏好项上。 */
    private void bindFeatures() {
        for (Feature f : Feature.values()) {
            Preference p = findPreference(f.key);
            if (p == null) {
                Log.e(TAG, "preference missing for " + f.key);
                continue;
            }
            p.setTitle(f.titleRes);
            p.setSummary(f.summaryRes);

            if (f.blocked()) {
                // blocked 功能保留编号和行位，但不注册 hook、不允许切换。
                p.setEnabled(false);
                p.setSummary(getString(R.string.f_blocked_summary, f.blockedReason));
                continue;
            }

            // 配置在后台读取完成前不允许点击，避免把“还没加载”误当成“已开启”。
            p.setEnabled(false);
            p.setOnPreferenceChangeListener((pref, value) -> {
                if (value instanceof Boolean) {
                    return handleToggle(f, (Boolean) value);
                }
                return true;
            });
        }
    }

    /**
     * 写开关。remote 不可用时直接拒绝；写入在后台线程 commit，
     * commit 失败再回主线程 Toast 并把开关拨回旧值。
     */
    private boolean handleToggle(Feature f, boolean next) {
        ConfigStore store = mStore;
        if (store == null || !store.remote()) {
            Toast.makeText(requireContext(), R.string.action_write_failed,
                    Toast.LENGTH_SHORT).show();
            return false;
        }

        Boolean rendered = mRenderedValues.get(f.key);
        final boolean previous = rendered != null ? rendered : f.defaultEnabled;
        if (previous == next) {
            return true;
        }

        final Activity host = getActivity();
        // 写入代次：同一 key 的失败回调只有在仍是最新一笔写入时才允许回滚，
        // 否则旧失败的回滚会覆盖后续（可能已成功）写入的 UI 状态。
        // 代次只在写入成功排队后生效（见下方 put），被拒绝的写入不得让旧回调失效。
        Integer lastGeneration = mWriteGeneration.get(f.key);
        final int generation = (lastGeneration != null ? lastGeneration : 0) + 1;
        boolean scheduled = store.put(f.key, next, committed -> {
            // 写入已被接受，只有 commit 真正成功才能保留 UI 新值；失败必须回滚。
            if (!ConfigPolicy.mustRollback(true, committed) || host == null) {
                return;
            }
            host.runOnUiThread(() -> {
                if (!isAdded() || getActivity() != host) {
                    return;
                }
                Integer latest = mWriteGeneration.get(f.key);
                if (latest == null || latest != generation) {
                    // 用户已经又拨动了这个开关：本笔失败的回滚必须让位。
                    return;
                }
                Toast.makeText(requireContext(), R.string.action_write_failed,
                        Toast.LENGTH_SHORT).show();
                // UI 仍停在这笔写入的值时才回滚，避免覆盖用户后续操作。
                if (Boolean.valueOf(next).equals(mRenderedValues.get(f.key))) {
                    mRenderedValues.put(f.key, previous);
                    Preference p = findPreference(f.key);
                    if (p instanceof MaterialSwitchPreference) {
                        ((MaterialSwitchPreference) p).setCheckedValue(previous);
                    }
                }
            });
        });
        if (!scheduled) {
            Toast.makeText(requireContext(), R.string.action_write_failed,
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        // 先乐观更新渲染缓存；commit 失败的回调会再拨回去。
        // 这笔写入自此成为该 key 的最新代次：UI 回调只会晚于当前主线程块执行，
        // 所以这里先记代次不会与回调竞争。
        mWriteGeneration.put(f.key, generation);
        mRenderedValues.put(f.key, next);
        return true;
    }

    /** 页脚说明。 */
    private void bindPages() {
        Preference footer = findPreference("footer");
        if (footer != null) {
            footer.setSummary(getString(R.string.footer_restart,
                    getString(R.string.app_target_name))
                    + "\n" + getString(R.string.footer_compat,
                    getString(R.string.app_target_name), VersionGate.SUPPORTED_VERSION));
        }
    }

    /** 重新读取框架状态与开关值。service 绑定/断开后由 MainActivity 调一次。 */
    void refresh() {
        if (getContext() == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        XposedService service = ServiceBridge.get();
        // 1 秒内的重复调用丢弃；binder 刚绑上/断开属于新信息，必须放行。
        if (mHasRefreshed
                && now - mLastRefreshMs < REFRESH_MIN_INTERVAL_MS
                && service == mLastRefreshService) {
            return;
        }
        mHasRefreshed = true;
        mLastRefreshMs = now;
        mLastRefreshService = service;

        Preference status = findPreference("status");
        if (status != null && service == null) {
            status.setTitle(R.string.status_connecting);
            status.setSummary(getString(R.string.status_detail_connecting,
                    shownTargetVersion()));
        }

        final Activity host = requireActivity();
        Context appContext = requireContext().getApplicationContext();
        final Context app = appContext != null ? appContext : requireContext();
        final ConfigStore existing = mStore;
        final boolean rebuild = ConfigStore.needsRebuild(
                existing != null, mStoreService, service);
        final int generation = ++mStoreGeneration;
        if (rebuild) {
            // service 刚绑定/断开或还没有 store：先置灰，后台读取完成后再按
            // remote 可用性恢复，避免这段时间里点到“改了没用”的开关。
            setSwitchesEnabled(false);
        }

        if (mStoreFuture != null) {
            mStoreFuture.cancel(false);
        }
        mStoreFuture = CONFIG_EXECUTOR.submit(() -> {
            ConfigStore store = existing;
            if (rebuild || store == null) {
                try {
                    // 构造与首次 remote prefs 绑定都在后台线程完成。
                    store = new ConfigStore(app);
                } catch (Throwable t) {
                    Log.w(TAG, "config store init failed", t);
                    store = null;
                }
            }
            final boolean writable = store != null && store.remote();
            final Map<String, Boolean> values = new HashMap<>();
            for (Feature f : Feature.values()) {
                values.put(f.key, store != null
                        ? store.get(f.key, f.defaultEnabled)
                        : f.defaultEnabled);
            }
            final ConfigStore loaded = store;

            host.runOnUiThread(() -> {
                if (generation != mStoreGeneration || !isAdded() || getActivity() != host) {
                    return;
                }
                mStore = loaded;
                mStoreService = service;
                mRenderedValues.clear();
                mRenderedValues.putAll(values);
                applyValues(values, writable);

                Preference p = findPreference("status");
                if (p != null) {
                    if (service == null) {
                        appendRemoteState(p, writable);
                    } else {
                        loadStatusAsync(service, writable);
                    }
                }
                updateBanner();
            });
        });
    }

    /** 主线程渲染开关值；remote 不可用或 blocked 时置灰。 */
    private void applyValues(Map<String, Boolean> values, boolean writable) {
        for (Feature f : Feature.values()) {
            Preference p = findPreference(f.key);
            if (p instanceof MaterialSwitchPreference) {
                MaterialSwitchPreference sw = (MaterialSwitchPreference) p;
                Boolean value = values.get(f.key);
                // blocked 项即使 remote 里残留 true 也必须显示关闭，避免“不可点击却显示已开启”。
                sw.setCheckedValue(!f.blocked()
                        && (value != null ? value : f.defaultEnabled));
                // 框架没绑定时写不进去，置灰避免“改了没用”；blocked 始终置灰。
                sw.setEnabled(writable && !f.blocked());
            }
        }
    }

    /** 重新加载期间先置灰可写开关；blocked 项本来就是灰的，不动。 */
    private void setSwitchesEnabled(boolean enabled) {
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            Preference p = findPreference(f.key);
            if (p != null) {
                p.setEnabled(enabled);
            }
        }
    }

    /**
     * 状态卡的 service 查询全在后台单线程跑，结果回主线程写 UI。
     * Fragment 已 detach 或 service 已换绑时整份结果丢弃。
     */
    private void loadStatusAsync(XposedService service, boolean writable) {
        final XposedService bound = service;
        final boolean remoteWritable = writable;
        final String version = versionName();
        final String targetVersion = shownTargetVersion();
        final Activity host = requireActivity();

        if (mStatusFuture != null) {
            mStatusFuture.cancel(false);
        }
        mStatusFuture = STATUS_EXECUTOR.submit(() -> {
            int api = 0;
            boolean scoped = false;
            try {
                api = bound.getApiVersion();
            } catch (Throwable ignored) {
            }
            try {
                List<String> scope = bound.getScope();
                scoped = scope != null && scope.contains(ReportPaths.TARGET_PACKAGE);
            } catch (Throwable ignored) {
            }
            // getRunningTargets 是“当前真的被注入的进程”，只有它命中目标才能说明已激活。
            boolean injected = isTargetRunning(bound);
            final int finalApi = api;
            final boolean finalScoped = scoped;
            final boolean finalInjected = injected;

            // isAdded()/getActivity() 是主线程 API（Fragment.mHost/mAdded 非 volatile），
            // 不能在这个后台线程读；失效结果的丢弃统一交给 runOnUiThread 里的主线程复查。
            host.runOnUiThread(() -> {
                if (!isAdded() || getActivity() != host) {
                    return;
                }
                if (ServiceBridge.get() != bound) {
                    return;
                }
                Preference p = findPreference("status");
                if (p == null) {
                    return;
                }
                String appName = getString(R.string.app_target_name);
                if (!finalScoped) {
                    p.setTitle(R.string.status_inactive);
                    p.setSummary(getString(R.string.status_detail_no_scope,
                            finalApi, appName, targetVersion, version));
                } else if (finalInjected) {
                    p.setTitle(R.string.status_active);
                    p.setSummary(getString(R.string.status_detail_active,
                            finalApi, appName, targetVersion, version));
                } else {
                    p.setTitle(R.string.status_connected);
                    p.setSummary(getString(R.string.status_detail_not_running,
                            finalApi, appName, targetVersion, version));
                }
                appendRemoteState(p, remoteWritable);
                updateBanner();
            });
        });
    }

    /**
     * 版本不匹配或 ≥2 个功能失效时显示红色横幅；不新增第五种状态。
     * 失败功能数需要读 prefs/文件，放后台单线程；主线程只更新 Preference。
     */
    private void updateBanner() {
        Preference banner = findPreference("warning");
        if (banner == null || getContext() == null) {
            return;
        }
        String target = targetVersion();
        if (target == null) {
            // 目标应用未安装或 PackageManager 读不到版本：不显示横幅，避免误导。
            banner.setVisible(false);
            return;
        }
        if (!VersionGate.isSupported(target)) {
            showBannerMessage(getString(R.string.status_banner_version, target,
                    VersionGate.SUPPORTED_VERSION));
            return;
        }
        int cached = mFailedCount;
        long age = SystemClock.elapsedRealtime() - mFailedCountAt;
        if (cached >= 0 && age >= 0 && age < BANNER_TTL_MS) {
            applyFailedCount(cached);
            return;
        }
        // 缓存过期/未读：隐藏旧横幅，后台读完再决定是否显示。
        banner.setVisible(false);
        loadFailedCountAsync();
    }

    private void applyFailedCount(int failed) {
        if (failed >= 2) {
            showBannerMessage(getString(R.string.status_banner_failed, failed));
        } else {
            Preference banner = findPreference("warning");
            if (banner != null) {
                banner.setVisible(false);
            }
        }
    }

    private void showBannerMessage(String message) {
        Preference banner = findPreference("warning");
        if (banner == null) {
            return;
        }
        banner.setSummary(message);
        banner.setVisible(true);
    }

    private void loadFailedCountAsync() {
        if (getContext() == null) {
            return;
        }
        final Context app = requireContext().getApplicationContext();
        final Activity host = requireActivity();
        if (mBannerFuture != null) {
            mBannerFuture.cancel(false);
        }
        mBannerFuture = STATUS_EXECUTOR.submit(() -> {
            final int failed;
            try {
                failed = StatusProvider.failedFeatureCount(app);
            } catch (Throwable t) {
                return;
            }
            mFailedCount = failed;
            mFailedCountAt = SystemClock.elapsedRealtime();
            // 同上：不在后台线程读 isAdded()/getActivity()，交给主线程复查。
            host.runOnUiThread(() -> {
                if (!isAdded() || getActivity() != host) {
                    return;
                }
                // 版本可能在查询期间变化；以最新的版本判断为准。
                String target = targetVersion();
                if (target != null && VersionGate.isSupported(target)) {
                    applyFailedCount(failed);
                }
            });
        });
    }

    /** remote prefs 可用性挂在状态卡副标题第二行，抓“开关写不进框架”这个坑。 */
    private void appendRemoteState(Preference status, boolean writable) {
        status.setSummary(status.getSummary() + "\n"
                + getString(writable ? R.string.status_remote_ok : R.string.status_remote_fail));
    }

    /** 判断目标进程当前是否真的被注入（API 102 getRunningTargets）。 */
    private boolean isTargetRunning(XposedService service) {
        try {
            List<HookedTarget> targets = service.getRunningTargets();
            if (targets == null) {
                return false;
            }
            for (HookedTarget t : targets) {
                String name = t.getProcessName();
                if (name != null && (name.equals(ReportPaths.TARGET_PACKAGE)
                        || name.startsWith(ReportPaths.TARGET_PACKAGE + ":"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 目标版本缓存：getPackageInfo 是主线程同步 PMS 调用，成功后本实例内不再重复查。 */
    private String mTargetVersion;

    private String targetVersion() {
        if (mTargetVersion != null) {
            return mTargetVersion;
        }
        try {
            PackageInfo pi = requireContext().getPackageManager()
                    .getPackageInfo(ReportPaths.TARGET_PACKAGE, 0);
            // 查询失败或目标未安装（versionName 为 null）不缓存：下次 refresh 重试。
            mTargetVersion = pi.versionName;
            return mTargetVersion;
        } catch (Throwable t) {
            return null;
        }
    }

    private String shownTargetVersion() {
        String v = targetVersion();
        return v != null ? v : getString(R.string.status_unknown_version);
    }

    private String versionName() {
        if (mVersionName != null) {
            return mVersionName;
        }
        try {
            String v = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            mVersionName = v != null ? v : "?";
        } catch (Throwable t) {
            mVersionName = "?";
        }
        return mVersionName;
    }
}
