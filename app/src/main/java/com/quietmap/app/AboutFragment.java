package com.quietmap.app;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

/**
 * 关于页：模块/版本、目标应用、源码、四模块互链、免责声明、许可证。
 *
 * 互链使用 SpannableString + ClickableSpan + LinkMovementMethod，
 * 颜色取 {@code about_link}；当前模块名称后加“（当前）”。
 */
public class AboutFragment extends PreferenceFragmentCompat {

    private static final String CURRENT_APPLICATION_ID = "io.github.yigenhuobah.quietmap";
    private static final String SOURCE_URL = "https://github.com/yigenhuobah/quietmap";
    private static final String LICENSE_URL = SOURCE_URL + "/blob/main/LICENSE";

    /** 桌面图标开关的 key：仅用于 onResume 回读，不参与持久化（状态在组件上）。 */
    private static final String KEY_HIDE_ICON = "about_hide_icon";

    private static final int[] LINK_NAME_RES = {
            R.string.about_module_cloudmusictool,
            R.string.about_module_quietbox,
            R.string.about_module_quietmap,
            R.string.about_module_tiebaquiet
    };
    private static final int[] LINK_NAME_EN_RES = {
            R.string.about_module_cloudmusictool_en,
            R.string.about_module_quietbox_en,
            R.string.about_module_quietmap_en,
            R.string.about_module_tiebaquiet_en
    };
    private static final String[] LINK_PACKAGES = {
            "io.github.yigenhuobah.cloudmusictool",
            "io.github.yigenhuobah.quietbox",
            "io.github.yigenhuobah.quietmap",
            "io.github.yigenhuobah.tiebaquiet"
    };
    private static final String[] LINK_URLS = {
            "https://github.com/yigenhuobah/cloudmusictool",
            "https://github.com/yigenhuobah/quietbox",
            "https://github.com/yigenhuobah/quietmap",
            "https://github.com/yigenhuobah/tiebaquiet"
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);

        Preference module = info(R.string.app_name,
                getString(R.string.about_version, versionName(), versionCode()));
        module.setSelectable(false);
        screen.addPreference(module);

        Preference target = info(R.string.about_target,
                getString(R.string.about_target_value, getString(R.string.app_target_name),
                        ReportPaths.TARGET_PACKAGE, VersionGate.SUPPORTED_VERSION));
        target.setSelectable(false);
        screen.addPreference(target);

        Preference source = info(R.string.about_source,
                getString(R.string.about_source_summary, SOURCE_URL));
        source.setOnPreferenceClickListener(p -> {
            openUrl(SOURCE_URL);
            return true;
        });
        screen.addPreference(source);

        screen.addPreference(buildLinksPreference());

        Preference disclaimer = info(R.string.about_disclaimer_title,
                getString(R.string.about_disclaimer, getString(R.string.app_target_name)));
        disclaimer.setSelectable(false);
        screen.addPreference(disclaimer);

        Preference license = info(R.string.about_license_title,
                getString(R.string.about_license_summary, "MIT"));
        license.setOnPreferenceClickListener(p -> {
            openUrl(LICENSE_URL);
            return true;
        });
        screen.addPreference(license);

        // ── 桌面图标 ──
        // 前面都是只读信息，唯一可操作项放最后。
        PreferenceCategory iconCategory = new PreferenceCategory(requireContext());
        iconCategory.setTitle(R.string.about_icon_title);
        screen.addPreference(iconCategory);

        MaterialSwitchPreference hideIcon = new MaterialSwitchPreference(requireContext());
        hideIcon.setKey(KEY_HIDE_ICON);
        hideIcon.setTitle(R.string.about_hide_icon_title);
        hideIcon.setSummary(R.string.about_hide_icon_summary);
        hideIcon.setIcon(R.drawable.ic_launcher_visibility);
        hideIcon.setCheckedValue(LauncherIcon.isHidden(requireContext()));
        hideIcon.setOnPreferenceChangeListener((pref, value) -> {
            if (!(value instanceof Boolean)) {
                return false;
            }
            boolean hide = (Boolean) value;
            if (!LauncherIcon.setHidden(requireContext(), hide)) {
                Toast.makeText(requireContext(), R.string.about_hide_icon_failed,
                        Toast.LENGTH_LONG).show();
                return false;
            }
            Toast.makeText(requireContext(),
                    hide ? R.string.about_hide_icon_toast_hidden : R.string.about_hide_icon_toast_shown,
                    Toast.LENGTH_SHORT).show();
            return true;
        });
        screen.addPreference(hideIcon);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 组件状态可能被外部改动（adb、清 App 数据），回到本页时以系统状态为准重刷。
        syncLauncherIconState();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // 三个 Fragment 是 add + hide/show（不是 replace），切回本页不会走 onResume，
        // 需要在这里补一次回读，否则开关可能停在上次的值上。
        if (!hidden) {
            syncLauncherIconState();
        }
    }

    private void syncLauncherIconState() {
        Preference p = findPreference(KEY_HIDE_ICON);
        if (p instanceof MaterialSwitchPreference) {
            ((MaterialSwitchPreference) p).setCheckedValue(LauncherIcon.isHidden(requireContext()));
        }
    }

    private Preference info(int titleRes, CharSequence summary) {
        Preference p = new Preference(requireContext());
        p.setTitle(titleRes);
        p.setSummary(summary);
        return p;
    }

    private Preference buildLinksPreference() {
        Preference links = new Preference(requireContext()) {
            @Override
            public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
                super.onBindViewHolder(holder);
                TextView tv = (TextView) holder.findViewById(R.id.about_links);
                if (tv == null) {
                    return;
                }
                tv.setText(buildLinks());
                tv.setMovementMethod(LinkMovementMethod.getInstance());
                tv.setHighlightColor(Color.TRANSPARENT);
            }
        };
        links.setLayoutResource(R.layout.preference_about_links);
        links.setTitle(R.string.about_links_title);
        links.setSummary(R.string.about_links_summary);
        links.setSelectable(false);
        return links;
    }

    private CharSequence buildLinks() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int color = ContextCompat.getColor(requireContext(), R.color.about_link);
        for (int i = 0; i < LINK_URLS.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String label = getString(LINK_NAME_RES[i]) + " ("
                    + getString(LINK_NAME_EN_RES[i]) + ")";
            if (LINK_PACKAGES[i].equals(CURRENT_APPLICATION_ID)) {
                label += getString(R.string.about_current_mark);
            }
            int start = sb.length();
            sb.append(label);
            int end = sb.length();

            final String url = LINK_URLS[i];
            ClickableSpan span = new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    openUrl(url);
                }

                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setColor(color);
                    ds.setUnderlineText(false);
                }
            };
            sb.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.action_open_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 模块自身 PackageInfo：getPackageInfo 是主线程同步 PMS 调用，versionName/versionCode 只查一次。 */
    private PackageInfo mSelfInfo;

    /** 查询失败不缓存（下次进入本页重试），成功后本实例内复用。 */
    private PackageInfo selfPackageInfo() {
        if (mSelfInfo == null) {
            try {
                mSelfInfo = requireContext().getPackageManager()
                        .getPackageInfo(requireContext().getPackageName(), 0);
            } catch (Throwable t) {
                return null;
            }
        }
        return mSelfInfo;
    }

    private String versionName() {
        PackageInfo pi = selfPackageInfo();
        String v = pi != null ? pi.versionName : null;
        return v != null ? v : "?";
    }

    private int versionCode() {
        PackageInfo pi = selfPackageInfo();
        return pi != null ? (int) pi.getLongVersionCode() : 0;
    }
}
