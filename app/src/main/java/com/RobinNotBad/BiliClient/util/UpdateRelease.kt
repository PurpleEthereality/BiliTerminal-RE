package com.RobinNotBad.BiliClient.util

/**
 * 发行版（Release）更新信息的纯解析逻辑（无 Android 依赖，可直接 JVM 单测）。
 *
 * Gitee 与 GitHub 的 Release JSON 在 `tag_name` / `body` / `assets[].name` /
 * `assets[].browser_download_url` 这几个字段上形状一致，所以两边共用一份解析。
 *
 * 版本号来源有两条路，优先元数据、其次按 tag 推算：
 * 1. Release 说明末尾由 CI 写入的机器可读元数据
 *    `<!-- update: versionCode=2610020 versionName=26.10.02 forceUpdate=false -->`；
 * 2. 元数据缺失时按 tag 推算（`26.10.02` → `2610020`，即 YYMMDD 后补一个 0）。
 */
object UpdateRelease {

    /** 一个发行版附件（只用得到名字与下载直链）。 */
    data class ReleaseAsset(val name: String, val url: String)

    /** 从发行版里解析出的版本信息。 */
    data class Meta(val versionCode: Int, val versionName: String?, val forceUpdate: Boolean)

    /** 与 CI 写入格式严格对应，见 `.github/workflows/build-release.yml` 的「计算发行 tag 与发布说明」。 */
    private val META_REGEX = Regex("<!--\\s*update:\\s*(.*?)\\s*-->")

    /**
     * 解析 Release 说明里的元数据；缺失或不可解析时回落到 tag 推算。
     *
     * @param body Release 说明（可能为 null）
     * @param tag  发行 tag，如 `26.10.02`
     */
    fun parseMeta(body: String?, tag: String?): Meta {
        val tokens = HashMap<String, String>()
        val match = META_REGEX.find(body.orEmpty())
        if (match != null) {
            for (piece in match.groupValues[1].split(' ', '\t', '\n', '\r')) {
                val kv = piece.split('=', limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) {
                    tokens[kv[0].trim()] = kv[1].trim()
                }
            }
        }

        val codeFromMeta = tokens["versionCode"]?.toIntOrNull()
        val versionCode = if (codeFromMeta != null && codeFromMeta > 0) codeFromMeta else deriveVersionCode(tag)
        val versionName = tokens["versionName"]?.takeIf { it.isNotBlank() } ?: tag
        val forceUpdate = tokens["forceUpdate"]?.lowercase() == "true"
        return Meta(versionCode, versionName, forceUpdate)
    }

    /** 去掉元数据注释，得到干净的发布说明（给更新弹窗展示用）。 */
    fun stripMeta(body: String?): String = META_REGEX.replace(body.orEmpty(), "").trim()

    /**
     * 按 tag 推算 versionCode：`26.10.02` → `2610020`（YYMMDD 后补一个 0，见发版约定）。
     *
     * 允许前缀 `v`、允许省略前导零；不足三段或含非数字时返回 0（调用方据此报错，而不是当成"没有更新"）。
     */
    fun deriveVersionCode(tag: String?): Int {
        val cleaned = tag.orEmpty().trim().removePrefix("v").removePrefix("V")
        val parts = cleaned.split('.')
        if (parts.size < 3) return 0
        val nums = parts.take(3).map { it.trim().toIntOrNull() ?: return 0 }
        if (nums.any { it < 0 || it > 99 }) return 0
        val yymmdd = nums[0] * 10000 + nums[1] * 100 + nums[2]
        return yymmdd * 10
    }

    /**
     * 按设备 ABI 选要下载的 APK 直链。
     *
     * 发版产物是 `splits.abi` 打出来的分包 + universal：
     * `app-arm64-v8a-release.apk` / `app-armeabi-v7a-release.apk` / `app-x86-release.apk` /
     * `app-universal-release.apk`。按 [supportedAbis] 的**设备优先顺序**取第一个命中的分包，
     * 都没有再用 universal，最后兜底取任意 `*-release.apk`。
     *
     * 注意 Gitee 会往发行版里塞 `{tag}.zip` / `{tag}.tar.gz` 两个**源码归档**（不是我们上传的），
     * 所以这里一律按精确文件名匹配，绝不能用"第一个附件"这种写法。
     */
    fun pickApkUrl(assets: List<ReleaseAsset>, supportedAbis: List<String>): String? {
        val byName = HashMap<String, String>()
        for (a in assets) byName[a.name] = a.url

        for (abi in supportedAbis) {
            val candidate = when (abi.lowercase()) {
                "arm64-v8a" -> "app-arm64-v8a-release.apk"
                "armeabi-v7a", "armeabi" -> "app-armeabi-v7a-release.apk"
                "x86", "x86_64" -> "app-x86-release.apk"
                else -> null
            } ?: continue
            byName[candidate]?.let { return it }
        }
        byName["app-universal-release.apk"]?.let { return it }
        return assets.firstOrNull { it.name.endsWith("-release.apk") }?.url
    }
}
