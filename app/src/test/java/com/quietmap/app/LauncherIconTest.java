package com.quietmap.app;

import android.content.pm.PackageManager;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 组件启用状态到「图标是否隐藏」的映射契约测试。
 * 只覆盖纯函数与 manifest 契约，不依赖 Context 与 PackageManager 实例。
 */
public class LauncherIconTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void isHiddenState_defaultIsVisible() {
        assertEquals("DEFAULT 应视为未隐藏",
                Boolean.FALSE,
                LauncherIcon.isHiddenState(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT));
    }

    @Test
    public void isHiddenState_enabledIsVisible() {
        assertEquals("ENABLED 应视为未隐藏",
                Boolean.FALSE,
                LauncherIcon.isHiddenState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED));
    }

    @Test
    public void isHiddenState_disabledIsHidden() {
        assertEquals("DISABLED 应视为已隐藏",
                Boolean.TRUE,
                LauncherIcon.isHiddenState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED));
    }

    @Test
    public void isHiddenState_disabledUserIsHidden() {
        assertEquals("DISABLED_USER 应视为已隐藏",
                Boolean.TRUE,
                LauncherIcon.isHiddenState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER));
    }

    @Test
    public void isHiddenState_disabledUntilUsedIsHidden() {
        assertEquals("DISABLED_UNTIL_USED 应视为已隐藏",
                Boolean.TRUE,
                LauncherIcon.isHiddenState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED));
    }

    @Test
    public void isHiddenState_unknownValueReturnsNull() {
        assertNull("Integer.MIN_VALUE 属于未知状态，应返回 null",
                LauncherIcon.isHiddenState(Integer.MIN_VALUE));
        assertNull("-1 属于未知状态，应返回 null",
                LauncherIcon.isHiddenState(-1));
        assertNull("5 属于未知状态，应返回 null",
                LauncherIcon.isHiddenState(5));
    }

    /**
     * 代码常量与 manifest 契约。
     *
     * <p>两条都是隐藏开关能成立的前提，缺一条就会把自己锁在外面：
     * <ol>
     *   <li>{@code ALIAS_CLASS} 必须作为 activity-alias 出现在 manifest 里，
     *       否则开关会去改一个不存在的组件；</li>
     *   <li>该 alias 的 targetActivity 必须指向 MainActivity。</li>
     * </ol>
     *
     * <p>另有一条兜底断言：MainActivity 必须仍挂着 MODULE_SETTINGS，否则隐藏图标后
     * LSPosed 管理器再也没有入口打开本页。
     *
     * <p>Gradle 单测的工作目录是 app 模块，因此用相对路径读取。
     */
    @Test
    public void manifestDeclaresLauncherAlias() throws Exception {
        File manifest = new File("src/main/AndroidManifest.xml");
        assertTrue("单测工作目录应位于 app 模块，未找到 manifest：" + manifest.getAbsolutePath(),
                manifest.isFile());

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(manifest);

        Element alias = null;
        NodeList aliases = doc.getElementsByTagName("activity-alias");
        for (int i = 0; i < aliases.getLength(); i++) {
            Element e = (Element) aliases.item(i);
            if (LauncherIcon.ALIAS_CLASS.equals(e.getAttributeNS(ANDROID_NS, "name"))) {
                alias = e;
                break;
            }
        }
        assertTrue("manifest 缺少 activity-alias：" + LauncherIcon.ALIAS_CLASS, alias != null);
        assertEquals("alias 必须指向 MainActivity，否则图标隐藏后会没有可用的落点",
                ".MainActivity",
                alias.getAttributeNS(ANDROID_NS, "targetActivity"));

        // MainActivity 自身只有 MAIN+MODULE_SETTINGS，没有 LAUNCHER：alias 丢掉
        // 桌面 intent-filter 后，桌面将没有任何本模块图标，“隐藏图标”隐藏的
        // 只是一个不存在的入口。
        assertTrue("alias 必须自带 MAIN/LAUNCHER intent-filter，否则桌面没有入口",
                hasLauncherIntentFilter(alias));

        // alias 默认不得禁用：android:enabled="false" 会让图标从安装起就消失，
        // 与上一条同为桌面入口成立的前提（属性缺省时系统按启用处理，不算禁用）。
        assertFalse("alias 不能默认禁用（android:enabled=\"false\"）",
                "false".equals(alias.getAttributeNS(ANDROID_NS, "enabled")));

        assertTrue("MainActivity 必须保留 MODULE_SETTINGS 入口，否则隐藏图标后无法从 LSPosed 管理器恢复",
                hasModuleSettingsEntry(doc));
    }

    /** 同一个 intent-filter 内必须同时有 MAIN action 与 LAUNCHER category。 */
    private static boolean hasLauncherIntentFilter(Element alias) {
        NodeList filters = alias.getElementsByTagName("intent-filter");
        for (int i = 0; i < filters.getLength(); i++) {
            Element filter = (Element) filters.item(i);
            boolean main = false;
            boolean launcher = false;
            NodeList actions = filter.getElementsByTagName("action");
            for (int j = 0; j < actions.getLength(); j++) {
                if ("android.intent.action.MAIN".equals(
                        ((Element) actions.item(j)).getAttributeNS(ANDROID_NS, "name"))) {
                    main = true;
                }
            }
            NodeList categories = filter.getElementsByTagName("category");
            for (int j = 0; j < categories.getLength(); j++) {
                if ("android.intent.category.LAUNCHER".equals(
                        ((Element) categories.item(j)).getAttributeNS(ANDROID_NS, "name"))) {
                    launcher = true;
                }
            }
            if (main && launcher) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasModuleSettingsEntry(Document doc) {
        NodeList activities = doc.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            if (!".MainActivity".equals(activity.getAttributeNS(ANDROID_NS, "name"))) {
                continue;
            }
            NodeList categories = activity.getElementsByTagName("category");
            for (int j = 0; j < categories.getLength(); j++) {
                String name = ((Element) categories.item(j)).getAttributeNS(ANDROID_NS, "name");
                if ("de.robv.android.xposed.category.MODULE_SETTINGS".equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
