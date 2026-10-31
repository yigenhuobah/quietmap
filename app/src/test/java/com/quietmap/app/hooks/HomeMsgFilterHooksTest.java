package com.quietmap.app.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * HomeMsgFilterHooks 的纯 JVM 逻辑：getMessages 回调形状（onFinish 空结果实参）、
 * 角标桥 bizId 门控。Hooker 本身需要 XposedModule/Chain，JVM 单测无法构造；
 * 回调形状通过真实 onFinish(List,List,Z,Z) 接口验证。
 */
public class HomeMsgFilterHooksTest {

    /** 与高德 GetMessageListener 相同的完成回调签名（HomeMsgFilterHooks 类注释 1)。 */
    public interface FakeGetMessageListener {
        void onFinish(List<String> newMessages, List<String> oldMessages,
                      boolean hasMore, boolean fromCache);
    }

    public static final class RecordingListener implements FakeGetMessageListener {
        int calls;
        List<String> firstArg;
        List<String> secondArg;
        boolean firstFlag;
        boolean secondFlag;

        @Override
        public void onFinish(List<String> newMessages, List<String> oldMessages,
                             boolean hasMore, boolean fromCache) {
            calls++;
            firstArg = newMessages;
            secondArg = oldMessages;
            firstFlag = hasMore;
            secondFlag = fromCache;
        }
    }

    /** 回调内部抛错的 listener：验证 invokeGetMessagesCallback 会抛 InvocationTargetException。 */
    public static final class ThrowingListener implements FakeGetMessageListener {
        static final RuntimeException BOOM = new RuntimeException("listener boom");

        @Override
        public void onFinish(List<String> newMessages, List<String> oldMessages,
                             boolean hasMore, boolean fromCache) {
            throw BOOM;
        }
    }

    /** 回归锁定 id 86：空结果回调必须以 (List,List,boolean,boolean) 四个独立实参展开。 */
    @Test
    public void invokeGetMessagesCallbackPassesFourArgs() throws Exception {
        Method onFinish = FakeGetMessageListener.class.getDeclaredMethod("onFinish",
                List.class, List.class, boolean.class, boolean.class);
        RecordingListener listener = new RecordingListener();

        HomeMsgFilterHooks.invokeGetMessagesCallback(onFinish, listener);

        assertEquals("onFinish 必须被恰好回调一次（否则调用方一直等不到结果）", 1, listener.calls);
        assertTrue("新消息列表必须为空", listener.firstArg.isEmpty());
        assertTrue("旧消息列表必须为空", listener.secondArg.isEmpty());
        assertFalse("hasMore 必须为 false", listener.firstFlag);
        assertFalse("fromCache 必须为 false", listener.secondFlag);
    }

    /** 回归锁定 id 86：两组空列表不能是同一个实例，避免调用方改动互相污染。 */
    @Test
    public void emptyListenerCallbackArgsAreDistinctLists() {
        Object[] args = HomeMsgFilterHooks.emptyListenerCallbackArgs();
        assertEquals(4, args.length);
        assertTrue(args[0] instanceof List);
        assertTrue(args[1] instanceof List);
        assertTrue("第一个列表与第二个列表不能是同一实例",
                args[0] != args[1]);
        assertEquals(Boolean.FALSE, args[2]);
        assertEquals(Boolean.FALSE, args[3]);
    }

    /** 回调内部抛错必须以 InvocationTargetException 冒出，供 Hooker 区分「已进入回调」。 */
    @Test
    public void listenerThrowSurfacesAsInvocationTargetException() throws Exception {
        Method onFinish = FakeGetMessageListener.class.getDeclaredMethod("onFinish",
                List.class, List.class, boolean.class, boolean.class);

        try {
            HomeMsgFilterHooks.invokeGetMessagesCallback(onFinish, new ThrowingListener());
            throw new AssertionError("expected InvocationTargetException");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertEquals(ThrowingListener.BOOM, expected.getCause());
        }
    }

    /** 回归锁定 id 87：updateAppBadge 只抑制 messagetab 的正向计数。 */
    @Test
    public void suppressBadgeOnlyForMessageTabPositiveCount() {
        String messageTab = MsgCenterHooks.MESSAGE_TAB_BADGE_ID;
        assertEquals("与 UI-06 的角标 id 同源，防止两处常量漂移", "messagetab", messageTab);

        assertTrue("messagetab 正计数必须抑制",
                HomeMsgFilterHooks.shouldSuppressBadge(messageTab, 3));
        assertTrue(HomeMsgFilterHooks.shouldSuppressBadge(messageTab, 1));
        assertFalse("count<=0 是清角标，必须放行",
                HomeMsgFilterHooks.shouldSuppressBadge(messageTab, 0));
        assertFalse("count<=0 是清角标，必须放行",
                HomeMsgFilterHooks.shouldSuppressBadge(messageTab, -1));
        assertFalse("其它业务角标（taxi_tab/mine/nearby_tab 等）必须放行",
                HomeMsgFilterHooks.shouldSuppressBadge("taxi_tab", 3));
        assertFalse(HomeMsgFilterHooks.shouldSuppressBadge("mine", 3));
        assertFalse(HomeMsgFilterHooks.shouldSuppressBadge("nearby_tab", 3));
        assertFalse("bizId 为 null 不得抑制", HomeMsgFilterHooks.shouldSuppressBadge(null, 3));
        assertFalse("count 非数值时不得抑制",
                HomeMsgFilterHooks.shouldSuppressBadge(messageTab, "3"));
        assertFalse("count 为 null 时不得抑制",
                HomeMsgFilterHooks.shouldSuppressBadge(messageTab, null));
    }

    /** 回归锁定 id 87：setBadgeDisplay 只跳过 messagetab，其它 bizId 放行。 */
    @Test
    public void skipBadgeDisplayOnlyForMessageTab() {
        String messageTab = MsgCenterHooks.MESSAGE_TAB_BADGE_ID;

        assertTrue(HomeMsgFilterHooks.shouldSkipBadgeDisplay(messageTab));
        assertFalse(HomeMsgFilterHooks.shouldSkipBadgeDisplay("taxi_tab"));
        assertFalse(HomeMsgFilterHooks.shouldSkipBadgeDisplay("mine"));
        assertFalse(HomeMsgFilterHooks.shouldSkipBadgeDisplay("nearby_tab"));
        assertFalse(HomeMsgFilterHooks.shouldSkipBadgeDisplay(null));
        assertFalse(HomeMsgFilterHooks.shouldSkipBadgeDisplay(""));
    }
}
