package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SettingsKeys] 里「已经在线上跑过、改了就静默丢用户数据」的 key 名钉死测试。
 *
 * 为什么值得写：这些 key 的字符串就是磁盘协议 —— 用户升级后读的还是同一个
 * SharedPreferences 文件。谁把常量后面的字面量改了（比如手滑改成 "player_id"），
 * 编译、单测、安装全都正常，只有用户那边的设置会悄悄回到默认值，且不会有任何报错。
 * 26.10.04 批次 4（E4）把散落的字面量收敛到常量后，这层保护才有意义。
 */
class SettingsKeysTest {

    @Test
    fun playerKey_isLocked() {
        assertEquals("player", SettingsKeys.PLAYER)
    }

    @Test
    fun playQnKey_isLocked() {
        assertEquals("play_qn", SettingsKeys.PLAY_QN)
    }

    @Test
    fun bangumiUpdateNotifyKey_isLocked() {
        // C16 新增：改了会让"追番更新提醒"开关静默失效（用户关不掉 / 开不了）
        assertEquals("bangumi_update_notify_enable", SettingsKeys.BANGUMI_UPDATE_NOTIFY_ENABLE)
    }
}
