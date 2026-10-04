package com.RobinNotBad.BiliClient.util

import android.content.Context
import com.RobinNotBad.BiliClient.api.BangumiApi
import com.RobinNotBad.BiliClient.model.FollowedBangumi
import org.json.JSONObject

/**
 * 追番更新提醒（26.10.04 批次 5 的 C16）。
 *
 * 设计取舍（用户拍板）：
 * - **只在打开应用时检查，不做后台定时**：项目没有也不引入 WorkManager / AlarmManager，
 *   触发点是 `BiliTerminal.onCreate` 里紧挨着未读检查的那一段。
 * - **判据是「同一部番的 `new_ep.id` 变了」**，不是「总集数变了」也不是服务端的 `is_new`
 *   （后者会被用户在别的客户端看过之后清掉）。首次检查只写下快照、不提醒，否则一装上
 *   就会把全部追番报成"更新"。
 * - **快照只在成功拉取后写回**：拉取失败就保持旧快照，否则下次会把老集当新集重复提醒。
 *
 * 这里只放纯逻辑 + 一个入口，网络与通知分别由 `BangumiApi` / `MsgNotifier` 负责。
 */
object BangumiUpdateChecker {

    /**
     * 快照 JSON：`{"media_id": new_ep_id, ...}`。
     *
     * 用 JSON 而不是 SharedPreferences 的多条 String 是为了"整份替换"语义清晰：
     * 取消追番后旧条目要跟着消失，否则重新追同一部番、期间又更新过，会被误报成更新。
     */
    fun snapshotJson(items: List<FollowedBangumi>): String {
        val json = JSONObject()
        for (item in items) {
            json.put(item.mediaId.toString(), item.newEpId)
        }
        return json.toString()
    }

    /**
     * 容错解析快照：空串 / 坏 JSON 一律当"没有快照"（等于首次检查），
     * 键不是数字的条目直接跳过。
     */
    fun parseSnapshot(json: String?): MutableMap<Long, Long> {
        val map = mutableMapOf<Long, Long>()
        if (json.isNullOrEmpty()) return map
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val mediaId = key.toLongOrNull() ?: continue
                map[mediaId] = obj.optLong(key, 0L)
            }
        } catch (e: Exception) {
            // 坏快照只能重建，绝不能因此崩在启动路径上
            return mutableMapOf()
        }
        return map
    }

    /**
     * 找出真正"更新了"的番：
     * - 快照里没有的（这次新追的番）不算更新，只是第一次记录；
     * - `newEpId` 没变不算；
     * - 当前 `newEpId <= 0`（还没开播 / 接口没给 new_ep）不算，避免把"待开播"当更新。
     */
    fun findUpdated(
        stored: Map<Long, Long>,
        current: List<FollowedBangumi>
    ): List<FollowedBangumi> {
        val updated = ArrayList<FollowedBangumi>()
        for (item in current) {
            if (item.newEpId <= 0) continue
            val old = stored[item.mediaId] ?: continue
            if (old != item.newEpId) updated.add(item)
        }
        return updated
    }

    /**
     * 入口：拉一次追番列表，与快照比对，有更新就发一条通知，然后把当前快照写回。
     *
     * 未登录时 `BangumiApi.getFollowedBangumi()` 返回空列表——此时 **什么都不做**，
     * 既不发通知也不覆盖快照（账号退出登录不该让快照归零）。
     */
    fun checkAndNotify(context: Context) {
        val stored = parseSnapshot(
            SharedPreferencesUtil.getString(SharedPreferencesUtil.BANGUMI_UPDATE_SNAPSHOT, "")
        )
        val current = BangumiApi.getFollowedBangumi()

        if (current.isEmpty()) {
            Logu.i("BangumiUpdateChecker", "追番列表为空（未登录或没有追番），跳过检查")
            return
        }

        if (stored.isEmpty()) {
            SharedPreferencesUtil.putString(
                SharedPreferencesUtil.BANGUMI_UPDATE_SNAPSHOT, snapshotJson(current)
            )
            Logu.i("BangumiUpdateChecker", "首次检查，记录 ${current.size} 部追番")
            return
        }

        val updated = findUpdated(stored, current)
        if (updated.isNotEmpty()) {
            MsgNotifier.notifyBangumiUpdates(context, updated.map { it.title })
            Logu.i("BangumiUpdateChecker", "${updated.size} 部追番有更新")
        }

        SharedPreferencesUtil.putString(
            SharedPreferencesUtil.BANGUMI_UPDATE_SNAPSHOT, snapshotJson(current)
        )
    }
}
