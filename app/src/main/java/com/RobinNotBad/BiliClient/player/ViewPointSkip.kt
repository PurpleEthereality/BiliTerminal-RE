package com.RobinNotBad.BiliClient.player

/**
 * 自动跳过片头/片尾的纯判定逻辑（无 Android 依赖，可直接 JVM 单测）。
 *
 * 数据来源是 `x/player/wbi/v2` 返回的 `view_points`：B 站会给番剧/影视（以及部分 UGC）标出
 * `type == 1`（片头）和 `type == 2`（片尾）的区间，单位是**秒**。本仓库的
 * [com.RobinNotBad.BiliClient.api.PlayerApi.getViewPoints] 早就把 `type` 解析进了
 * [com.RobinNotBad.BiliClient.model.ViewPoint]，但一直没人用它，所以「自动跳过」只差这一层判定。
 *
 * 判定与取样分离：本文件只回答「该不该跳」，真正的 `seekTo` 由播放页完成。
 */
object ViewPointSkip {

    /** `view_points` 里的片段类型：片头。 */
    const val TYPE_OP = 1

    /** `view_points` 里的片段类型：片尾。 */
    const val TYPE_ED = 2

    /**
     * 单个片段允许自动跳过的最大时长（秒）。
     *
     * 片头/片尾正常都在 3 分钟以内，这里给到 10 分钟只是兜底：万一上游下发一个
     * 「0 秒 → 整集」之类的脏区间，自动跳过会直接把用户甩到片尾，必须挡住。
     */
    const val MAX_SEGMENT_SECONDS = 600.0

    /**
     * 一个可跳过的片段。
     *
     * @param type 片段类型，[TYPE_OP] 或 [TYPE_ED]
     * @param fromSec 片段起点（秒，**含**）
     * @param toSec 片段终点（秒，**不含**）
     */
    data class Segment(val type: Int, val fromSec: Double, val toSec: Double)

    /** 该类型是否值得自动跳过（只有片头/片尾；普通章节看点 type 为 0，不动）。 */
    fun isSkippable(type: Int): Boolean = type == TYPE_OP || type == TYPE_ED

    /** 片段的稳定标识，用于「本集已处理过」的去重。 */
    fun keyOf(segment: Segment): String =
        segment.type.toString() + "@" + segment.fromSec + "-" + segment.toSec

    /**
     * 从原始 `view_points` 里筛出可跳过的片段。
     *
     * 丢弃：非片头/片尾类型、区间反向或为空（`toSec <= fromSec`）、时长超过 [MAX_SEGMENT_SECONDS] 的脏数据。
     * 结果按起点升序，方便 [segmentAt] 用「第一个命中」的语义。
     */
    fun buildSegments(raw: List<Segment>): List<Segment> =
        raw.filter {
            isSkippable(it.type) &&
                it.toSec > it.fromSec &&
                (it.toSec - it.fromSec) <= MAX_SEGMENT_SECONDS
        }.sortedBy { it.fromSec }

    /**
     * [positionSec] 落在哪个片段里。
     *
     * 区间是**左闭右开** `[fromSec, toSec)`：跳过之后播放位置正好落在 `toSec`，
     * 若把右端也算作「片段内」，下一轮判定会再次命中，形成反复跳。
     *
     * @param segments 必须是 [buildSegments] 排好序的结果
     */
    fun segmentAt(positionSec: Double, segments: List<Segment>): Segment? {
        if (positionSec.isNaN() || positionSec < 0) return null
        return segments.firstOrNull { positionSec >= it.fromSec && positionSec < it.toSec }
    }

    /**
     * 当前是否应当自动跳过；返回需要跳过的片段，不需要则返回 null。
     *
     * @param handled 本次播放里已经处理过的片段 key（自动跳过的、以及用户手动拖进去的），只跳一次
     */
    fun shouldSkip(positionSec: Double, segments: List<Segment>, handled: Set<String>): Segment? {
        val segment = segmentAt(positionSec, segments) ?: return null
        return if (handled.contains(keyOf(segment))) null else segment
    }

    /**
     * 用户**手动**把进度拖到 [positionSec] 时，应当顺手标成「已处理」的片段 key。
     *
     * 用户主动拖进片头，说明他就是想看看片头，不能下一轮定时器又把他弹走；返回 null 表示无需处理。
     */
    fun keyForManualSeek(positionSec: Double, segments: List<Segment>, handled: Set<String>): String? {
        val segment = segmentAt(positionSec, segments) ?: return null
        val key = keyOf(segment)
        return if (handled.contains(key)) null else key
    }
}
