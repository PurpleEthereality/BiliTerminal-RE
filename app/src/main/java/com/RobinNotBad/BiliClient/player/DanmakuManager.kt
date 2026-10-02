package com.RobinNotBad.BiliClient.player

import android.content.Context
import android.graphics.Color
import android.view.View
import com.RobinNotBad.BiliClient.model.DmSegMobileReply
import com.RobinNotBad.BiliClient.util.Logu
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.loader.ILoader
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.parser.android.BiliDanmukuParser
import master.flame.danmaku.danmaku.parser.android.BiliProtobufDanmakuParser
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class DanmakuState(
    val isVisible: Boolean = true,
    val isPrepared: Boolean = false,
    val maxLines: Int = 15,
    val speedFactor: Float = 1.0f,
    val textSizeScale: Float = 0.7f,
    val transparency: Float = 0.5f,
    val mergeDuplicate: Boolean = false,
    val allowOverlap: Boolean = true
)

class DanmakuManager(
    private val danmakuView: IDanmakuView,
    /**
     * 返回当前播放位置（毫秒）。**播放器未就绪或正在重建时必须返回负数**，
     * 表示"没有可信位置"，[configureAndPrepare] 里会据此跳过 timer 更新，见其注释。
     *
     * 注意：本回调运行在 DanmakuView 的渲染线程上，实现方**不要在这里直接读播放器**
     * （例如 `IjkMediaPlayer.currentPosition`）——那是会取 native 锁的 JNI 调用，
     * 与主线程的 seek/release 并发时有卡死渲染线程的风险。请返回由主线程定时器维护的
     * 内存值（参见 PlayerActivity 的 video_now）。
     */
    private val onCurrentPositionMs: () -> Long
) {
    private var danmakuContext: DanmakuContext? = null
    private var danmakuParser: BaseDanmakuParser? = null

    /**
     * 上一次喂给 DanmakuTimer 的播放位置，用于去重。
     * 必须 volatile：updateTimer 正常由 DFM 的 "DFM Update" 线程回调
     * （DrawHandler.java:140 `mUpdateInNewThread = availableProcessors() > 3`，否则走 DrawHandler 的
     * HandlerThread），但 `DanmakuView.pause()`（主线程）与 QUIT 流程里的
     * `syncTimerIfNeeded()` 也可能在别的线程上触发同一次回调，Long 在 32 位设备上会撕裂读。
     */
    @Volatile
    private var lastTimerPos = -1L

    private val _state = MutableStateFlow(DanmakuState())
    val state: StateFlow<DanmakuState> = _state.asStateFlow()

    fun init() {
        val prefs = SharedPreferencesUtil.getSharedPreferences()
        _state.update {
            DanmakuState(
                maxLines = SharedPreferencesUtil.getInt("player_danmaku_maxline", 15),
                speedFactor = SharedPreferencesUtil.getFloat("player_danmaku_speed", 1.0f),
                textSizeScale = SharedPreferencesUtil.getFloat("player_danmaku_size", 0.7f),
                transparency = SharedPreferencesUtil.getFloat("player_danmaku_transparency", 0.5f),
                mergeDuplicate = SharedPreferencesUtil.getBoolean("player_danmaku_mergeduplicate", false),
                allowOverlap = SharedPreferencesUtil.getBoolean("player_danmaku_allowoverlap", true)
            )
        }
    }

    fun loadFromXmlFile(xmlFilePath: String) {
        val file = File(xmlFilePath)
        if (!file.exists()) return

        try {
            // 用 use{} 包住输入流：原来 file.inputStream() 交给 createParser 后，
            // 解析中途抛异常或提前结束时这个文件描述符就没人关了。
            // DanmakuParser.prepare() 是同步解析，configureAndPrepare 返回后流已读完，可安全关闭
            file.inputStream().use { stream ->
                val parser = createParser(stream)
                configureAndPrepare(parser)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadFromXmlInput(inputStream: InputStream) {
        try {
            val parser = createParser(inputStream)
            configureAndPrepare(parser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 用新版分段弹幕（protobuf）准备弹幕视图。
     *
     * 这是普通播放器原来内联在 `PlayerActivity.downdanmuNew()` 里的能力，合并弹幕栈后归到这里，
     * 两个播放器共用同一套配置与回调。
     */
    fun loadFromProtobufSegments(segments: List<DmSegMobileReply>) {
        try {
            val parser = BiliProtobufDanmakuParser()
            parser.sharedPreferences = SharedPreferencesUtil.getSharedPreferences()
            parser.setDanmakuSegments(segments)
            configureAndPrepare(parser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 直播模式：没有历史弹幕数据，先准备一个空 parser，后续用 [addDanmaku] 实时喂入。
     * （原 `PlayerActivity.streamDanmaku(null)` 就是这个语义。）
     */
    fun prepareEmpty() {
        configureAndPrepare(object : BaseDanmakuParser() {
            override fun parse(): Danmakus = Danmakus()
        })
    }

    /**
     * 构造弹幕 parser。**注意：本方法只能在主线程调用，不可并发。**
     *
     * `DanmakuLoaderFactory.create(TAG_BILI)` 返回的是进程级单例 `BiliDanmakuLoader`，
     * 而 `dataSource` 是它的**实例字段**（BiliDanmakuLoader.java:29,44-51），
     * 这里 `load()` 写字段、`loader.dataSource` 读字段是一段 check-then-act。
     * 两个 holder 同时加载弹幕时会交错，导致两个 parser 拿到同一个数据源 → 弹幕串台/解析为空。
     *
     * 另注：本方法**不做 XML 解析**——`AndroidFileSource(InputStream)` 与
     * `BaseDanmakuParser.load()` 都只是存引用，真解析在 `getDanmakus()` → `parse()` 里懒执行，
     * 跑在 `DanmakuView` 自己的渲染线程（调用点只有 `DrawTask.java:283`）。
     * 所以把它挪到后台线程并不会减少主线程耗时，只会引入上面那个竞态。
     */
    private fun createParser(inputStream: InputStream): BaseDanmakuParser {
        val loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI)
        loader.load(inputStream)
        val parser = BiliDanmukuParser()
        parser.sharedPreferences = SharedPreferencesUtil.getSharedPreferences()
        val dataSource = loader.dataSource
        parser.load(dataSource)
        return parser
    }

    private fun configureAndPrepare(parser: BaseDanmakuParser) {
        danmakuParser = parser
        val st = _state.value

        val maxLinesPair = hashMapOf(BaseDanmaku.TYPE_SCROLL_RL to st.maxLines)
        val overlap = hashMapOf(
            BaseDanmaku.TYPE_SCROLL_LR to st.allowOverlap,
            BaseDanmaku.TYPE_FIX_BOTTOM to st.allowOverlap
        )

        danmakuContext = DanmakuContext.create().apply {
            setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 1f)
            setDuplicateMergingEnabled(st.mergeDuplicate)
            setScrollSpeedFactor(st.speedFactor)
            setScaleTextSize(st.textSizeScale)
            setMaximumLines(maxLinesPair)
            setDanmakuTransparency(st.transparency)
            preventOverlapping(overlap)
        }

        danmakuView.setCallback(object : DrawHandler.Callback {
            override fun prepared() {
                // 必须兜异常：本回调运行在 DanmakuFlameMaster 的渲染线程上，
                // 一旦异常逸出会打断该线程，表现就是"弹幕一条都不显示"且没有任何上层报错。
                try {
                    _state.update { it.copy(isPrepared = true) }
                    addDanmaku("弹幕准备完毕", Color.WHITE)
                } catch (e: Exception) {
                    Logu.e("弹幕", "prepared 回调异常: ${e.message}")
                    e.printStackTrace()
                }
            }

            override fun updateTimer(timer: DanmakuTimer) {
                // 回调返回负数表示"当前拿不到可信的播放位置"，此时必须**跳过**本次更新。
                // 这个守卫是必需的：IjkMediaPlayer 的 native 层在 setDataSource / prepareAsync /
                // release 期间并非线程安全，而本回调运行在 DanmakuView 的渲染线程上，与主线程
                // 重建播放器的动作并发。一旦把窗口期的脏位置灌进 timer，整批弹幕会被判定为
                // "已过期"而一条都不显示 —— 表现为间歇性的"弹幕没了"。
                // （原 PlayerActivity 内联实现用的是 `if (ijkPlayer != null && isPrepared)`，同一个道理。）
                // 实现上 onCurrentPositionMs 已改为只读主线程定时器维护的内存值，
                // 不再在这个渲染线程上直接调 ijkPlayer.currentPosition（那会取 native 锁）。
                val pos = onCurrentPositionMs()
                if (pos < 0) return
                // 位置没变就不要喂给 timer。timer.update(pos) 是"把时钟强行设成 pos"，
                // 而 DFM 在 syncTimer 里每帧用 timer.add(d)（DrawHandler.java:462）自己推进时钟；
                // 若每帧都用同一个（最多 250ms 前的）位置回灌，DFM 的自走时钟会被钉死，
                // 弹幕变成 4Hz 一跳的卡顿。所以只在位置真的变化时校正一次：
                // DFM 自走 + 定时器周期性纠偏，既避开 JNI 又保留原有跟随精度。
                if (pos == lastTimerPos) return
                lastTimerPos = pos
                timer.update(pos)
            }

            override fun danmakuShown(danmaku: BaseDanmaku?) {}

            override fun drawingFinished() {}
        })

        danmakuView.enableDanmakuDrawingCache(true)
        danmakuView.prepare(parser, danmakuContext)
    }

    /**
     * 往当前弹幕视图里即时插入一条弹幕。
     *
     * 参数与原 `PlayerActivity.addDanmaku(text, color, textSize, type, backgroundColor)` **完全一致**，
     * 这样直播弹幕链路（`PlayerDanmuClientListener` 直接调用 `playerActivity.addDanmaku(...)`）
     * 与"发送弹幕"链路可以原样迁移过来，行为不变。
     */
    fun addDanmaku(
        text: String,
        color: Int = Color.WHITE,
        textSize: Int = 25,
        type: Int = BaseDanmaku.TYPE_SCROLL_RL,
        backgroundColor: Int = 0
    ) {
        val ctx = danmakuContext ?: return
        val danmaku = ctx.mDanmakuFactory.createDanmaku(type, ctx) ?: return
        danmaku.text = text
        danmaku.padding = 5
        danmaku.priority = 1
        danmaku.isLive = false
        danmaku.time = danmakuView.currentTime + 100
        danmaku.textSize = textSize * (ctx.displayer.density - 0.6f)
        danmaku.textColor = color
        danmaku.backgroundColor = backgroundColor
        danmaku.textShadowColor = Color.BLACK
        danmakuView.addDanmaku(danmaku)
    }


    fun show() {
        _state.update { it.copy(isVisible = true) }
        if (!_state.value.isPrepared) return
        try { danmakuView.show() } catch (_: Exception) {}
    }

    fun hide() {
        _state.update { it.copy(isVisible = false) }
        if (!_state.value.isPrepared) return
        try { danmakuView.hide() } catch (_: Exception) {}
    }

    fun pause() {
        if (!_state.value.isPrepared) return
        try { danmakuView.pause() } catch (_: Exception) {}
    }
    fun resume() {
        if (!_state.value.isPrepared) return
        try { danmakuView.resume() } catch (_: Exception) {}
    }
    fun seekTo(ms: Long) {
        if (!_state.value.isPrepared) return
        try { danmakuView.seekTo(ms) } catch (_: Exception) {}
    }

    fun release() {
        try { danmakuView.release() } catch (_: Exception) {}
        // 复位 isPrepared：show/hide/pause/resume/seekTo 都有 `if (!isPrepared) return` 守卫，
        // 释放后不复位会让复用同一 DanmakuManager 的路径以为弹幕还挂在已释放的 view 上
        _state.update { it.copy(isPrepared = false) }
    }
}