package com.quietmap.app;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;

/**
 * 单 Activity 外壳：工具栏 + 三个顶层 tab（设置 / 诊断 / 关于）+ 常驻底栏。
 *
 * 三个 Fragment 首次创建时一次性 add，切换只用 hide/show，因此各自的
 * Preference 滚动位置与控件状态都会保留。设置是默认 tab，返回键在任何
 * 非设置 tab 上都先回到设置，只有在设置页才退出应用。
 */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "state_tab";
    private static final String TAG_SETTINGS = "tab_settings";
    private static final String TAG_DIAGNOSTICS = "tab_diagnostics";
    private static final String TAG_ABOUT = "tab_about";

    private MaterialToolbar mToolbar;
    private BottomNavigationView mBottomNav;
    private Fragment mCurrentFragment;
    private int mSelectedTab = R.id.nav_settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 super.onCreate 之前调用，否则动态取色不会生效。
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mToolbar = findViewById(R.id.toolbar);
        mToolbar.setTitle(R.string.app_name);
        mToolbar.setSubtitle(getString(R.string.app_subtitle, versionName()));

        mBottomNav = findViewById(R.id.bottom_nav);

        if (savedInstanceState == null) {
            Fragment settings = new SettingsFragment();
            Fragment diagnostics = new DiagnosticsFragment();
            Fragment about = new AboutFragment();
            // commitNow：底栏恢复选中态之前，三个 Fragment 必须已经就位，
            // 否则 setSelectedItemId 触发的回调会重复 add。
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.settings_container, settings, TAG_SETTINGS)
                    .add(R.id.settings_container, diagnostics, TAG_DIAGNOSTICS)
                    .hide(diagnostics)
                    .add(R.id.settings_container, about, TAG_ABOUT)
                    .hide(about)
                    .commitNow();
            mCurrentFragment = settings;
            mSelectedTab = R.id.nav_settings;
        } else {
            mSelectedTab = savedInstanceState.getInt(STATE_TAB, R.id.nav_settings);
            if (mSelectedTab != R.id.nav_diagnostics && mSelectedTab != R.id.nav_about) {
                mSelectedTab = R.id.nav_settings;
            }
            restoreFragments();
        }

        mBottomNav.setSelectedItemId(mSelectedTab);
        mBottomNav.setOnItemSelectedListener(item -> {
            switchTab(item.getItemId());
            return true;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mSelectedTab != R.id.nav_settings) {
                    // 先切 Fragment，再同步底栏选中态；setSelectedItemId 若触发
                    // 回调，switchTab 会因已选中而直接返回。
                    switchTab(R.id.nav_settings);
                    mBottomNav.setSelectedItemId(R.id.nav_settings);
                } else {
                    finish();
                }
            }
        });

        applyInsets();

        // 顺序不能反：registerListener 对已缓存的 binder 会立即回调，
        // 回调必须先挂上，否则首次绑定会被空回调吃掉、永远等不到重绘。
        ServiceBridge.setOnBound(() -> runOnUiThread(this::refreshCurrent));
        ServiceBridge.bind(getApplicationContext());
    }

    /** 旋转恢复：按 tag 找回三个 Fragment，只显示保存的 tab。 */
    private void restoreFragments() {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        Fragment target = null;
        for (String tag : new String[]{TAG_SETTINGS, TAG_DIAGNOSTICS, TAG_ABOUT}) {
            Fragment f = fm.findFragmentByTag(tag);
            if (f == null) {
                continue;
            }
            if (tag.equals(tagFor(mSelectedTab))) {
                target = f;
                tx.show(f);
            } else {
                tx.hide(f);
            }
        }
        if (target == null) {
            // 状态不完整时按当前 tab 重建，避免容器空白。
            target = createFragment(mSelectedTab);
            tx.add(R.id.settings_container, target, tagFor(mSelectedTab));
        }
        tx.commitNow();
        mCurrentFragment = target;
    }

    /** hide/show 切换，不 replace：保留每个 tab 的滚动位置与控件状态。 */
    private void switchTab(int tabId) {
        if (tabId != R.id.nav_diagnostics && tabId != R.id.nav_about) {
            tabId = R.id.nav_settings;
        }
        if (tabId == mSelectedTab && mCurrentFragment != null) {
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved()) {
            return;
        }

        Fragment target = fm.findFragmentByTag(tagFor(tabId));
        FragmentTransaction tx = fm.beginTransaction();
        if (mCurrentFragment != null && mCurrentFragment != target) {
            tx.hide(mCurrentFragment);
        }
        if (target == null) {
            target = createFragment(tabId);
            tx.add(R.id.settings_container, target, tagFor(tabId));
        } else {
            tx.show(target);
        }
        tx.commit();

        mCurrentFragment = target;
        mSelectedTab = tabId;
        // 进入诊断页时刷新一次，等价于二级页时代 onResume 的行为。
        refreshCurrent();
    }

    private Fragment createFragment(int tabId) {
        switch (tabId) {
            case R.id.nav_diagnostics:
                return new DiagnosticsFragment();
            case R.id.nav_about:
                return new AboutFragment();
            case R.id.nav_settings:
            default:
                return new SettingsFragment();
        }
    }

    private String tagFor(int tabId) {
        switch (tabId) {
            case R.id.nav_diagnostics:
                return TAG_DIAGNOSTICS;
            case R.id.nav_about:
                return TAG_ABOUT;
            case R.id.nav_settings:
            default:
                return TAG_SETTINGS;
        }
    }

    /** targetSdk 36 强制 edge-to-edge，内容会画到状态栏/导航栏下面，必须自己吃 insets。 */
    private void applyInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            // bottom 不能写到 root：BottomNavigationView 在 Material 构造时会自己把
            // 同一份导航栏 inset 加进自身 padding，root 再垫一次底栏会被抬高约
            // 两倍导航栏高度。bottom 只由底栏自己消化（insets 原样下发）。
            v.setPadding(bars.left, bars.top, bars.right, 0);
            return windowInsets;
        });
    }

    /** 框架绑定/断开后刷新当前可见页；关于页是静态内容，不处理。 */
    private void refreshCurrent() {
        // binder 回调可能晚于 Activity 销毁，避免在已销毁的实例上操作 Fragment。
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Fragment f = mCurrentFragment;
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).refresh();
        } else if (f instanceof DiagnosticsFragment) {
            ((DiagnosticsFragment) f).refresh();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, mSelectedTab);
    }

    @Override
    protected void onDestroy() {
        // 清掉对 Activity 的静态引用，防止退出后 binder 回调仍持有已销毁实例。
        ServiceBridge.setOnBound(null);
        super.onDestroy();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
