package com.RobinNotBad.BiliClient.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「长按打开操作面板」开关的默认值与「面板里要不要列出复制项」的判据。
 *
 * 钉住三件**不会崩、只会静默错**的事：
 *
 * 1. **默认必须是开启**——用户提的就是「复制放入弹出里 + 给个选项启用长按打开面板」，
 *    若默认关着，改版在他手上等于没生效，而且没有任何报错。
 * 2. **老用户显式关过的必须还是关的**——升级覆盖安装不能把人家关掉的开关又打开，
 *    靠的是 `getBoolean(key, def)` 的语义（键不存在才用 def）。
 * 3. **空正文不能列出「复制」**——点了只会看到一个空界面，既不报错也没有反馈。
 */
class LongPressPrefsTest {

    @Before
    fun setUp() {
        SharedPreferencesUtil.sharedPreferences = FakeSharedPreferences()
    }

    @After
    fun tearDown() {
        SharedPreferencesUtil.sharedPreferences = null
    }

    // ---------- 默认值 ----------

    @Test
    fun 默认值是开启() {
        assertTrue("长按打开操作面板必须默认开启", LongPressPrefs.DEFAULT_ENABLED)
    }

    @Test
    fun 从未存过时得到开启() {
        // 新装用户：SP 里没有这个键
        assertTrue("键不存在时应取默认值 true", LongPressPrefs.isEnabled())
    }

    @Test
    fun 老用户显式关过之后仍然是关闭() {
        SharedPreferencesUtil.putBoolean(SettingsKeys.LONG_PRESS_PANEL_ENABLE, false)
        assertFalse(
            "用户显式关过的设置不能被默认值覆盖掉",
            LongPressPrefs.isEnabled()
        )
    }

    @Test
    fun 老用户显式开过之后仍然是开启() {
        SharedPreferencesUtil.putBoolean(SettingsKeys.LONG_PRESS_PANEL_ENABLE, true)
        assertTrue(LongPressPrefs.isEnabled())
    }

    /** 键名不能被改掉：改了等于把老用户的设置读成「没设过」，默认值会把他关掉的开关重新打开。 */
    @Test
    fun 键名是稳定的() {
        assertEquals("long_press_panel_enable", SettingsKeys.LONG_PRESS_PANEL_ENABLE)
    }

    // ---------- 面板里要不要列出「复制」 ----------

    @Test
    fun 复制开着且正文非空时列出复制项() {
        assertTrue(
            "这是改版后的主路径：长按弹面板、复制是其中一项",
            LongPressPrefs.shouldOfferCopy(copyEnabled = true, text = "这是一条评论")
        )
    }

    @Test
    fun 复制开着但正文为空时不列出复制项() {
        assertFalse(
            "空正文点复制只会进到一个空界面，不如不给这个入口",
            LongPressPrefs.shouldOfferCopy(copyEnabled = true, text = "")
        )
        assertFalse(LongPressPrefs.shouldOfferCopy(copyEnabled = true, text = null))
    }

    @Test
    fun 复制关着时一律不列出复制项() {
        // 用户已经把「长按复制」关掉了，面板里再出现「复制」就是自相矛盾
        assertFalse(LongPressPrefs.shouldOfferCopy(copyEnabled = false, text = "有正文"))
        assertFalse(LongPressPrefs.shouldOfferCopy(copyEnabled = false, text = ""))
        assertFalse(LongPressPrefs.shouldOfferCopy(copyEnabled = false, text = null))
    }

    @Test
    fun 只有空串才算没有内容() {
        // 正文可能只有空白/换行，但那仍然是有内容、可复制的，不该被当成空
        assertTrue(LongPressPrefs.shouldOfferCopy(copyEnabled = true, text = " "))
        assertTrue(LongPressPrefs.shouldOfferCopy(copyEnabled = true, text = "\n"))
    }
}
