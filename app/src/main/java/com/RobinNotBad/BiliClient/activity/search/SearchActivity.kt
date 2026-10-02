package com.RobinNotBad.BiliClient.activity.search

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView

import android.text.TextUtils

import androidx.annotation.NonNull
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.InstanceActivity
import com.RobinNotBad.BiliClient.adapter.SearchHistoryAdapter
import com.RobinNotBad.BiliClient.adapter.SearchSuggestionsAdapter
import com.RobinNotBad.BiliClient.api.SearchApi
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.JsonUtil
import com.RobinNotBad.BiliClient.util.LinkUrlUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil
import com.RobinNotBad.BiliClient.util.ToolsUtil

import org.json.JSONArray
import org.json.JSONException

import java.util.Objects

class SearchActivity : InstanceActivity() {
    private var lastKeyword = "≠~`"
    private lateinit var historyRecyclerview: RecyclerView
    private lateinit var suggestionsRecyclerview: RecyclerView
    lateinit var searchHistoryAdapter: SearchHistoryAdapter
    lateinit var searchSuggestionsAdapter: SearchSuggestionsAdapter
    lateinit var viewPager: ViewPager2
    lateinit var keywordInput: EditText
    private lateinit var searchBar: ConstraintLayout
    private var searchBarVisible = true
    private var refreshing = false
    private var animate_last: Long = 0
    lateinit var handler: Handler
    lateinit var searchHistory: ArrayList<String>
    lateinit var searchSuggestions: ArrayList<String>
    private var suggestionRunnable: Runnable? = null

    // 搜索建议的「代际令牌」：每次输入自增，异步返回的建议回调只认自己那一代。
    // 为什么需要：removeCallbacks 只能取消还没开始跑的延时任务，已经派发进 Thread 的请求取消不掉，
    // 慢响应返回时会覆盖新响应（建议乱序）；用令牌可以在回调落地时把过期结果整包丢掉。
    private var suggestionGeneration = 0
    private var suggestionsEnabled: Boolean = false
    private var defaultSearchContent: String? = null
    private var defaultSearchContentEnabled: Boolean = false

    var tutorial_show: Boolean = false
    var classname: String? = null

    // 搜索类别动态列表：根据用户设置动态管理启用的搜索类别及排序
    private lateinit var categoryList: ArrayList<String>

    // 默认搜索类别顺序（番剧紧跟视频，与上游一致）
    private val defaultCategoryOrder = arrayOf("video", "bangumi", "article", "user", "live")

    var specialList = arrayOf("心理疾病", "自杀", "自尽", "自残", "抑郁", "双相", "安眠药")
    var specialNamesList = arrayOf("严炜", "陈学峰", "徐波", "易德元", "舒微函", "张自东", "杨国明", "张俊胜")

    @SuppressLint("MissingInflatedId", "NotifyDataSetChanged", "InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        classname = javaClass.simpleName
        tutorial_show = SharedPreferencesUtil.getBoolean("tutorial_pager_$classname", true)

        asyncInflate(R.layout.activity_search) { _, _ ->
            Log.e("debug", "进入搜索页")

            // 教程改由 BaseActivity 按 Tutorials 注册表集中触发

            handler = Handler()

            suggestionsEnabled = SharedPreferencesUtil.getBoolean("search_suggestions_enable", true)
            defaultSearchContentEnabled = SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_DEFAULT_CONTENT_ENABLE, false)

            if (defaultSearchContentEnabled) {
                Thread {
                    try {
                        defaultSearchContent = SearchApi.getDefaultSearchContent()
                        if (defaultSearchContent != null && defaultSearchContent!!.isNotEmpty()) {
                            runOnUiThread { keywordInput.hint = defaultSearchContent }
                        }
                    } catch (e: Exception) {
                        Log.e("SearchActivity", "获取默认搜索内容失败", e)
                    }
                }.start()
            }

            viewPager = findViewById(R.id.viewPager)

            // 构建启用的搜索类别列表
            buildCategoryList()

            val searchBtn = findViewById<View>(R.id.search)
            keywordInput = findViewById(R.id.keywordInput)
            searchBar = findViewById(R.id.searchbar)
            historyRecyclerview = findViewById(R.id.history_recyclerview)
            suggestionsRecyclerview = findViewById(R.id.suggestions_recyclerview)

            viewPager.offscreenPageLimit = maxOf(1, categoryList.size - 1)
            viewPager.isUserInputEnabled = true

            keywordInput.onFocusChangeListener = View.OnFocusChangeListener { _, b ->
                if (b) {
                    val keyword = keywordInput.text.toString()
                    // 只有「输入框有内容 且 确实拿到了建议」才显示建议列表，
                    // 否则会出现建议列表空白、历史记录又看不见的情况
                    if (keyword.isEmpty() || !suggestionsEnabled || searchSuggestions.isEmpty()) {
                        historyRecyclerview.visibility = View.VISIBLE
                        suggestionsRecyclerview.visibility = View.GONE
                    } else {
                        historyRecyclerview.visibility = View.GONE
                        suggestionsRecyclerview.visibility = View.VISIBLE
                    }
                } else {
                    historyRecyclerview.visibility = View.GONE
                    suggestionsRecyclerview.visibility = View.GONE
                }
            }
            historyRecyclerview.visibility = View.VISIBLE
            suggestionsRecyclerview.visibility = View.GONE
            // 动态适配器：根据用户设置决定显示的搜索类别和顺序
            val vpfAdapter: FragmentStateAdapter = object : FragmentStateAdapter(this) {
                override fun getItemCount(): Int {
                    return categoryList.size
                }

                @NonNull
                override fun createFragment(position: Int): Fragment {
                    return createFragmentForCategory(categoryList[position])
                }
            }
            viewPager.adapter = vpfAdapter
            // 标题随类别页变化：搜索-视频 / 搜索-番剧 / 搜索-专栏 / 搜索-用户 / 搜索-直播
            updatePageName(viewPager.currentItem)

            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                private var lastPosition = -1

                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                    if (position != 0 && position != lastPosition) {
                        onScrolled(256)
                        if (tutorial_show) {
                            tutorial_show = false
                            findViewById<View>(R.id.text_tutorial_pager).visibility = View.GONE
                            SharedPreferencesUtil.putBoolean("tutorial_pager_$classname", false)
                        }
                        lastPosition = position
                    }
                    super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                }

                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    updatePageName(position)
                    val fragmentCurr = supportFragmentManager.findFragmentByTag("f$position")
                    if (fragmentCurr != null) {
                        (fragmentCurr as SearchFragment).refresh()
                    }
                }
            })

            searchBtn.setOnClickListener { searchKeyword(keywordInput.text.toString()) }
            searchBtn.setOnLongClickListener { jumpToTargetId(it) }
            keywordInput.setOnEditorActionListener { textView, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || event != null &&
                    KeyEvent.KEYCODE_ENTER == event.keyCode && KeyEvent.ACTION_DOWN == event.action) {
                    searchKeyword(keywordInput.text.toString())
                }
                false
            }
            // 首次点击输入框时，系统只会把焦点交还给输入框，不会自动拉起输入法
            // （全工程之前没有一处 showSoftInput），用户会觉得「点了没反应」。
            // 用 post 把拉起动作延后到本轮点击事件处理完之后：否则刚 grant 的焦点会被同一轮事件撤回，键盘仍然不弹。
            keywordInput.setOnClickListener { v ->
                v.post {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
            }

            try {
                searchHistory = JsonUtil.jsonToArrayList(
                    JSONArray(SharedPreferencesUtil.getString(SharedPreferencesUtil.search_history, "[]")),
                    false)
            } catch (e: JSONException) {
                runOnUiThread { MsgUtil.err(e) }
                searchHistory = ArrayList()
            }
            searchHistoryAdapter = SearchHistoryAdapter(this, searchHistory)
            searchHistoryAdapter.setOnClickListener { position -> keywordInput.setText(searchHistory[position]) }
            searchHistoryAdapter.setOnLongClickListener { position ->
                MsgUtil.showMsg("删除成功")
                searchHistory.removeAt(position)
                searchHistoryAdapter.notifyItemRemoved(position)
                searchHistoryAdapter.notifyItemRangeChanged(position, searchHistory.size - position)
                SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                    JSONArray(searchHistory).toString())
            }
            historyRecyclerview.layoutManager = CustomLinearManager(this)
            historyRecyclerview.adapter = searchHistoryAdapter
            if (searchHistory.size > 4) {
                historyRecyclerview.isFocusable = true
                historyRecyclerview.isFocusableInTouchMode = true
                historyRecyclerview.requestFocus()
            }

            searchSuggestions = ArrayList()
            searchSuggestionsAdapter = SearchSuggestionsAdapter(this, searchSuggestions)
            searchSuggestionsAdapter.setOnClickListener { position ->
                val suggestion = searchSuggestions[position]
                // 中文输入法还在组词（composing）状态时直接 setText，会把组合区的半成品和整词叠加，出现串字；
                // 先清掉组合态再整词替换
                keywordInput.clearComposingText()
                keywordInput.setText(suggestion)
                keywordInput.setSelection(suggestion.length)
                searchKeyword(suggestion)
            }
            suggestionsRecyclerview.layoutManager = CustomLinearManager(this)
            suggestionsRecyclerview.adapter = searchSuggestionsAdapter

            if (suggestionsEnabled) {
                keywordInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable) {
                        // searchKeyword() 内部也会 setText/clearFocus，会再次回调到这里；
                        // 不拦住就会在搜索执行过程中又发起一次建议请求
                        if (refreshing) return

                        val keyword = s.toString()

                        if (suggestionRunnable != null) {
                            handler.removeCallbacks(suggestionRunnable!!)
                        }

                        if (keyword.isEmpty()) {
                            runOnUiThread {
                                if (keywordInput.hasFocus()) {
                                    historyRecyclerview.visibility = View.VISIBLE
                                    suggestionsRecyclerview.visibility = View.GONE
                                }
                            }
                        } else {
                            // 每次输入自增代际令牌，回调只认自己那一代
                            suggestionGeneration++
                            val gen = suggestionGeneration
                            suggestionRunnable = Runnable {
                                Thread {
                                    try {
                                        val suggestions = SearchApi.getSearchSuggestions(keyword)
                                        runOnUiThread {
                                            // 过期请求直接丢弃（乱序的根因），页面已销毁也不再碰视图
                                            if (gen != suggestionGeneration || refreshing || isFinishing || isDestroyed) return@runOnUiThread
                                            searchSuggestions.clear()
                                            searchSuggestions.addAll(suggestions)
                                            searchSuggestionsAdapter.notifyDataSetChanged()

                                            if (suggestions.isNotEmpty()) {
                                                historyRecyclerview.visibility = View.GONE
                                                suggestionsRecyclerview.visibility = View.VISIBLE
                                            } else {
                                                historyRecyclerview.visibility = View.VISIBLE
                                                suggestionsRecyclerview.visibility = View.GONE
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("SearchActivity", "获取搜索建议失败", e)
                                    }
                                }.start()
                            }
                            handler.postDelayed(suggestionRunnable!!, 300)
                        }
                    }
                })
            }

            if (intent.getStringExtra("keyword") != null) {
                findViewById<View>(R.id.top).setOnClickListener { finish() }
                keywordInput.setText(intent.getStringExtra("keyword"))
                MsgUtil.showMsg("可点击标题栏返回详情页")
            }
        }
    }

    fun jumpToTargetId(view: View): Boolean {
        val text = keywordInput.text.toString()
        LinkUrlUtil.handleId(this, text)
        return true
    }

    /**
     * 标题随搜索类别页变化（搜索-视频 / 搜索-番剧 / 搜索-专栏 / 搜索-用户 / 搜索-直播）。
     * 仅修改标题文字，不影响标题栏点击（菜单/返回详情）与长按搜索按钮等隐藏功能
     */
    private fun updatePageName(position: Int) {
        if (!::categoryList.isInitialized || position < 0 || position >= categoryList.size) return
        val sub = when (categoryList[position]) {
            "video" -> "视频"
            "bangumi" -> "番剧"
            "article" -> "专栏"
            "user" -> "用户"
            "live" -> "直播"
            else -> categoryList[position]
        }
        setPageName("搜索-$sub")
    }

    /**
     * 根据用户偏好设置构建启用的搜索类别列表
     * 视频始终启用且在第一位；番剧固定启用；其他类别根据设置决定是否显示及排序
     */
    private fun buildCategoryList() {
        categoryList = ArrayList()

        // 读取已保存的排序配置
        val sortConf = SharedPreferencesUtil.getString(SharedPreferencesUtil.SEARCH_CATEGORY_SORT, "")

        // 旧逻辑要求保存的类别数必须与默认表完全相等，一旦默认表加了新类别（本次加「番剧」），
        // 旧配置就会整份作废，用户在设置页排好的顺序也随之失效（而设置页目前只能写 4 个类别）。
        // 改成：保存的每一项都是已知类别就采用它，默认表里新增、旧配置里还没有的类别补在后面。
        val savedKeys: List<String> = if (TextUtils.isEmpty(sortConf)) emptyList() else sortConf.split(";")
        val savedKeysUsable = savedKeys.isNotEmpty() && savedKeys.all { defaultCategoryOrder.contains(it) }

        if (savedKeysUsable) {
            // 使用已保存的排序顺序
            for (key in savedKeys) {
                if (isCategoryEnabled(key)) {
                    categoryList.add(key)
                }
            }
            // 补上保存配置里还没有的类别（版本升级新增、设置页尚未提供开关的 tab）
            for (key in defaultCategoryOrder) {
                if (!savedKeys.contains(key) && isCategoryEnabled(key)) {
                    categoryList.add(key)
                }
            }
        } else {
            // 使用默认排序顺序
            for (key in defaultCategoryOrder) {
                if (isCategoryEnabled(key)) {
                    categoryList.add(key)
                }
            }
        }

        // 确保视频搜索始终存在
        if (!categoryList.contains("video")) {
            categoryList.add(0, "video")
        }
    }

    /**
     * 判断指定搜索类别是否启用
     * 视频搜索始终启用
     */
    private fun isCategoryEnabled(categoryKey: String): Boolean {
        return when (categoryKey) {
            "video" -> true  // 视频始终启用
            // 番剧目前没有对应的设置开关（设置页只有专栏/用户/直播三项），固定启用
            "bangumi" -> true
            "article" -> SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_CATEGORY_ARTICLE_SHOW, true)
            "user" -> SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_CATEGORY_USER_SHOW, true)
            "live" -> SharedPreferencesUtil.getBoolean(SharedPreferencesUtil.SEARCH_CATEGORY_LIVE_SHOW, true)
            else -> false
        }
    }

    /**
     * 根据类别key创建对应的搜索Fragment
     */
    private fun createFragmentForCategory(categoryKey: String): Fragment {
        return when (categoryKey) {
            "video" -> SearchVideoFragment.newInstance()
            "bangumi" -> SearchBangumiFragment.newInstance()
            "article" -> SearchArticleFragment.newInstance()
            "user" -> SearchUserFragment.newInstance()
            "live" -> SearchLiveFragment.newInstance()
            else -> SearchVideoFragment.newInstance()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回后，重新加载分类列表以应用最新设置
        if (::categoryList.isInitialized && ::viewPager.isInitialized) {
            val oldSize = categoryList.size
            buildCategoryList()
            // 如果类别列表发生变化，通知适配器更新
            if (oldSize != categoryList.size) {
                viewPager.adapter?.notifyDataSetChanged()
                viewPager.offscreenPageLimit = maxOf(1, categoryList.size - 1)
            }
            // 类别顺序/数量可能变化，刷新标题子标题
            updatePageName(viewPager.currentItem)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun searchKeyword(str: String) {
        var keyword = str
        if (keyword.contains("Robin") || keyword.contains("robin")) {
            if (keyword.contains("撅")) {
                MsgUtil.showText("特殊彩蛋", getString(R.string.egg_special))
                return
            }
            if (keyword.contains("纳西妲")) {
                MsgUtil.showText("特殊彩蛋", getString(R.string.egg_robin_nahida))
                return
            }
        }
        for (s in specialList) {
            if (keyword.contains(s)) {
                MsgUtil.showText("特殊彩蛋", getString(R.string.egg_warmwords_warmworld))
                return
            }
        }
        for (name in specialNamesList) {
            if (keyword.contains(name)) {
                MsgUtil.showText("特殊彩蛋", getString(R.string.egg_special_names))
                return
            }
        }

        if (!refreshing) {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val curFocus: View?
            if (getCurrentFocus().also { curFocus = it } != null) {
                manager.hideSoftInputFromWindow(curFocus!!.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
            }

            // 搜索一旦真正执行，就要收起建议面板并作废还没返回的建议请求：
            // 否则那个请求返回后会把建议卡片重新盖在搜索结果上
            suggestionGeneration++
            if (suggestionRunnable != null) handler.removeCallbacks(suggestionRunnable!!)
            runOnUiThread { suggestionsRecyclerview.visibility = View.GONE }

            if (keyword.isEmpty()) {
                if (defaultSearchContentEnabled && defaultSearchContent != null && defaultSearchContent!!.isNotEmpty()) {
                    keyword = defaultSearchContent!!
                } else {
                    runOnUiThread { MsgUtil.showMsg("还没输入内容喵~") }
                    return
                }
            }

            if (Objects.equals(lastKeyword, keyword)) {
                runOnUiThread {
                    keywordInput.clearFocus()
                    historyRecyclerview.visibility = View.GONE
                }
            } else {
                refreshing = true
                lastKeyword = keyword

                runOnUiThread {
                    historyRecyclerview.visibility = View.GONE
                    keywordInput.clearFocus()
                }

                if (!searchHistory.contains(keyword)) {
                    try {
                        searchHistory.add(0, keyword)
                        SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                            JSONArray(searchHistory).toString())
                        runOnUiThread {
                            searchHistoryAdapter.notifyItemInserted(0)
                            searchHistoryAdapter.notifyItemRangeChanged(0, searchHistory.size)
                            historyRecyclerview.scrollToPosition(0)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { MsgUtil.err(e) }
                    }
                } else {
                    try {
                        val pos = searchHistory.indexOf(keyword)
                        searchHistory.remove(keyword)
                        searchHistory.add(0, keyword)
                        SharedPreferencesUtil.putString(SharedPreferencesUtil.search_history,
                            JSONArray(searchHistory).toString())
                        runOnUiThread {
                            searchHistoryAdapter.notifyItemMoved(pos, 0)
                            searchHistoryAdapter.notifyItemRangeChanged(0, searchHistory.size)
                            historyRecyclerview.scrollToPosition(0)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { MsgUtil.err(e) }
                    }
                }

                try {
                    for (i in 0 until categoryList.size) {
                        val fragmentById = supportFragmentManager.findFragmentByTag("f$i")
                        if (fragmentById != null)
                            (fragmentById as SearchFragment).update(keyword)
                    }
                    val fragmentCurr = supportFragmentManager
                        .findFragmentByTag("f" + viewPager.currentItem)
                    if (fragmentCurr != null) {
                        (fragmentCurr as SearchFragment).refresh()
                        requestFragmentFocus()
                    }
                } catch (e: Exception) {
                    report(e)
                }
                refreshing = false

                if (tutorial_show) {
                    runOnUiThread {
                        val textView = findViewById<TextView>(R.id.text_tutorial_pager)
                        textView.visibility = View.VISIBLE
                        textView.text = getString(R.string.tutorial_pager, categoryList.size - 1)
                    }
                }
            }
        }
    }

    fun onScrolled(dy: Int) {
        val height = searchBar.height + ToolsUtil.dp2px(2f)

        if (System.currentTimeMillis() - animate_last > 200) {
            if (dy > 0 && searchBarVisible) {
                animate_last = System.currentTimeMillis()
                this.searchBarVisible = false
                @SuppressLint("ObjectAnimatorBinding")
                val animator = ObjectAnimator.ofFloat(searchBar, "translationY", 0f, (-height).toFloat())
                animator.start()
                handler.postDelayed({ searchBar.visibility = View.GONE }, 200)
            }
            if (dy < -1 && !searchBarVisible) {
                animate_last = System.currentTimeMillis()
                this.searchBarVisible = true
                searchBar.visibility = View.VISIBLE
                @SuppressLint("ObjectAnimatorBinding")
                val animator_show = ObjectAnimator.ofFloat(searchBar, "translationY", (-height).toFloat(), 0f)
                animator_show.start()
            }
        }

        requestFragmentFocus()
    }

    private fun requestFragmentFocus() {
        // 输入框已经持有焦点时就不要再抢：键盘弹出会改变布局并触发列表滚动回调，
        // 而滚动回调（onScrolled）每次都会走到这里，把刚拉起的键盘又顶掉
        if (keywordInput.hasFocus()) return
        val fragmentCurr = supportFragmentManager
            .findFragmentByTag("f" + viewPager.currentItem)
        if (fragmentCurr != null) {
            (fragmentCurr as SearchFragment).refresh()
            if (fragmentCurr.view != null) {
                val recyclerView = fragmentCurr.view!!.findViewById<View>(R.id.recyclerView)
                recyclerView.isFocusable = true
                recyclerView.isFocusableInTouchMode = true
                recyclerView.requestFocus()
            }
        }
    }
}