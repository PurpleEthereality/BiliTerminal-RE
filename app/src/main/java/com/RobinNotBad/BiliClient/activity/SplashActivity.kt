package com.RobinNotBad.BiliClient.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import com.RobinNotBad.BiliClient.BiliTerminal
import com.RobinNotBad.BiliClient.BiliTerminalApp
import com.RobinNotBad.BiliClient.BuildConfig
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.settings.UpdateHistoryActivity
import com.RobinNotBad.BiliClient.activity.settings.setup.SetupUIActivity
import com.RobinNotBad.BiliClient.activity.video.RecommendActivity
import com.RobinNotBad.BiliClient.activity.video.local.LocalListActivity
import com.RobinNotBad.BiliClient.api.AppInfoApi
import com.RobinNotBad.BiliClient.api.AppTokenRefreshApi
import com.RobinNotBad.BiliClient.api.CookieRefreshApi
import com.RobinNotBad.BiliClient.api.CookiesApi
import com.RobinNotBad.BiliClient.ui.appearance.ColorScheme
import com.RobinNotBad.BiliClient.util.AccountManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.NetWorkUtil
import com.RobinNotBad.BiliClient.util.PerformanceManager
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import java.io.IOException

@SuppressLint("CustomSplashScreen")
class SplashActivity : Activity() {

    private lateinit var splashTextView: TextView
    private var splashText: String = "欢迎使用\nRE:哔哩终端"

    /** F4：本次启动是否需要自动展示当版更新日志（只在"版本升级"时为 true）。 */
    private var pendingUpdateLog = false

    private var typewriterIndex = 0
    private var typewriterRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val typewriterRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!typewriterRunning || typewriterIndex > splashText.length) return
            splashTextView.text = splashText.substring(0, typewriterIndex)
            typewriterIndex++
            handler.postDelayed(this, 100)
        }
    }

    private fun startTypewriter(text: String) {
        typewriterRunning = true
        typewriterIndex = 0
        handler.post(typewriterRunnable)
    }

    private fun stopTypewriter() {
        typewriterRunning = false
        handler.removeCallbacks(typewriterRunnable)
    }

    override fun onDestroy() {
        // 打字机 Runnable 通过 handler 间接持有 Activity 并持续 postDelayed，
        // 不随生命周期停止就会泄漏。这里统一停掉（等价于对方 onDestroy 里 cancel splashTimer）。
        stopTypewriter()
        super.onDestroy()
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(BiliTerminal.getFitDisplayContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        setContentView(R.layout.activity_splash)

        splashTextView = findViewById(R.id.splashText)
        splashText = SharedPreferencesUtil.getString("ui_splashtext", "欢迎使用\nRE:哔哩终端")
        startTypewriter(splashText)

        // F4：在这里（而不是进主流程之后）判一次版本，是因为下面可能为了 UETool 悬浮窗权限
        // 直接 return、稍后由 onActivityResult 再走一遍 proceedSplashFlow；判定结果用字段记住，
        // 保证"升级后首次启动弹更新日志"只算一次。
        pendingUpdateLog = shouldShowUpdateLogAfterUpgrade()

        // Debug 构建下：若未授予悬浮窗权限，先跳去授权再继续启动流程，确保 UETool 能显示
        if (ensureUEToolOverlayPermission()) return

        // 启动主流程（抽取成单独方法，供授权回调再次调用）
        proceedSplashFlow()
    }

    /**
     * 本地是否还持有登录凭证。
     *
     * 判据用 Cookie 里的 SESSDATA：它是 Web 端唯一的身份凭证，只要它还在，就说明用户
     * 仍然是"已登录"状态。刷新接口的返回值（false / 解析异常 / 服务端抖动 / 返回体缺 data）
     * 都只能说明"这次问不到服务端"，不能证明凭证失效——这正是之前"一次网络波动就清空登录态、
     * 强制用户重新登录"的根因。
     */
    private fun hasLocalSession(): Boolean {
        val cookie = SharedPreferencesUtil.getString(SharedPreferencesUtil.cookies, "")
        return NetWorkUtil.getInfoFromCookie("SESSDATA", cookie).isNotEmpty()
    }

    @Throws(IOException::class)
    private fun checkCookieRefresh() {
        // CookieRefreshApi.cookieInfo() 内部直接 `result.getJSONObject("data")`，
        // 网络抖动、返回体缺 data、服务端 code != 0 都会抛异常。这类失败与"登录已失效"
        // 无法从返回值区分，因此单独兜住它、跳过本次刷新检查，绝不清登录态。
        val cookieInfo = try {
            CookieRefreshApi.cookieInfo()
        } catch (e: Exception) {
            Log.e("Cookies", "cookieInfo 获取/解析失败，跳过本次刷新检查：${e.message}")
            return
        }

        if (!cookieInfo.optBoolean("refresh")) return

        Log.e("Cookies", "需要刷新")
        if (SharedPreferencesUtil.getString(SharedPreferencesUtil.refresh_token, "") == "") {
            Log.e("Cookies", "没有 refresh_token，跳过刷新")
            return
        }

        try {
            val correspondPath = CookieRefreshApi.getCorrespondPath(cookieInfo.getLong("timestamp"))
            Log.e("CorrespondPath", correspondPath)
            val refreshCsrf = CookieRefreshApi.getRefreshCsrf(correspondPath)
            Log.e("RefreshCsrf", refreshCsrf)
            if (CookieRefreshApi.refreshCookie(refreshCsrf)) {
                MsgUtil.showMsg("Cookies已刷新")
                AccountManager.saveCurrentAccount()
                return
            }
            // 刷新返回 false 的全部来源——correspondPath / refresh_csrf 取空、confirm 接口
            // code != 0、新 Cookie 缺 DedeUserID（此时 CookieRefreshApi 已回退旧 Cookie）——
            // 都不是"凭证失效"，所以这里只记日志、保留登录态。
            Log.e("Cookies", "Cookie 刷新未成功，保留本地登录态")
        } catch (e: Exception) {
            // 放宽到 Exception：原实现只接 JSONException，其余异常（IOException 等）会穿透到外层。
            // 刷新流程里的任何异常都只代表这次刷新没成功，不应清登录态。
            Log.e("Cookies", "Cookie 刷新异常，保留本地登录态：${e.message}")
        }

        // 只有本地连 SESSDATA 都没有了，才是明确意义上的"未登录/凭证失效"，
        // 这时才需要提示并清空登录态引导用户重新登录。
        if (!hasLocalSession()) {
            MsgUtil.showMsgLong("登录信息过期，请重新登录！")
            resetLogin()
        }
    }

    private fun resetLogin() {
        SharedPreferencesUtil.putLong(SharedPreferencesUtil.mid, 0L)
        SharedPreferencesUtil.putString(SharedPreferencesUtil.csrf, "")
        NetWorkUtil.setCookiesString("")
        SharedPreferencesUtil.putString(SharedPreferencesUtil.refresh_token, "")
    }

    private fun applyTheme() {
        ColorScheme.applyWindowTheme(this)
    }

    /**
     * F4：判断本次启动是否需要自动展示当版更新日志（仅「版本升级」需要）。
     *
     * 记录的是 **versionCode**，不是 versionName：versionCode 由本项目版本号规则 YYMMDD0 推出、
     * 单调递增且唯一；versionName 在 beta 包上带「-BETA<n>」后缀、又靠人肉维护，不适合当基准。
     *
     * 判定口径：
     * - 存的值 == 当前 versionCode：不是升级，不弹；
     * - 存的值 >= 0（这个键生效之后的任意一次启动）：只要不相等就算升级，弹；
     * - 存的值 < 0（键不存在，即用户从"还没有这段逻辑"的旧版本升上来）：退回用 PackageInfo 的
     *   lastUpdateTime > firstInstallTime 区分「覆盖安装（升级）」与「首次安装」——前者弹，
     *   后者不弹（首装用户刚走完 SetupUIActivity，再糊一脸更新日志纯属噪音）；
     * - 无论哪种情况都把当前 versionCode 写回，保证同一版本只弹一次。
     *
     * 读取包了 try/catch：万一这个键将来被写成非 Int，getInt 的 ClassCastException 不该拖垮
     * 闪屏，退化成 -1（走上面的"未知"分支）即可。写入前判 sharedPreferences 是否就绪，
     * 因为 util 里的 putInt 没有判空。
     */
    private fun shouldShowUpdateLogAfterUpgrade(): Boolean {
        val currentCode = BuildConfig.VERSION_CODE
        val lastCode = try {
            SharedPreferencesUtil.getInt(SharedPreferencesUtil.last_version, -1)
        } catch (e: Exception) {
            Log.e("Splash", "读取 last_version 失败，按未记录处理：${e.message}")
            -1
        }

        val upgraded = when {
            lastCode == currentCode -> false
            lastCode >= 0 -> true
            else -> isCoverInstall()
        }

        if (SharedPreferencesUtil.sharedPreferences != null) {
            SharedPreferencesUtil.putInt(SharedPreferencesUtil.last_version, currentCode)
        } else {
            Log.e("Splash", "sharedPreferences 未就绪，跳过 last_version 写回")
        }
        return upgraded
    }

    /**
     * last_version 这个键还不存在时的兜底判据：
     * 包的最后更新时间晚于首次安装时间 => 覆盖安装（版本升级）；两者相等 => 首次安装。
     */
    private fun isCoverInstall(): Boolean {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            info.lastUpdateTime > info.firstInstallTime
        } catch (e: Exception) {
            Log.e("Splash", "读取安装时间失败，按非升级处理：${e.message}")
            false
        }
    }

    /** F4：构造自动打开更新日志页的 Intent，并预选当前版本选项卡。 */
    private fun buildUpdateLogIntent(): Intent {
        val intent = Intent(this, UpdateHistoryActivity::class.java)
        intent.putExtra(
            UpdateHistoryActivity.EXTRA_VERSION_INDEX,
            UpdateHistoryActivity.indexOfCurrentVersion(this)
        )
        return intent
    }

    /**
     * Debug 构建下，检查悬浮窗权限：
     * - 已授权 → 返回 false，让启动流程继续
     * - 未授权 → 跳转系统设置授权页并返回 true，在 onActivityResult 再继续启动
     *
     * Release 构建不做任何处理。
     */
    private fun ensureUEToolOverlayPermission(): Boolean {
        if (!BiliTerminalApp.isDebugBuild()) return false
        if (BiliTerminalApp.canDrawOverlaysCompat(this)) {
            // 已授权：直接显示 UETool 悬浮窗
            Handler(Looper.getMainLooper()).postDelayed({ BiliTerminalApp.showUEToolMenu() }, 300L)
            return false
        }
        // 未授权：先跳转授予悬浮窗权限
        BiliTerminalApp.requestOverlayPermission(this)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 从悬浮窗授权页返回
        if (requestCode == BiliTerminalApp.REQUEST_OVERLAY_PERMISSION_FOR_UETOOL) {
            if (BiliTerminalApp.canDrawOverlaysCompat(this)) {
                // 授权成功：立即显示 UETool，然后继续原来的启动流程
                Handler(Looper.getMainLooper()).postDelayed({ BiliTerminalApp.showUEToolMenu() }, 200L)
            } else {
                // 用户未授予：Toast 提示，不阻塞启动
                try {
                    android.widget.Toast.makeText(
                        this,
                        "未授予悬浮窗权限，UETool 调试悬浮窗不会显示",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) {
                }
            }
            // 继续启动主流程
            proceedSplashFlow()
        }
    }

    /**
     * 从 onCreate 里抽取出来的启动主流程，供授权页返回后再次调用
     */
    private fun proceedSplashFlow() {
        CenterThreadPool.run {
            if (SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.setup, false)) {
                try {
                    val firstActivity = SharedPreferencesUtil.loadMenuEnabled().firstOrNull()

                    val activityClass = MenuActivity.btnNames[firstActivity]?.second

                    val intent = Intent()
                    intent.setClass(this@SplashActivity, activityClass ?: RecommendActivity::class.java)
                    intent.putExtra("from", firstActivity)

                    runOnUiThread {
                        stopTypewriter()
                        splashTextView.text = splashText
                        startActivity(intent)
                        finish()
                        // F4：版本升级后的首次启动，自动展示当版更新日志。
                        // 排在这一句之后：日志页叠在首屏之上、返回即回到首屏，闪屏阶段既不阻塞
                        // UI 线程，也不插入登录 / Cookie 刷新流程中间。setup 尚未完成、要走
                        // SetupUIActivity 的分支不弹，免得和初始化向导抢屏幕。
                        //
                        // 已知重叠（本次未处理，需 AppInfoApi 侧配合）：AppInfoApi.check() 里
                        // 还有一条老链路——app_version_last < version 时会再弹一个内容重复的
                        // 「更新公告」ShowTextActivity，并负责删除已下载的旧更新包。详见报告。
                        if (pendingUpdateLog) {
                            pendingUpdateLog = false
                            startActivity(buildUpdateLogIntent())
                        }
                        // 启动通知（免责声明 / 夜深了）必须排在首屏 startActivity 之后，且在同一个
                        // UI 线程上弹出；否则 check() 的后台线程会抢跑，把 DialogActivity 压在首屏下面
                        AppInfoApi.showStartupNotices(this@SplashActivity)
                    }

                    if (SharedPreferencesUtil.getLong("mid", 0) != 0L) {
                        CenterThreadPool.run {
                            // 按凭证类型分流：TV/APP 登录（有 access_key）走统一的 APP token 刷新，
                            // 该接口会同时刷新 access_token 与 cookies；Web 登录走 web cookie 刷新。
                            if (SharedPreferencesUtil.getString(SharedPreferencesUtil.access_key, "").isNotEmpty()) {
                                try {
                                    AppTokenRefreshApi.refreshAppToken()
                                } catch (e: Exception) {
                                    Log.e("Splash", "APP token刷新失败: ${e.message}")
                                }
                            } else {
                                try {
                                    checkCookieRefresh()
                                } catch (e: Exception) {
                                    Log.e("Splash", "Cookie刷新失败: ${e.message}")
                                }
                            }
                            try {
                                CookiesApi.checkCookies()
                            } catch (e: Exception) {
                                Log.e("Splash", "Cookies检查失败: ${e.message}")
                            }
                        }
                    }
                    CenterThreadPool.run { AppInfoApi.check(this@SplashActivity) }

                } catch (e: Exception) {
                    // 放宽到 Exception：原来只接 JSONException，其它异常会直接打断启动流程
                    // （闪退或停在启动页）。任何失败都退化成进本地列表页，保证能进主界面。
                    stopTypewriter()
                    runOnUiThread { MsgUtil.err(e) }
                    val intent = Intent()
                    intent.setClass(this@SplashActivity, LocalListActivity::class.java)
                    startActivity(intent)
                    finish()
                }
            } else {
                stopTypewriter()
                val intent = Intent()
                intent.setClass(this@SplashActivity, SetupUIActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
    }
}