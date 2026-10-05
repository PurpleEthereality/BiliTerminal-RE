package com.RobinNotBad.BiliClient.util.view

/**
 * 「一滚动就把顶部工具条收回」的判据。
 *
 * <h3>为什么单独抽成一个对象</h3>
 * 决定用户观感的其实只有两件事：**什么时候收、什么时候展**。View 那一层的动画在 JVM
 * 单测里跑不起来——`View.animate()`、`layoutParams.height` 在单测环境全是 Android 桩，
 * 断言写了也是假的（比不写还危险）。所以把判断挪到这里的纯函数，让「阈值、方向、
 * 到顶强制展开」这些真正容易写错的分支可以被断言钉住，剩下的动画部分只做机械翻译。
 *
 * <h3>为什么要阈值而不是直接看 dy 正负</h3>
 * 手指停在屏幕上时 RecyclerView 仍会报出正负交替的小 dy（尤其是带惯性的手表滚轮），
 * 直接按符号切换会让工具条来回抽搐。所以调用方先把 dy 累加，本对象只在**同向累计**
 * 超过 [THRESHOLD] 时才给出动作。
 */
object ScrollRetractDecider {

    /** 什么都不做。 */
    const val NONE = 0

    /** 把工具条收回。 */
    const val COLLAPSE = 1

    /** 把工具条展开。 */
    const val EXPAND = 2

    /** 连续同向滚动多少像素才触发一次，用来滤掉手抖和惯性抖动。 */
    const val THRESHOLD = 12

    /**
     * 本次滚动该做什么。
     *
     * <h3>为什么展开只有「回到顶部」这一条路</h3>
     * 展开条 = 把条的高度从 0 动画回自然高度。这些条是列表的**兄弟节点**、位于列表上方，
     * 高度一变，列表可见区域跟着变、**列表内容会整体位移**。如果在列表中间就展开，用户正按着
     * 屏幕拖动时，手指底下的条目会被凭空推走——表现出来就是「按钮让下面列表位移，和滑动手势
     * 冲突」。这个问题在真机上被明确报告过（26.10.05 第三批反馈）。
     * 所以收回可以随时发生（内容朝手指方向让位，方向一致不打架），**展开一律等到
     * `!canScrollUp`**——列表已经停在顶部、不会再产生位移冲突的时候。
     *
     * @param accumulated 到目前为止**同向**累计的滚动量，向上滚为负、向下滚为正
     * @param collapsed   工具条当前是否已经收回
     * @param canScrollUp 列表还能不能往上滚；`false` 表示已经停在顶部
     */
    @JvmStatic
    fun action(accumulated: Int, collapsed: Boolean, canScrollUp: Boolean): Int {
        if (collapsed) {
            // 已经在顶部还收着，就必须展开：此时用户往回滚是滚不动的（列表没有更上面的内容），
            // 列表自身不可能再产生一个负的 dy 把它带回来，只能在这里兜住。
            // `accumulated <= 0` 是给「刚到顶就立刻往下滚」留的出口——那是在要求继续收回。
            if (!canScrollUp && accumulated <= 0) return EXPAND
            // 收着的时候继续向下滚仍然返回 COLLAPSE（即使已经收着），唯一作用是让调用方清零累加值。
            // 若在这里返回 NONE，累加值会一直涨；等用户往上滚时先要抵消掉这些历史正值，
            // 阈值早就被吃掉了，表现为「条收起来以后怎么滚都不回来」。
            if (accumulated > THRESHOLD) return COLLAPSE
            return NONE
        }
        // 没收起时只会被要求收回；向上滚不做事（展开只有回到顶部那一条路，见上文）。
        if (accumulated > THRESHOLD) return COLLAPSE
        return NONE
    }
}
