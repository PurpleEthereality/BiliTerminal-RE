package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateRelease]（发行版更新信息解析）的纯 JVM 单测。
 *
 * 钉住三件容易静默出错的事：
 * 1. 元数据缺失时必须能按 tag 推出版本号 —— 推错会让客户端「永远收不到更新」；
 * 2. ABI 选包必须按**精确文件名**匹配 —— Gitee 会自动往发行版里塞 `{tag}.zip` 源码归档，
 *    任何"取第一个附件"的写法都会下到源码压缩包；
 * 3. forceUpdate 默认 false，不能被脏数据误判成强制更新。
 */
class UpdateReleaseTest {

    private fun assets(vararg names: String) =
        names.map { UpdateRelease.ReleaseAsset(it, "https://example.test/$it") }

    // ==================== 元数据解析 ====================

    @Test
    fun parseMeta_readsCiMetadata() {
        val body = """
            ## 更新内容

            修了一堆东西。

            ### APK 校验值（MD5）

            ```
            abc  app-universal-release.apk
            ```

            <!-- update: versionCode=2610020 versionName=26.10.02 forceUpdate=false -->
        """.trimIndent()
        val meta = UpdateRelease.parseMeta(body, "26.10.02")
        assertEquals(2610020, meta.versionCode)
        assertEquals("26.10.02", meta.versionName)
        assertFalse(meta.forceUpdate)
    }

    @Test
    fun parseMeta_readsForceUpdate() {
        val meta = UpdateRelease.parseMeta("<!-- update: versionCode=2610030 versionName=26.10.03 forceUpdate=true -->", "26.10.03")
        assertEquals(2610030, meta.versionCode)
        assertTrue("forceUpdate=true 必须被识别成强制更新", meta.forceUpdate)
    }

    @Test
    fun parseMeta_missingMetadata_fallsBackToTag() {
        val meta = UpdateRelease.parseMeta("## 只写了更新说明，没有元数据", "26.10.02")
        assertEquals("没有元数据时应按 tag 推算 versionCode", 2610020, meta.versionCode)
        assertEquals("26.10.02", meta.versionName)
        assertFalse("没有元数据时不能是强制更新", meta.forceUpdate)
    }

    @Test
    fun parseMeta_nullBody_fallsBackToTag() {
        assertEquals(2610020, UpdateRelease.parseMeta(null, "26.10.02").versionCode)
        assertEquals(0, UpdateRelease.parseMeta(null, null).versionCode)
    }

    @Test
    fun parseMeta_brokenMetadata_fallsBackToTag() {
        // 元数据里 versionCode 是垃圾值：不能把 0 当成"版本号"，应回落到 tag 推算
        val meta = UpdateRelease.parseMeta("<!-- update: versionCode=abc forceUpdate=false -->", "26.10.02")
        assertEquals(2610020, meta.versionCode)
    }

    // ==================== tag 推算 ====================

    @Test
    fun deriveVersionCode_standardTag() {
        assertEquals(2610020, UpdateRelease.deriveVersionCode("26.10.02"))
        assertEquals(2609070, UpdateRelease.deriveVersionCode("26.09.07"))
    }

    @Test
    fun deriveVersionCode_tolerantForms() {
        assertEquals("带 v 前缀也要认", 2610020, UpdateRelease.deriveVersionCode("v26.10.02"))
        assertEquals("前导零可省", 2610020, UpdateRelease.deriveVersionCode("26.10.2"))
    }

    @Test
    fun deriveVersionCode_invalidReturnsZero() {
        assertEquals(0, UpdateRelease.deriveVersionCode("v1.2"))
        assertEquals(0, UpdateRelease.deriveVersionCode("nightly"))
        assertEquals(0, UpdateRelease.deriveVersionCode(""))
        assertEquals(0, UpdateRelease.deriveVersionCode(null))
        assertEquals(0, UpdateRelease.deriveVersionCode("26.10.02-rc1"))
        assertEquals(0, UpdateRelease.deriveVersionCode("199.10.02"))
    }

    // ==================== 去掉元数据 ====================

    @Test
    fun stripMeta_removesCommentAndTrailingBlank() {
        val body = "## 更新内容\n\n- 一条\n\n<!-- update: versionCode=2610020 forceUpdate=false -->\n"
        assertEquals("## 更新内容\n\n- 一条", UpdateRelease.stripMeta(body))
    }

    @Test
    fun stripMeta_keepsBodyWhenNoMeta() {
        assertEquals("## 更新内容", UpdateRelease.stripMeta("## 更新内容\n\n"))
    }

    // ==================== ABI 选包 ====================

    @Test
    fun pickApkUrl_matchesDeviceAbi() {
        val list = assets(
            "app-arm64-v8a-release.apk",
            "app-armeabi-v7a-release.apk",
            "app-universal-release.apk",
            "app-x86-release.apk"
        )
        assertTrue(UpdateRelease.pickApkUrl(list, listOf("arm64-v8a"))!!.endsWith("app-arm64-v8a-release.apk"))
        assertTrue(UpdateRelease.pickApkUrl(list, listOf("armeabi-v7a"))!!.endsWith("app-armeabi-v7a-release.apk"))
        assertTrue(UpdateRelease.pickApkUrl(list, listOf("x86"))!!.endsWith("app-x86-release.apk"))
        // 设备 ABI 列表有优先顺序：第一个有包的先用
        assertTrue(
            UpdateRelease.pickApkUrl(list, listOf("arm64-v8a", "armeabi-v7a"))!!.endsWith("app-arm64-v8a-release.apk")
        )
    }

    @Test
    fun pickApkUrl_fallsBackToUniversal() {
        val list = assets("app-universal-release.apk", "app-arm64-v8a-release.apk")
        assertTrue(
            "设备 ABI 没有分包时必须回落到 universal",
            UpdateRelease.pickApkUrl(list, listOf("mips"))!!.endsWith("app-universal-release.apk")
        )
        // 只有 universal 时也能用
        assertTrue(
            UpdateRelease.pickApkUrl(assets("app-universal-release.apk"), listOf("arm64-v8a"))!!
                .endsWith("app-universal-release.apk")
        )
    }

    @Test
    fun pickApkUrl_partialAssets_returnsNullSoCallerFallsBackToAnotherSource() {
        // Gitee 上传很慢，可能只传成功了一部分（例如只有 arm64）。
        // 32 位设备此时必须拿到 null，好让调用方去用 GitHub 源 ——
        // 绝不能把 arm64 的包当成"随便一个 -release.apk"下下来：装了也起不来。
        val onlyArm64 = assets("app-arm64-v8a-release.apk", "26.10.03.zip")
        assertNull(UpdateRelease.pickApkUrl(onlyArm64, listOf("armeabi-v7a")))
        assertNull(UpdateRelease.pickApkUrl(onlyArm64, listOf("x86")))
        // 而 arm64 设备本来就该拿到它
        assertTrue(
            UpdateRelease.pickApkUrl(onlyArm64, listOf("arm64-v8a"))!!.endsWith("app-arm64-v8a-release.apk")
        )
    }

    @Test
    fun pickApkUrl_neverPicksGiteeSourceArchive() {
        // Gitee 会自动附加这两个源码归档；它们既不是 APK，也绝不能被当成安装包
        val list = assets(
            "26.10.02.zip",
            "26.10.02.tar.gz",
            "app-arm64-v8a-release.apk"
        )
        val url = UpdateRelease.pickApkUrl(list, listOf("arm64-v8a"))
        assertTrue(url!!.endsWith("app-arm64-v8a-release.apk"))

        val onlyArchives = assets("26.10.02.zip", "26.10.02.tar.gz")
        assertNull("只有源码归档时应返回 null，而不是把 zip 当安装包", UpdateRelease.pickApkUrl(onlyArchives, listOf("arm64-v8a")))
    }

    @Test
    fun pickApkUrl_emptyAssets() {
        assertNull(UpdateRelease.pickApkUrl(emptyList(), listOf("arm64-v8a")))
    }
}
