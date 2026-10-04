package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * 更新安装包校验（26.10.04 批次 4 / E6）。
 *
 * 为什么需要它：`UpdateManager` 以前下载完 APK 就直接丢给系统安装器，
 * 中间不做任何检查。下载链路本身走 https，但一旦文件被替换/截断/串包，
 * 用户看到的是系统安装器一句笼统的「解析软件包时出现问题」，既不知道原因，
 * 也没机会在装之前发现包是别人的。
 *
 * 这里只做两件**不需要密钥、也不需要发布侧配合**的检查：
 * 1. **包名必须与当前应用一致** —— 拦住「下载到的其实是另一个应用」；
 * 2. **签名必须与当前已安装应用一致** —— 攻击者没有私钥就伪造不出来。
 *
 * 边界说明（别把它当银弹）：
 * - 系统的安装器本身也会拒绝「同包名不同签名」的包，本校验的价值是**早失败 + 说清原因**；
 * - 它防不住「同一个签名者发布的坏包」（那属于发布侧被攻破，客户端无解）；
 * - 哈希校验（MD5/SHA-256 写进 Release 元数据）没有采纳：元数据与安装包来自同一个
 *   响应，能改包的人也能改元数据，对真正的中间人几乎没有增量价值。
 *
 * 纯逻辑（[isSamePackage] / [isSameSignature] / [signatureHex]）与 Android 取签名
 * （[verify]）分开，前者由 JVM 单测覆盖。
 */
object ApkVerifier {

    /** 校验结果：`ok` 为 false 时 [message] 是可直接展示给用户的中文原因。 */
    data class Result(val ok: Boolean, val message: String = "")

    private val HEX = "0123456789abcdef".toCharArray()

    /** 包名是否一致。apk 解析失败（`actual` 为 null/空）一律视为不一致。 */
    fun isSamePackage(expected: String, actual: String?): Boolean =
        !actual.isNullOrEmpty() && actual == expected

    /**
     * 签名集合是否一致：比较的是证书字节的十六进制串集合，**与顺序无关、忽略大小写**。
     * 任一侧为空都判为不一致（读不到签名时必须失败关闭，不能放行）。
     */
    fun isSameSignature(expected: List<String>, actual: List<String>): Boolean {
        if (expected.isEmpty() || actual.isEmpty()) return false
        return expected.map { it.lowercase() }.toSortedSet() ==
                actual.map { it.lowercase() }.toSortedSet()
    }

    /** 证书字节转小写十六进制（每个字节固定两位，含前导零）。 */
    fun signatureHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 校验本地 APK：包名与签名都要与当前已安装应用一致。
     *
     * 只做读取与比较，不抛异常——失败一律转成 [Result]，由调用方决定提示与是否删包。
     */
    @Suppress("DEPRECATION")
    fun verify(context: Context, apkFile: File): Result {
        if (!apkFile.isFile || apkFile.length() == 0L) {
            return Result(false, "安装包不存在或为空")
        }

        val packageManager = context.packageManager

        val archive = try {
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
        } catch (e: Exception) {
            null
        } ?: return Result(false, "安装包无法解析，文件可能已损坏")

        if (!isSamePackage(context.packageName, archive.packageName)) {
            return Result(false, "安装包包名不符（${archive.packageName}），已拒绝安装")
        }

        val expectedSignatures = try {
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.map { signatureHex(it.toByteArray()) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (expectedSignatures.isEmpty()) {
            return Result(false, "无法读取当前应用签名，为安全起见已拒绝安装")
        }

        val actualSignatures = archive.signatures?.map { signatureHex(it.toByteArray()) } ?: emptyList()
        if (!isSameSignature(expectedSignatures, actualSignatures)) {
            return Result(false, "安装包签名与当前应用不一致，已拒绝安装")
        }

        return Result(true)
    }
}
