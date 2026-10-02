package com.RobinNotBad.BiliClient.adapter.viewpager

import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter

/**
 * ViewPager 用的 Fragment 适配器，适用于各类需要翻页的场景。
 *
 * 基类用 `FragmentPagerAdapter` 而不是 `FragmentStatePagerAdapter`，原因有两条：
 * 1. 这里的翻页都是固定少量页（登录 / 详情 / 搜索等 2~4 个 tab），本来就该常驻内存；
 * 2. `getItem()` 返回的是**调用方共享的 Fragment 实例**，而 `FragmentStatePagerAdapter`
 *    在页面销毁重建或状态恢复时会重新 add 这些实例，重复 add 会抛
 *    `IllegalStateException("Fragment already added")` 崩溃；
 *    `FragmentPagerAdapter` 会先按 tag 找回 FragmentManager 里已有的实例再 attach，不会重复添加。
 *
 * `BEHAVIOR_SET_USER_VISIBLE_HINT`：保持旧的 `setUserVisibleHint` 可见性回调行为，
 * 与本项目其它 ViewPager 页面的既有写法一致（换成 `BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT`
 * 会改变页面的生命周期时序，属于本次修复之外的行为变更）。
 */
@Suppress("DEPRECATION")
class ViewPagerFragmentAdapter(fm: FragmentManager, private val fragmentList: List<Fragment>) :
    FragmentPagerAdapter(fm, FragmentPagerAdapter.BEHAVIOR_SET_USER_VISIBLE_HINT) {

    val fm: FragmentManager = fm

    /**
     * 记录真正被 FragmentPagerAdapter 托管到 FragmentManager 里的 Fragment（position -> Fragment）。
     *
     * 为什么不能直接用 `fragmentList`：`getItem()` 只负责给出候选实例，
     * 状态恢复时 FragmentManager 可能已经用 tag 找回了另一个实例，
     * 这时被 add 的才是「活的」那个。按位置拿 Fragment 必须走这里，
     * 否则外层会拿到一个未被添加的孤儿实例。
     */
    private val instantiatedFragments = HashMap<Int, Fragment>()

    override fun getItem(position: Int): Fragment {
        if (position < 0 || position >= fragmentList.size) {
            return Fragment()
        }
        return fragmentList[position]
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val fragment = super.instantiateItem(container, position) as Fragment
        instantiatedFragments[position] = fragment
        return fragment
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        instantiatedFragments.remove(position)
        super.destroyItem(container, position, `object`)
    }

    /** 取回当前位置真正被托管的 Fragment；未创建或已销毁时返回 null。 */
    fun getFragment(position: Int): Fragment? = instantiatedFragments[position]

    override fun getCount(): Int {
        return fragmentList.size
    }
}
