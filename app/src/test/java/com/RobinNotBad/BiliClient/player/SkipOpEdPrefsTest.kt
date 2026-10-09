package com.RobinNotBad.BiliClient.player

import com.RobinNotBad.BiliClient.util.FakeSharedPreferences
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「自动跳过片头片尾」的默认值与引导判据单测。
 *
 * 钉住三件**不会报错、只会静默错**的事：
 *
 * 1. **默认必须是开启**——用户的原话是「默认不是开启吗，这边默认关闭了」。
 *    这个默认值原先散在四处字面量里，改漏一处的症状是「设置页显示开着、播放器却不跳」。
 * 2. **老用户显式关过的必须还是关的**——升级覆盖安装后不能把人家关掉的开关又打开。
 *    靠的是 `getBoolean(key, def)` 的语义（键不存在才用 def），这条测试就是它的护栏。
 * 3. **引导判据与开关解耦**——原实现「开关开着就 return」，默认改成开启之后
 *    这条引导（真机清单第 200 条）会永远不出现，变成死代码。
 * 4. **撤回必须与存档无关**（26.10.10 新增）——把功能关掉时只改 [SkipOpEdPrefs.DEFAULT_ENABLED]
 *    是**无效**的：默认值只决定「键不存在时读什么」，老用户存档里早就是 `true` 了。
 *    护栏是 [SkipOpEdPrefs.FEATURE_ENABLED] 这个独立否决位，见下面「紧急撤回」一节。
 */
class SkipOpEdPrefsTest {

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
        assertTrue("自动跳过片头片尾必须默认开启", SkipOpEdPrefs.DEFAULT_ENABLED)
    }

    @Test
    fun 从未存过时读取默认值得到开启() {
        // 新装用户：SP 里没有这个键
        val enabled = SharedPreferencesUtil.getBoolean(
            SettingsKeys.PLAYER_SKIP_OP_ED, SkipOpEdPrefs.DEFAULT_ENABLED
        )
        assertTrue("键不存在时应取默认值 true", enabled)
    }

    @Test
    fun 老用户显式关过之后仍然是关闭() {
        // 升级覆盖安装：用户上一版手动关掉了它
        SharedPreferencesUtil.putBoolean(SettingsKeys.PLAYER_SKIP_OP_ED, false)
        val enabled = SharedPreferencesUtil.getBoolean(
            SettingsKeys.PLAYER_SKIP_OP_ED, SkipOpEdPrefs.DEFAULT_ENABLED
        )
        assertFalse("用户显式关过的设置不能被默认值覆盖掉", enabled)
    }

    @Test
    fun 老用户显式开过之后仍然是开启() {
        SharedPreferencesUtil.putBoolean(SettingsKeys.PLAYER_SKIP_OP_ED, true)
        val enabled = SharedPreferencesUtil.getBoolean(
            SettingsKeys.PLAYER_SKIP_OP_ED, SkipOpEdPrefs.DEFAULT_ENABLED
        )
        assertTrue(enabled)
    }

    // ---------- 26.10.10 紧急撤回：功能总开关 ----------

    @Test
    fun 功能已撤回时总开关必须是关闭() {
        assertFalse(
            "26.10.10 起「自动跳过片头片尾」已紧急撤回，FEATURE_ENABLED 必须是 false",
            SkipOpEdPrefs.FEATURE_ENABLED
        )
    }

    @Test
    fun 撤回期间存档里写着开启也不能跳() {
        // 这是这次撤回**最容易被改错**的地方：只把 DEFAULT_ENABLED 改成 false 是没用的，
        // 默认值只管「键不存在时读什么」，老用户存档里早就存着 true 了。
        // 所以撤回必须落在 FEATURE_ENABLED 这个与存档无关的否决位上。
        SharedPreferencesUtil.putBoolean(SettingsKeys.PLAYER_SKIP_OP_ED, true)
        val prefValue = SharedPreferencesUtil.getBoolean(
            SettingsKeys.PLAYER_SKIP_OP_ED, SkipOpEdPrefs.DEFAULT_ENABLED
        )
        assertTrue("前置条件：存档确实读出了 true，否则这条测试什么都没验证", prefValue)
        assertFalse("撤回期间，即使存档为 true 也不能生效", SkipOpEdPrefs.isEnabled(prefValue))
    }

    @Test
    fun 撤回期间两种存档取值都判定为不启用() {
        assertFalse(SkipOpEdPrefs.isEnabled(true))
        assertFalse(SkipOpEdPrefs.isEnabled(false))
    }

    // ---------- 引导判据 ----------

    @Test
    fun 没引导过且有片段时要引导() {
        // 这是默认开启之后新增的行为：开关开着、但用户从没见过引导
        assertTrue(
            "默认开启后仍必须引导一次，否则用户不知道进度为什么自己动了",
            SkipOpEdPrefs.shouldShowGuide(hasSegments = true, alreadyGuided = false)
        )
    }

    @Test
    fun 引导过之后不再引导() {
        assertFalse(
            "每集都弹会很烦",
            SkipOpEdPrefs.shouldShowGuide(hasSegments = true, alreadyGuided = true)
        )
    }

    @Test
    fun 视频没有片头片尾时不引导() {
        assertFalse(
            "没有可跳片段还提示「可以自动跳过」是假承诺",
            SkipOpEdPrefs.shouldShowGuide(hasSegments = false, alreadyGuided = false)
        )
        assertFalse(SkipOpEdPrefs.shouldShowGuide(hasSegments = false, alreadyGuided = true))
    }

    // ---------- 文案随开关状态切换 ----------

    @Test
    fun 开关已开时文案是提示而不是劝开() {
        val text = SkipOpEdPrefs.guideText(enabled = true)
        assertTrue("已开启时应该说「已自动跳过」", text.contains("已自动跳过"))
        assertFalse("已经开着还劝用户开启是自相矛盾", text.contains("可以自动跳过"))
        assertEquals("知道了", SkipOpEdPrefs.guideActionText(enabled = true))
    }

    @Test
    fun 开关未开时文案是劝开() {
        val text = SkipOpEdPrefs.guideText(enabled = false)
        assertEquals("这个视频有片头片尾，可以自动跳过", text)
        assertEquals("开启", SkipOpEdPrefs.guideActionText(enabled = false))
    }
}
