package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.api.TerminalApi

/**
 * 匿名使用统计：唯一安装数 + 日活。
 *
 * <p>上报的内容**只有**：随机 install_id、版本号、机型（brand/device/ABI）、系统 SDK。
 * 不含账号、不含 Cookie、不含任何设备唯一号。服务端按 (install_id, 当天) 去重，
 * 所以「一天内开几次」与「一天内有几个人开」是同一份数据，客户端只负责一天发一次。
 *
 * <p>默认开启，用户可在「设置 - 关于与帮助」里关掉（关掉后连 install_id 都不会再发）。
 * 这是用户拍板的取舍：默认开能反映真实留存，代价是必须把
 * 「绝不收集任何隐私信息」那两句文案改准确——文案已经同步改过了，
 * 见 `strings.xml` 的 `about_to_uncle` 与 `text_setup_introduction`。
 *
 * <p>但「默认开」之上还有一道闸：[TerminalApi.isTelemetryEnabled] 会先要求
 * 用户就当前版本的隐私说明点过「同意」（启动时弹一次，见 SplashActivity）。
 * 所以这里的判断同时覆盖了「用户关掉了开关」和「用户还没同意」，不需要重复判。
 *
 * <p>线程：只做网络与 SharedPreferences，**必须**在子线程调用（SplashActivity 里走
 * [CenterThreadPool]）。任何失败都吞掉——统计失败绝不能影响用户启动。
 */
object TelemetryReporter {

    /**
     * 若今天还没上报过就上报一次。
     *
     * <p>「今天」用客户端本地日期：[TerminalApi.dayKey]。只有**成功**才写回日期，
     * 这样断网启动一次不会把当天剩下的机会吃掉——下次启动会重试。
     */
    @JvmStatic
    fun reportIfNeeded() {
        try {
            if (!TerminalApi.isTelemetryEnabled()) return

            val today = TerminalApi.dayKey(System.currentTimeMillis())
            if (today == SharedPreferencesUtil.getString(SettingsKeys.TELEMETRY_LAST_REPORT_DAY, "")) return

            if (TerminalApi.ping()) {
                SharedPreferencesUtil.putString(SettingsKeys.TELEMETRY_LAST_REPORT_DAY, today)
            }
        } catch (e: Exception) {
            Logu.w("TelemetryReporter", "上报失败: ${e.message}")
        }
    }
}
