package com.RobinNotBad.BiliClient.util

import org.json.JSONException
import org.json.JSONObject

/**
 * 「特殊登录」登录信息的纯解析 / 校验逻辑，与 UI 无关，便于 JVM 单测。
 *
 * 登录信息由另一台设备导出，格式固定为 JSON：
 * ```
 * {"cookies":"...","refresh_token":"...","access_key":"..."}
 * ```
 *
 * 校验规则（任一不满足即失败）：
 * 1. 必须是合法 JSON 对象；
 * 2. 必须带 `cookies` 字段且非空；
 * 3. 必须带 `refresh_token` 字段；
 * 4. `cookies` 里必须能解析出 `DedeUserID`（否则连登录的是谁都不知道）。
 *
 * `access_key` 是可选字段，缺失时返回 null，调用方不要写入空值。
 */
object SpecialLoginParser {

    sealed class Result {
        data class Success(
            val cookies: String,
            val refreshToken: String,
            /** 可选字段，缺失时为 null */
            val accessKey: String?,
            val mid: Long,
        ) : Result()

        data class Failure(val message: String) : Result()
    }

    fun parse(raw: String?): Result {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Result.Failure("没有读取到登录信息，剪贴板是空的")

        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return Result.Failure("登录信息不是合法的 JSON，请检查剪贴板内容")
        }

        if (!json.has("cookies")) return Result.Failure("登录信息缺少 cookies 字段")
        val cookies = json.optString("cookies").trim()
        if (cookies.isEmpty()) return Result.Failure("登录信息里的 cookies 是空的")

        if (!json.has("refresh_token")) return Result.Failure("登录信息缺少 refresh_token 字段")

        val mid = NetWorkUtil.getInfoFromCookie("DedeUserID", cookies).toLongOrNull()
            ?: return Result.Failure("Cookie 中缺少用户 ID（DedeUserID），请检查复制的内容是否完整")

        return Result.Success(
            cookies = cookies,
            refreshToken = json.optString("refresh_token"),
            accessKey = if (json.has("access_key")) json.optString("access_key") else null,
            mid = mid,
        )
    }
}
