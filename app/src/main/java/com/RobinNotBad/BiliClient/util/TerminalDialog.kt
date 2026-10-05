package com.RobinNotBad.BiliClient.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.RobinNotBad.BiliClient.R

/**
 * 弹窗的唯一构造入口（终端列表方案 B，设计稿 `docs/design/dialog-redesign-v2.html`）。
 *
 * <h3>为什么有这个文件</h3>
 * 全工程曾有 **17 处**裸 `AlertDialog.Builder(...)` 直接内联在业务代码里（7 个 `setItems` 菜单型、
 * 2 个 `setSingleChoiceItems` 单选型、8 个 `setMessage` 确认型）。样式问题不在调用点，而在主题层：
 * 7 族主题此前**都没有** `alertDialogTheme`，于是弹窗走 `Theme.MaterialComponents` 自带的
 * alert overlay，而不是本主题的颜色 overlay。后果三连：
 * - 正文/标题色取自 `colorOnSurface` —— 全工程 **0 处**声明，回退到 Material 默认近白 → 「白字」
 *   （主题里的 `android:colorForeground` 救不了它，Material 正文不读 colorForeground）
 * - 破坏性按钮色取自 `colorError` —— 同样 **0 处**声明 → 删除类操作没有危险语义
 * - 按钮文字色取自 `colorAccent` —— 这是**唯一**跟随主题的一项，恰好是 `#FF6699` 荧光粉 → 「粉按钮」
 * - 背景是方角无描边 → 「像安卓原生的一样」
 *
 * <h3>修复分两层，本文件是第二层</h3>
 * 1. `themes.xml` 给 7 族主题各加 `alertDialogTheme` → `ThemeOverlay.<X>.Dialog`，补上
 *    `colorSurface`/`colorOnSurface`/`colorError` 与圆角描边背景。这一层让**已有的** 17 个裸
 *    Builder 立刻变正常，调用点零改动。
 * 2. 本文件提供统一的**终端列表**外观（等宽引导符 `›`/`▸`、无按钮行、点空白处关闭），
 *    并把「二次确认」「纯提示」也收口进来。新代码一律走这里。
 *
 * <h3>颜色从哪来</h3>
 * 全部读 `?attr/`（`colorPrimary` / `colorOnSurface` / `colorError`），由当前主题的 dialog
 * overlay 解析，因此 7 套主题各自成立，**本文件不写任何色值常量**。
 *
 * <h3>不要做的事</h3>
 * - 不要在业务代码里裸写 `AlertDialog.Builder(...).setItems(...)`，见 `AGENTS.md` 禁令。
 * - 不要把按钮行加回菜单/单选：手表 300×300 表盘上纵向空间比一个「确定」值钱。
 *
 * 纯视图构造，无网络、无持久化，不持有 Context 引用（Dialog 自己持有）。
 */
object TerminalDialog {

    /** 未选中项的引导符。 */
    const val GLYPH_IDLE = "›"

    /** 选中项的引导符。与未选中项区分，让「当前选的是哪个」一眼可见。 */
    const val GLYPH_ACTIVE = "▸"

    /**
     * 菜单型：一串「点一下执行一个动作」的条目。
     *
     * 对应原来的 `AlertDialog.Builder(context).setItems(...)` 7 处调用点。
     *
     * @param title  标题；null 或空则不显示标题行
     * @param items  条目文字，保持调用方原有顺序与措辞，不要在这里改写业务文案
     * @param danger 哪些下标是破坏性操作（删除/取消收藏之类），用 `colorError` 着色
     * @param onPick 选中回调，参数是条目下标
     */
    fun menu(
        context: Context,
        title: String? = null,
        items: List<String>,
        danger: Set<Int> = emptySet(),
        onPick: (Int) -> Unit
    ): AlertDialog {
        val sheet = Sheet(context, title)

        items.forEachIndexed { index, text ->
            val on = index in danger
            val row = sheet.addItem(text, danger = on) {
                // 先关再回调：回调里常会再弹一个框（「删除评论」→ 二次确认），
                // 不关的话两个弹窗会叠在一起。原裸 Builder 由系统自动 dismiss，行为一致。
                sheet.dialog.dismiss()
                onPick(index)
            }
            // 菜单项没有「选中」概念，引导符一律用未选中态：次级灰。
            // 不能沿用布局默认的 ?attr/colorPrimary，否则每一项都挂个主题色箭头，比原来还花。
            row.glyph.text = GLYPH_IDLE
            if (!on) {
                row.glyph.setTextColor(sheet.secondaryColor())
                row.text.setTextColor(sheet.onSurfaceColor())
            }
        }

        sheet.setHint("点空白处取消")
        return sheet.dialog
    }

    /**
     * 单选型：带 `▸`/`›` 双态引导符 + 主色文字的选择列表。
     *
     * 对应原来的 `setSingleChoiceItems` 2 处调用点（选季 / 选集）。系统单选样式在这个深色主题下
     * 选中态几乎不可见，这里改成两重指示。
     *
     * **不自动关闭**：选集场景的回调要先做越界校验再自行决定关不关
     * （见 `BangumiInfoFragment` 拒绝切到空季的逻辑），所以由调用方 dismiss。
     *
     * @param checked 初始选中下标，越界会被夹到合法范围
     * @param onPick  选中回调；如需改变选中态，调 [Sheet.setChecked] 或重新 [paintSelection]
     */
    fun singleChoice(
        context: Context,
        title: String? = null,
        items: List<String>,
        checked: Int = 0,
        onPick: (Sheet, Int) -> Unit
    ): AlertDialog {
        val sheet = Sheet(context, title)
        items.forEach { text -> sheet.addItem(text) {} }
        sheet.rows.forEachIndexed { index, row ->
            row.root.setOnClickListener { onPick(sheet, index) }
        }
        sheet.paintSelection(checked.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        sheet.setHint("点空白处取消")
        return sheet.dialog
    }

    /**
     * 确认型：一句话 + 危险操作。
     *
     * 对应原来的 8 处 `setMessage` + `setPositiveButton`。原实现里「取消」与「删除」同为
     * `colorAccent` 粉，误触代价看起来一样；这里取消走次级灰、确认走 `colorError`。
     *
     * **保留按钮行**：终端列表方案在菜单/单选上去掉了按钮行，但确认框没有按钮就没有明确的
     * 「确认/放弃」语义，必须留。两个按钮都撑到 44dp 触控下限。
     *
     * @param confirmIsDanger true 时确认按钮用危险色；纯提示型传 false
     */
    fun confirm(
        context: Context,
        title: String? = null,
        message: CharSequence,
        confirmText: String = "确定",
        cancelText: String = "取消",
        confirmIsDanger: Boolean = true,
        onConfirm: () -> Unit
    ): AlertDialog {
        val sheet = Sheet(context, title)
        sheet.addMessage(message)

        if (cancelText.isNotEmpty()) {
            sheet.addButton(cancelText, sheet.secondaryColor()) { sheet.dialog.dismiss() }
        }
        sheet.addButton(
            confirmText,
            if (confirmIsDanger) sheet.errorColor() else sheet.primaryColor()
        ) {
            sheet.dialog.dismiss()
            onConfirm()
        }

        return sheet.dialog
    }

    /** 纯提示型（只有一个按钮），如「该季暂无剧集」。 */
    fun alert(
        context: Context,
        title: String? = null,
        message: CharSequence,
        buttonText: String = "知道了"
    ): AlertDialog = confirm(
        context = context,
        title = title,
        message = message,
        confirmText = buttonText,
        cancelText = "",
        confirmIsDanger = false,
        onConfirm = {}
    )

    /**
     * 一个弹窗的可变状态。
     *
     * 公开出来只为让 [singleChoice] 的调用方能在回调里改选中态 —— 其余场景不需要碰它。
     */
    class Sheet internal constructor(context: Context, title: String?) {

        val dialog: AlertDialog = AlertDialog.Builder(context).create()
        val rows = ArrayList<Row>()

        private val inflater = LayoutInflater.from(context)
        private val content: LinearLayout =
            inflater.inflate(R.layout.layout_dialog_terminal, null) as LinearLayout
        private val container: LinearLayout = content.findViewById(R.id.terminal_dialog_list)
        private val scroll: ScrollView = content.findViewById(R.id.terminal_dialog_scroll)
        private val titleView: TextView = content.findViewById(R.id.terminal_dialog_title)
        private val hintView: TextView = content.findViewById(R.id.terminal_dialog_hint)

        init {
            // 背景走 overlay 的 android:background（@drawable/dialog_background：圆角 + 描边）。
            // 同时必须清掉 window 自己的背景，否则 AppCompat 会垫一层方角底衬，圆角就白做了 ——
            // 这正是用户说的「像安卓原生的一样」的直接原因。
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            content.setBackgroundResource(R.drawable.dialog_background)
            dialog.setView(content)
            capScrollHeight()

            if (title.isNullOrEmpty()) {
                titleView.visibility = View.GONE
            } else {
                titleView.text = "$GLYPH_IDLE $title"
                titleView.visibility = View.VISIBLE
            }
        }

        /**
         * 给条目区设高度上限。
         *
         * `ScrollView` 没有 `android:maxHeight` 这个属性（那是 ImageView 一类的），
         * 光靠 `wrap_content` 会被内容撑到超出表盘 —— 300×300 的表盘上一条动态最多能带 10 个
         * 菜单项，不封顶就直接顶出屏幕。
         *
         * 上限取屏幕可用高度的 45%：比原来裸 Builder 的默认高度克制，又能让常见 3~5 项一屏放下。
         */
        private fun capScrollHeight() {
            val metrics = dialog.context.resources.displayMetrics
            val cap = (metrics.heightPixels * 0.45f).toInt()
            scroll.layoutParams = scroll.layoutParams.apply { height = cap }
        }

        /** 加一个条目。返回行对象，方便调用方改引导符/文字。 */
        internal fun addItem(
            text: CharSequence,
            danger: Boolean = false,
            onClick: () -> Unit
        ): Row {
            val root = inflater.inflate(R.layout.item_dialog_terminal, container, false)
            val row = Row(
                root,
                root.findViewById(R.id.terminal_item_glyph),
                root.findViewById(R.id.terminal_item_text)
            )
            row.text.text = text
            if (danger) {
                row.text.setTextColor(errorColor())
                row.glyph.setTextColor(errorColor())
            }
            root.setOnClickListener { onClick() }
            container.addView(root)
            rows.add(row)
            return row
        }

        /** 加一段说明文字（确认型用）。不显示引导符。 */
        internal fun addMessage(message: CharSequence) {
            val row = addItem(message, onClick = {})
            row.glyph.visibility = View.GONE
            row.text.maxLines = Int.MAX_VALUE
            row.text.setTextColor(secondaryColor())
            // 说明文字不是可选项：撤掉点击回调与按压反馈，避免看起来像能点
            row.root.setOnClickListener(null)
            row.root.isClickable = false
            row.root.isFocusable = false
            row.root.background = null
        }

        /** 加一个右对齐的文字按钮（确认型用）。 */
        internal fun addButton(label: String, color: Int, onClick: () -> Unit) {
            val bar = container.getChildAt(container.childCount - 1) as? LinearLayout
            val target = if (bar?.tag == BUTTON_BAR_TAG) {
                bar
            } else {
                LinearLayout(dialog.context).apply {
                    tag = BUTTON_BAR_TAG
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(
                        dimen(R.dimen.dialog_item_padding_h), 0,
                        dimen(R.dimen.dialog_item_padding_h), 0
                    )
                }.also { container.addView(it) }
            }

            target.addView(
                TextView(dialog.context).apply {
                    text = label
                    setTextColor(color)
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    minHeight = dimen(R.dimen.dialog_item_min_height)
                    minimumWidth = dimen(R.dimen.dialog_item_min_height)
                    setPadding(
                        dimen(R.dimen.dialog_item_padding_h), 0,
                        dimen(R.dimen.dialog_item_padding_h), 0
                    )
                    isClickable = true
                    isFocusable = true
                    background = selectableItemBackground()
                    setOnClickListener { onClick() }
                }
            )
        }

        internal fun setHint(hint: String) {
            hintView.text = hint
            hintView.visibility = View.VISIBLE
        }

        /** 重绘单选态：选中项 `▸` + 主色，未选中 `›` + 正文色。 */
        fun paintSelection(selected: Int) {
            rows.forEachIndexed { index, row ->
                val on = index == selected
                row.glyph.text = if (on) GLYPH_ACTIVE else GLYPH_IDLE
                if (on) {
                    row.glyph.setTextColor(primaryColor())
                    row.text.setTextColor(primaryColor())
                } else {
                    row.glyph.setTextColor(secondaryColor())
                    row.text.setTextColor(onSurfaceColor())
                }
            }
        }

        internal fun primaryColor() = attrColor(androidx.appcompat.R.attr.colorPrimary)

        internal fun onSurfaceColor() = attrColor(com.google.android.material.R.attr.colorOnSurface)

        internal fun errorColor() = attrColor(com.google.android.material.R.attr.colorError)

        /**
         * 次级文字色：Material 没给 dialog 专用的次级色，用 onSurface 压到 70% 透明度得到。
         * 对深色底仍远高于 AA 的 4.5:1。
         */
        internal fun secondaryColor(): Int {
            val base = onSurfaceColor()
            val alpha = (Color.alpha(base) * 0.7f).toInt().coerceIn(0, 255)
            return (base and 0x00FFFFFF) or (alpha shl 24)
        }

        private fun dimen(resId: Int) = dialog.context.resources.getDimensionPixelSize(resId)

        private fun selectableItemBackground() = dialog.context.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackground)
        ).let { ta ->
            val drawable = ta.getDrawable(0)
            ta.recycle()
            drawable
        }

        /**
         * 取主题属性色。
         *
         * **必须给默认值**：`colorOnSurface` / `colorError` 是本次新补的属性，万一日后新增主题
         * 漏了 overlay，拿不到值时抛异常不如退回可读的兜底色。
         */
        private fun attrColor(attr: Int): Int {
            val ta = dialog.context.obtainStyledAttributes(intArrayOf(attr))
            val color = ta.getColor(0, Color.WHITE)
            ta.recycle()
            return color
        }

        private companion object {
            const val BUTTON_BAR_TAG = "terminal_dialog_button_bar"
        }
    }

    /** 一个条目的三个可变部位。 */
    class Row internal constructor(
        val root: View,
        val glyph: TextView,
        val text: TextView
    )
}
