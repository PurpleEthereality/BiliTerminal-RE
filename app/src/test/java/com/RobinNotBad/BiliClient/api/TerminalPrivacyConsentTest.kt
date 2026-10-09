package com.RobinNotBad.BiliClient.api

import com.RobinNotBad.BiliClient.util.FakeSharedPreferences
import com.RobinNotBad.BiliClient.util.SettingsKeys
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 隐私说明的「同意 → 能不能上报」状态机。
 *
 * 这一段的价值全在**静默错误**上：写错任何一步，App 都能编译、能装、界面全对，
 * 只有一件事变了——用户在没点过同意的情况下被上报了，或者点了同意却什么都没发生。
 * 前者是隐私事故，后者是「功能上了等于没上」，两种都不会崩溃、不会报错。
 *
 * 所以这里逐条钉住三件事：
 *
 * 1. **没同意 = 绝不上报**，包括「开关默认是开」和「有人把开关手动置 true」两种情况；
 * 2. **同意过 = 真能上报**，点完同意不能出现「开关开着但被静默拦住」；
 * 3. **问过就不再问**，无论是同意还是不同意；但**隐私说明改版（版本号 +1）要重新问**。
 */
class TerminalPrivacyConsentTest {

    @Before
    fun setUp() {
        SharedPreferencesUtil.sharedPreferences = FakeSharedPreferences()
    }

    @After
    fun tearDown() {
        SharedPreferencesUtil.sharedPreferences = null
    }

    // ---------- 1. 没同意就绝不上报 ----------

    @Test
    fun freshInstall_cannotReportBeforeConsent() {
        assertTrue("全新安装必须弹一次隐私说明", TerminalApi.needsPrivacyConsent())
        assertFalse("没点同意不算同意", TerminalApi.hasPrivacyConsent())
        // 关键：两个开关的默认值都是 true，但未同意前一律不上报
        assertFalse("未同意时匿名统计必须关闭", TerminalApi.isTelemetryEnabled())
        assertFalse("未同意时崩溃自动上报必须关闭", TerminalApi.isCrashReportAutoEnabled())
    }

    @Test
    fun declined_manualSwitchOnStillCannotReport() {
        TerminalApi.declinePrivacyConsent()
        // 模拟「开关被人为写开」（例如旧存档、或别处代码直接写了 pref）：
        // 只要没有同意记录，仍然不能上报——总闸不依赖任何调用点的自觉。
        SharedPreferencesUtil.putBoolean(SettingsKeys.TELEMETRY_ENABLE, true)
        SharedPreferencesUtil.putBoolean(SettingsKeys.CRASH_REPORT_AUTO, true)
        assertFalse("没有同意记录就不许上报", TerminalApi.isTelemetryEnabled())
        assertFalse("没有同意记录就不许上报", TerminalApi.isCrashReportAutoEnabled())
    }

    // ---------- 2. 同意后真能上报 ----------

    @Test
    fun accept_enablesBothReportsAndUnblocksSwitches() {
        TerminalApi.acceptPrivacyConsent()
        assertTrue(TerminalApi.hasPrivacyConsent())
        assertFalse("同意后不该再弹", TerminalApi.needsPrivacyConsent())
        assertTrue("点完同意不能出现「开着却不上报」", TerminalApi.isTelemetryEnabled())
        assertTrue(TerminalApi.isCrashReportAutoEnabled())
    }

    @Test
    fun accept_thenUserTurnsSwitchOff_staysOff() {
        TerminalApi.acceptPrivacyConsent()
        SharedPreferencesUtil.putBoolean(SettingsKeys.TELEMETRY_ENABLE, false)
        assertFalse("用户明确关掉的开关不能被同意记录顶回来", TerminalApi.isTelemetryEnabled())
        assertTrue("另一个开关不受影响", TerminalApi.isCrashReportAutoEnabled())
        assertFalse("用户关开关不改变「问过了」", TerminalApi.needsPrivacyConsent())
    }

    // ---------- 3. 问过就不再问，但改版要重新问 ----------

    @Test
    fun declined_doesNotAskAgain() {
        TerminalApi.declinePrivacyConsent()
        assertFalse("「不同意」也是一次回答，不该每次启动都再问", TerminalApi.needsPrivacyConsent())
        assertFalse("但同意记录不能因此变成已同意", TerminalApi.hasPrivacyConsent())
    }

    @Test
    fun declined_thenSettingsTurnedOn_isConsentAgain() {
        TerminalApi.declinePrivacyConsent()
        assertFalse(TerminalApi.hasPrivacyConsent())

        // 用户在设置里主动打开统计开关 = 重新同意。
        // 顺序必须照抄 SettingsAdapter：先 putBoolean(id, isChecked)，再回调 onChange，
        // 所以回调里看到的开关已经是 true 了。
        SharedPreferencesUtil.putBoolean(SettingsKeys.TELEMETRY_ENABLE, true)
        TerminalApi.recordPrivacyConsentFromSettings()
        assertTrue("设置里主动打开应当等同于同意", TerminalApi.hasPrivacyConsent())
        assertTrue(TerminalApi.isTelemetryEnabled())
        // 只点了统计一个开关，崩溃上报不该被顺手打开
        assertFalse("不能越权打开用户没点的那个开关", TerminalApi.isCrashReportAutoEnabled())
    }

    @Test
    fun privacyVersionBump_forcesReConfirmation() {
        // 模拟「用户同意的是上一版隐私说明」
        SharedPreferencesUtil.putInt(
            SettingsKeys.PRIVACY_CONSENT_VERSION, TerminalApi.PRIVACY_VERSION - 1
        )
        SharedPreferencesUtil.putInt(
            SettingsKeys.PRIVACY_PROMPTED_VERSION, TerminalApi.PRIVACY_VERSION - 1
        )
        assertTrue("隐私说明改版必须重新征求同意", TerminalApi.needsPrivacyConsent())
        assertFalse("上一版的同意不能算这一版", TerminalApi.hasPrivacyConsent())
        assertFalse("改版未确认期间停止上报", TerminalApi.isTelemetryEnabled())
    }
}
