package com.RobinNotBad.BiliClient.util

/**
 * 设置项 key 集中管理：所有设置项的 SharedPreferences key 统一在此定义，
 * 避免散落在各处导致改 key 漏改。
 *
 * 已在 [SharedPreferencesUtil] 中定义的 key 不重复声明，直接复用其常量。
 */
object SettingsKeys {

    // ==================== 账号 ====================
    /** 当前登录用户 mid（0 表示未登录）。 */
    const val MID = "mid"

    // ==================== 界面与外观 ====================
    const val PADDING_H = "paddingH_percent"
    const val PADDING_V = "paddingV_percent"
    const val DPI = "dpi"
    const val DENSITY = "density"
    const val UI_ROUND = "player_ui_round"
    const val THEME = "theme_selector"
    const val UI_LANDSCAPE = "ui_landscape"
    const val SPLASH_TEXT = "ui_splashtext"
    const val MARQUEE_ENABLE = "marquee_enable"

    // ---- 外观三模块（配色 / 圆角 / 字体）-------------------------------
    // 配色沿用上面的 THEME，不另开 key（保持既有存档向前兼容）。
    /** 卡片圆角档位，见 `ui/appearance/CornerStyle`。 */
    const val UI_CORNER_RADIUS = "ui_corner_radius"
    /** 自定义字体（用户从文件管理器选的字体文件路径），见 `ui/appearance/FontStyle`。 */
    const val UI_FONT_PATH = "ui_font_path"

    // ==================== 详情页设置 ====================
    const val FAV_SINGLE = "fav_single"
    const val FAV_NOTICE = "fav_notice"
    const val COVER_PLAY_ENABLE = "cover_play_enable"
    const val TAGS_ENABLE = "tags_enable"
    const val RELATED_ENABLE = "related_enable"
    const val LIVE_BY_GUEST = "live_by_guest"
    const val LIKE_ONE_TRIPLE = "like_one_triple"

    // ==================== 偏好设置 ====================
    const val COPY_ENABLE = "copy_enable"
    const val CREATIVE_ENABLE = "creative_enable"
    const val SEARCH_SUGGESTIONS_ENABLE = "search_suggestions_enable"
    const val BACK_DISABLE = "back_disable"
    const val SAVE_BAN_GALLERY = "save_ban_gallery"
    const val IMAGE_REQUEST_JPG = "image_request_jpg"
    const val IMAGE_NO_LOAD_ONSCROLL = "image_no_load_onscroll"
    const val UI_ROTATORY_ENABLE = "ui_rotatory_enable"
    const val UI_ROTATORY_RECYCLER = "ui_rotatory_recycler"
    const val UI_ROTATORY_SCROLL = "ui_rotatory_scroll"

    // ==================== 缓存与下载 ====================
    const val ARIA2_ENABLED = "aria2_enabled"
    const val DEV_DOWNLOAD_OLD = "dev_download_old"
    const val ARIA2_SPLIT = "aria2_split"
    const val CACHE_QUICK_MODE = "cache_quick_mode"
    const val PARALLEL_DOWNLOAD_VIDEOS = "parallel_download_videos"
    const val CACHE_DEFAULT_QUALITY = "cache_default_quality"
    const val FORCE_HIGH_QUALITY = "force_high_quality_options"
    const val SAVE_PATH_VIDEO = "save_path_video"
    const val SAVE_PATH_PICTURES = "save_path_pictures"

    // ==================== 高级与实验 ====================
    const val DEV_PLAYER_ROTATE_SOFTWARE = "dev_player_rotate_software"
    const val PLAYER_SHOW_VIEWPOINTS = "player_show_viewpoints"
    const val PLAYER_INTERACTION_DEBUG = "player_interaction_debug"

    // ==================== 播放器 ====================
    /** 当前选择的播放器（null / terminalPlayer / mtvPlayer / aliangPlayer）。 */
    const val PLAYER = "player"
    /** 默认清晰度（qn）。 */
    const val PLAY_QN = "play_qn"

    const val PLAYER_LONGCLICK = "player_longclick"
    const val PLAYER_DOUBLETAP_SEEK = "player_doubletap_seek"
    const val PLAYER_SWIPE_SEEK = "player_swipe_seek"
    const val PLAYER_DOUBLETAP_RESTORE_SCREEN = "player_doubletap_restore_screen"
    const val PLAYER_DOUBLETAP_SEEK_SECONDS = "player_doubletap_seek_seconds"
    /** 旧「洗脑循环」开关；已被 [PLAYER_DEFAULT_LOOP] 取代，仅用于迁移默认模式。 */
    const val PLAYER_LOOP = "player_loop"
    const val PLAYER_BACKGROUND = "player_background"
    /** 旧「默认横屏」开关；已被 [PLAYER_DEFAULT_ORIENTATION] 取代，仅用于迁移默认方向。 */
    const val PLAYER_AUTOLANDSCAPE = "player_autolandscape"
    const val PLAYER_FROM_LAST = "player_from_last"
    const val PLAYER_SHOW_ONLINE = "player_show_online"
    /** 旧「听视频模式」开关；已被 [PLAYER_DEFAULT_AUDIO_ONLY] 取代，仅用于迁移默认模式。 */
    const val PLAYER_AUDIO_ONLY = "player_audio_only"
    const val PLAYER_SCALE = "player_scale"
    const val PLAYER_DOUBLEMOVE = "player_doublemove"
    const val PLAYER_DISPLAY = "player_display"
    const val PLAYER_CODEC = "player_codec"
    const val PLAYER_AUDIO = "player_audio"
    const val PLAYER_HIGH_ENERGY = "player_high_energy"
    /**
     * 自动跳过片头/片尾。数据来源是 `view_points` 里 `type=1`（片头）/`type=2`（片尾）的区间。
     * 默认关闭：跳过是「替用户做决定」，必须由用户显式开启，且跳过后要能撤回。
     */
    const val PLAYER_SKIP_OP_ED = "player_skip_op_ed"
    /**
     * 「自动跳过片头片尾」的首次引导是否已经弹过。纯记账用，不出现在设置页。
     */
    const val PLAYER_SKIP_OP_ED_GUIDED = "player_skip_op_ed_guided"
    const val PLAYER_DANMAKU_ALLOW_OVERLAP = "player_danmaku_allowoverlap"
    const val PLAYER_DANMAKU_MERGE_DUPLICATE = "player_danmaku_mergeduplicate"
    const val PLAYER_DANMAKU_FORCE_R2L = "player_danmaku_forceR2L"
    const val PLAYER_DANMAKU_SHOW_SENDER = "player_danmaku_showsender"
    const val PLAYER_DANMAKU_MAXLINE = "player_danmaku_maxline"
    const val PLAYER_DANMAKU_SIZE = "player_danmaku_size"
    const val PLAYER_DANMAKU_TRANSPARENCY = "player_danmaku_transparency"
    const val PLAYER_DANMAKU_SPEED = "player_danmaku_speed"
    const val PLAYER_SUBTITLE_AUTOSHOW = "player_subtitle_autoshow"
    const val PLAYER_SUBTITLE_AI_ALLOWED = "player_subtitle_ai_allowed"
    const val PLAYER_SUBTITLE_DELTA = "player_subtitle_delta"
    const val PLAYER_UI_SHOW_ROTATE_BTN = "player_ui_showRotateBtn"
    const val PLAYER_UI_SHOW_DANMAKU_BTN = "player_ui_showDanmakuBtn"
    const val PLAYER_UI_SHOW_QUALITY_BTN = "player_ui_showQualityBtn"
    const val PLAYER_UI_SHOW_PAGE_BTN = "player_ui_showPageBtn"
    const val PLAYER_INTERACTION_CHOICE_SIZE = "player_interaction_choice_size"

    // ==================== 播放默认值（开播时自动应用，解析逻辑见 player/PlayerDefaults.kt） ====================
    // 设置页「播放默认值」分组；每项存「模式」，「沿用上次」时再读对应的 PLAYER_LAST_* 记录。
    /** 三态模式取值：开。 */
    const val PLAYER_DEFAULT_MODE_ON = "on"
    /** 三态模式取值：关。 */
    const val PLAYER_DEFAULT_MODE_OFF = "off"
    /** 三态模式取值：沿用上次。 */
    const val PLAYER_DEFAULT_MODE_LAST = "last"

    const val PLAYER_DEFAULT_DANMAKU = "player_default_danmaku"
    const val PLAYER_DEFAULT_AUDIO_ONLY = "player_default_audio_only"
    const val PLAYER_DEFAULT_LOOP = "player_default_loop"
    const val PLAYER_DEFAULT_AUTONEXT = "player_default_autonext"
    /** 倍速：存 "0.5"~"3.0" 或 [PLAYER_DEFAULT_MODE_LAST]。 */
    const val PLAYER_DEFAULT_SPEED = "player_default_speed"
    const val PLAYER_DEFAULT_SUBTITLE = "player_default_subtitle"
    const val PLAYER_DEFAULT_ORIENTATION = "player_default_orientation"

    /** 字幕默认值：开播自动选中文（优先人工，其次 AI）。 */
    const val PLAYER_DEFAULT_SUBTITLE_ZH = "zh"
    /** 字幕默认值：自行选择（是否弹选择框交给 [PLAYER_SUBTITLE_AUTOSHOW]）。 */
    const val PLAYER_DEFAULT_SUBTITLE_MANUAL = "manual"

    /** 屏幕方向：总是横屏。 */
    const val PLAYER_DEFAULT_ORIENTATION_LANDSCAPE = "landscape"
    /** 屏幕方向：总是竖屏。 */
    const val PLAYER_DEFAULT_ORIENTATION_PORTRAIT = "portrait"
    /** 屏幕方向：按视频分辨率（宽>高则横屏）。 */
    const val PLAYER_DEFAULT_ORIENTATION_AUTO = "auto"

    // ---- 「沿用上次」的记录位：播放中实际用过的值，跨重启保存 ----
    /** 上一次弹幕的实际可见状态；沿用既有键（播放中切换弹幕时写入）。 */
    const val PLAYER_LAST_DANMAKU = "pref_switch_danmaku"
    const val PLAYER_LAST_AUDIO_ONLY = "player_last_audio_only"
    const val PLAYER_LAST_LOOP = "player_last_loop"
    const val PLAYER_LAST_AUTONEXT = "player_last_autonext"
    const val PLAYER_LAST_SPEED = "player_last_speed"

    // ==================== 调试 ====================
    const val DEV_LOGV = "dev_logv"
    const val DEV_LOGD = "dev_logd"
    const val DEV_LOGI = "dev_logi"
    const val DEV_JSONERR_DETAILED = "dev_jsonerr_detailed"
    const val DEV_RECYCLERERR_DETAILED = "dev_recyclererr_detailed"
}
