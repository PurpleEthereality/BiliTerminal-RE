package com.RobinNotBad.BiliClient.activity.video.info

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.ImageViewerActivity
import com.RobinNotBad.BiliClient.activity.settings.SettingPlayerChooseActivity
import com.RobinNotBad.BiliClient.activity.video.JumpToPlayerActivity
import com.RobinNotBad.BiliClient.adapter.video.MediaEpisodeAdapter
import com.RobinNotBad.BiliClient.api.BangumiApi
import com.RobinNotBad.BiliClient.model.Bangumi
import com.RobinNotBad.BiliClient.ui.widget.recycler.CustomLinearManager
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.GlideUtil
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class BangumiInfoFragment : Fragment() {
    private var mediaId: Long = 0

    /** 从历史列表进来时要定位的剧集 id（epid）；0 表示不定位，正常从第 1 集开始 */
    private var targetEpid: Long = 0

    /** 上面那一集的续播进度，单位秒（跟历史接口、VideoCard.progress 一致）；0 表示没有 */
    private var targetProgressSec: Int = 0
    private var selectedSection = 0
    private var selectedEpisode = 0
    private var dialog: Dialog? = null
    private var rootView: View? = null
    private var episodeRecyclerView: RecyclerView? = null
    private var sectionChoose: Button? = null
    private var episodeChoose: TextView? = null
    private var bangumi: Bangumi? = null

    companion object {
        @JvmStatic
        fun newInstance(mediaId: Long): BangumiInfoFragment = newInstance(mediaId, 0L, 0)

        /**
         * @param mediaId       番剧 media_id
         * @param epid          上次观看的剧集 id，0 表示不定位
         * @param progressSec   上次观看进度，单位秒（历史接口的单位），0 表示没有
         */
        @JvmStatic
        fun newInstance(mediaId: Long, epid: Long, progressSec: Int): BangumiInfoFragment {
            val args = Bundle()
            args.putLong("media_id", mediaId)
            args.putLong("epid", epid)
            args.putInt("progress_sec", progressSec)
            val fragment = BangumiInfoFragment()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val arguments = arguments
        if (arguments != null) {
            mediaId = arguments.getLong("media_id")
            targetEpid = arguments.getLong("epid", 0L)
            targetProgressSec = arguments.getInt("progress_sec", 0)
        }
        rootView = inflater.inflate(R.layout.fragment_media_info, container, false)
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.visibility = View.GONE
        episodeRecyclerView = rootView!!.findViewById(R.id.rv_episode_list)

        // 兼容宿主把 epid/progress 直接塞进 Activity Intent 的用法（本类自己的 newInstance 走 arguments）
        val hostIntent = requireActivity().intent
        if (hostIntent != null) {
            if (targetEpid <= 0L) targetEpid = hostIntent.getLongExtra("epid", 0L)
            if (targetProgressSec <= 0) targetProgressSec = hostIntent.getIntExtra("progress_sec", 0)
        }

        CenterThreadPool
            .supplyAsyncWithLiveData {
                // 历史记录里的番剧卡片带的是 epid，传进来的 aid 其实是剧集 avid 而不是 media_id。
                // 直接拿 avid 当 media_id 请求，接口同样返回 code=0，但 season_id 是 0、标题为空，
                // 详情页会变成一个空壳，所以这里先用 epid 反查真正的 media_id，查不到再退回原值。
                var id = mediaId
                if (targetEpid > 0L) {
                    // getMdidFromEpid 的返回类型是装箱 Long，这里顺手挡一次 null，
                    // 免得以后有人改了那个方法让这里变成拆箱 NPE
                    val mdId = BangumiApi.getMdidFromEpid(targetEpid)
                    if (mdId != null && mdId > 0L) id = mdId
                }
                BangumiApi.getBangumi(id)
            }
            .observe(viewLifecycleOwner) { result ->
                result.onSuccess { bangumi ->
                    this.bangumi = bangumi
                    initView()
                }.onFailure { error -> MsgUtil.err("番剧详情：", error) }
            }
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        val imageMediaCover = rootView!!.findViewById<ImageView>(R.id.image_media_cover)
        val playButton = rootView!!.findViewById<Button>(R.id.btn_play)
        val title = rootView!!.findViewById<TextView>(R.id.text_title)
        val subtitle = rootView!!.findViewById<TextView>(R.id.text_subtitle)
        val areaType = rootView!!.findViewById<TextView>(R.id.text_area_type)
        val rating = rootView!!.findViewById<TextView>(R.id.text_rating)
        val pubTime = rootView!!.findViewById<TextView>(R.id.text_pub_time)
        val stats = rootView!!.findViewById<TextView>(R.id.text_stats)
        val styles = rootView!!.findViewById<TextView>(R.id.text_styles)
        val evaluateHeader = rootView!!.findViewById<View>(R.id.layout_evaluate_header)
        val evaluateArrow = rootView!!.findViewById<ImageView>(R.id.icon_evaluate_arrow)
        val evaluate = rootView!!.findViewById<TextView>(R.id.text_evaluate)
        val staffHeader = rootView!!.findViewById<View>(R.id.layout_staff_header)
        val staffArrow = rootView!!.findViewById<ImageView>(R.id.icon_staff_arrow)
        val staff = rootView!!.findViewById<TextView>(R.id.text_staff)
        val record = rootView!!.findViewById<TextView>(R.id.text_record)
        sectionChoose = rootView!!.findViewById(R.id.section_choose)
        episodeChoose = rootView!!.findViewById(R.id.episode_choose)
        selectedSection = 0

        rootView!!.visibility = View.GONE

        Glide.with(requireContext())
            .load(GlideUtil.url_hq(bangumi!!.info.cover_horizontal))
            .transition(GlideUtil.getTransitionOptions())
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .placeholder(R.mipmap.placeholder)
            .error(R.mipmap.placeholder)
            .into(imageMediaCover)
        imageMediaCover.setOnClickListener {
            startActivity(Intent(it.context, ImageViewerActivity::class.java).putExtra("imageList", ArrayList(listOf(bangumi!!.info.cover_horizontal))))
        }
        title.text = bangumi!!.info.title

        if (bangumi!!.info.subtitle != null && bangumi!!.info.subtitle!!.isNotEmpty()) {
            subtitle.text = bangumi!!.info.subtitle
            subtitle.visibility = View.VISIBLE
        } else {
            subtitle.visibility = View.GONE
        }

        val areaTypeText = (if (bangumi!!.info.area_name != null) bangumi!!.info.area_name else "") +
                (if (bangumi!!.info.type_name != null) " | " + bangumi!!.info.type_name else "")
        if (areaTypeText.trim().isNotEmpty()) {
            areaType.text = areaTypeText.trim()
            areaType.visibility = View.VISIBLE
        } else {
            areaType.visibility = View.GONE
        }

        if (bangumi!!.info.score > 0) {
            rating.text = String.format("评分：%.1f (%d人)", bangumi!!.info.score, bangumi!!.info.count)
            rating.visibility = View.VISIBLE
        } else {
            rating.visibility = View.GONE
        }

        if (bangumi!!.info.publish != null && bangumi!!.info.publish!!.pub_time_show != null && bangumi!!.info.publish!!.pub_time_show!!.isNotEmpty()) {
            val status = if (bangumi!!.info.publish!!.is_finish == 1) "已完结" else "连载中"
            pubTime.text = bangumi!!.info.publish!!.pub_time_show + " " + status
            pubTime.visibility = View.VISIBLE
        } else {
            pubTime.visibility = View.GONE
        }

        if (bangumi!!.info.stat != null) {
            val statBuilder = StringBuilder()
            if (bangumi!!.info.stat!!.views > 0) {
                statBuilder.append("播放：").append(formatNumber(bangumi!!.info.stat!!.views))
            }
            if (bangumi!!.info.stat!!.favorites > 0) {
                if (statBuilder.isNotEmpty()) statBuilder.append(" ")
                statBuilder.append("收藏：").append(formatNumber(bangumi!!.info.stat!!.favorites))
            }
            if (bangumi!!.info.stat!!.series_follow > 0) {
                if (statBuilder.isNotEmpty()) statBuilder.append(" ")
                statBuilder.append("追番：").append(formatNumber(bangumi!!.info.stat!!.series_follow))
            }
            if (statBuilder.isNotEmpty()) {
                stats.text = statBuilder.toString()
                stats.visibility = View.VISIBLE
            } else {
                stats.visibility = View.GONE
            }
        } else {
            stats.visibility = View.GONE
        }

        if (bangumi!!.info.styles != null && bangumi!!.info.styles!!.isNotEmpty()) {
            val styleText = "标签：" + bangumi!!.info.styles!!.joinToString(" ")
            styles.text = styleText
            styles.visibility = View.VISIBLE
        } else {
            styles.visibility = View.GONE
        }

        if (bangumi!!.info.evaluate != null && bangumi!!.info.evaluate!!.trim().isNotEmpty()) {
            evaluate.text = bangumi!!.info.evaluate!!.trim()
            evaluateHeader.visibility = View.VISIBLE
            evaluate.visibility = View.GONE
            evaluateHeader.setOnClickListener {
                val isExpanded = evaluate.visibility == View.VISIBLE
                evaluate.visibility = if (isExpanded) View.GONE else View.VISIBLE
                evaluateArrow.animate().rotation(if (isExpanded) 0f else 180f).setDuration(200).start()
            }
        } else {
            evaluateHeader.visibility = View.GONE
            evaluate.visibility = View.GONE
        }

        if (bangumi!!.info.staff != null && bangumi!!.info.staff!!.trim().isNotEmpty()) {
            staff.text = bangumi!!.info.staff!!.trim()
            staffHeader.visibility = View.VISIBLE
            staff.visibility = View.GONE
            staffHeader.setOnClickListener {
                val isExpanded = staff.visibility == View.VISIBLE
                staff.visibility = if (isExpanded) View.GONE else View.VISIBLE
                staffArrow.animate().rotation(if (isExpanded) 0f else 180f).setDuration(200).start()
            }
        } else {
            staffHeader.visibility = View.GONE
            staff.visibility = View.GONE
        }

        if (bangumi!!.info.record != null && bangumi!!.info.record!!.trim().isNotEmpty()) {
            record.text = "备案号：" + bangumi!!.info.record!!.trim()
            record.visibility = View.VISIBLE
        } else {
            record.visibility = View.GONE
        }

        val adapter = MediaEpisodeAdapter()

        adapter.setOnItemClickListener { index ->
            selectedEpisode = index
            refreshReplies()
        }

        val indexShow = rootView!!.findViewById<TextView>(R.id.indexShow)
        indexShow.text = bangumi!!.info.indexShow

        // 首个"有剧集"的季才是可用的起始季：番剧数据里第一项常常是空的 PV/预告季，
        // 直接用它会让选集区空白，而且后面按下标取剧集时必然越界
        val firstSection = firstSectionWithEpisodes()
        if (firstSection < 0) {
            sectionChoose!!.text = "敬请期待"
            playButton.visibility = View.GONE
            rootView!!.findViewById<View>(R.id.episodes).visibility = View.GONE
            val activity = requireActivity()
            if (activity is VideoInfoActivity) {
                activity.replyFragment?.setRefreshing(false)
            }
            return
        }

        selectedSection = firstSection
        selectedEpisode = 0
        // 从历史列表进来时带着上次观看的 epid：定位到就直接选中那一集；
        // 定位失败（换季/下架/数据异常）保持第 1 集，不崩也不白屏
        if (locateEpisodeByEpid(targetEpid) && (selectedSection != firstSection || selectedEpisode != 0)) {
            MsgUtil.showMsg("已定位到上次观看的「" + (currentEpisode()?.title ?: "") + "」")
        }

        sectionChoose!!.text = bangumi!!.sectionList[selectedSection].title + " 点击切换"
        sectionChoose!!.setOnClickListener { getSectionChooseDialog().show() }
        episodeChoose!!.setOnClickListener { getEposideChooseDialog().show() }

        adapter.setData(bangumi!!.sectionList[selectedSection].episodeList)
        episodeRecyclerView!!.layoutManager = CustomLinearManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        episodeRecyclerView!!.adapter = adapter
        // setData 会把高亮重置到第 0 项；定位成功时要把高亮和滚动位置一起挪到目标集
        adapter.selectedItemIndex = selectedEpisode
        episodeRecyclerView!!.scrollToPosition(selectedEpisode)

        playButton.setOnClickListener {
            // 用安全取值代替 direct 下标：选中的季为空/下标越界时返回 null 而不是抛 IndexOutOfBoundsException
            val episode = currentEpisode() ?: return@setOnClickListener
            Glide.get(requireContext()).clearMemory()
            val intent = Intent(it.context, JumpToPlayerActivity::class.java)
            // 必须带上 season_id 与季类型：番剧的观看进度要走心跳接口上报，
            // 只有 epid 而没有 sid/sub_type 时服务端不会把它记成番剧记录
            val data = episode.toPlayerData(bangumi!!.info.season_id, bangumi!!.info.type)
            // 历史来源的进度单位是秒，PlayerData.progress 要毫秒；
            // 只有当前这一集就是历史里记的那一集时才带，避免把别集的进度套到这一集上
            if (episode.id == targetEpid && targetProgressSec > 0) {
                data.progress = targetProgressSec * 1000
            }
            intent.putExtra("data", data)
            startActivity(intent)
        }
        playButton.setOnLongClickListener {
            val intent = Intent(it.context, SettingPlayerChooseActivity::class.java)
            startActivity(intent)
            true
        }
        onFinishLoad()

        refreshReplies()
    }

    /** sectionList 里第一个真正含有剧集的季的下标；全都为空（或没有季）时返回 -1。 */
    private fun firstSectionWithEpisodes(): Int {
        val sections = bangumi?.sectionList ?: return -1
        for (i in sections.indices) {
            if (!sections[i].episodeList.isNullOrEmpty()) return i
        }
        return -1
    }

    /**
     * 在全部季里找 epid 对应的一集并选中。
     *
     * 只改 selectedSection / selectedEpisode 这两个下标，最终取集仍然走 currentEpisode()，
     * 这样即使数据里有空季也不会选中一个取不出剧集的季（P19 修的越界崩溃就是这么来的）。
     * 找不到（epid 为 0、不在任何季里、或取出来是 null）时不动下标并返回 false，
     * 调用方保持第 1 集即可。
     */
    private fun locateEpisodeByEpid(epid: Long): Boolean {
        if (epid <= 0L) return false
        val sections = bangumi?.sectionList ?: return false
        for (s in sections.indices) {
            val episodes = sections[s].episodeList ?: continue
            for (e in episodes.indices) {
                if (episodes[e].id != epid) continue
                if (s == selectedSection && e == selectedEpisode) return true
                selectedSection = s
                selectedEpisode = e
                // 能通过既有安全取值拿到这一集才算定位成功，否则让调用方退回第 1 集
                return currentEpisode() != null
            }
        }
        return false
    }

    /**
     * 当前选中的剧集；选中季为空或下标越界时返回 null。
     * 所有需要按下标取剧集的地方都必须走这里，否则空季数据会直接抛 IndexOutOfBoundsException。
     */
    private fun currentEpisode(): Bangumi.Episode? {
        val sections = bangumi?.sectionList ?: return null
        if (selectedSection !in sections.indices) return null
        val episodes = sections[selectedSection].episodeList ?: return null
        if (selectedEpisode !in episodes.indices) return null
        return episodes[selectedEpisode]
    }

    @SuppressLint("SetTextI18n")
    private fun getSectionChooseDialog(): Dialog {
        val choices = Array(bangumi!!.sectionList.size) { i -> bangumi!!.sectionList[i].title }

        val builder = AlertDialog.Builder(requireContext())
        builder.setSingleChoiceItems(choices, selectedSection) { dialog, which ->
            // 允许切到空季会让选集区/playButton 随后在空列表上取下标而崩溃，这里直接拒绝切换并提示
            if (bangumi!!.sectionList[which].episodeList.isNullOrEmpty()) {
                MsgUtil.showMsg("该季暂无剧集")
                dialog.dismiss()
                return@setSingleChoiceItems
            }
            selectedSection = which
            selectedEpisode = 0

            refreshReplies()
            val section = bangumi!!.sectionList[which]
            sectionChoose!!.text = section.title + " 点击切换"
            val adapter = episodeRecyclerView!!.adapter as MediaEpisodeAdapter
            adapter.setData(bangumi!!.sectionList[which].episodeList)
            episodeRecyclerView!!.scrollToPosition(0)
            episodeChoose!!.setOnClickListener { getEposideChooseDialog().show() }
            dialog.dismiss()
        }
        dialog = builder.create()

        return dialog!!
    }

    private fun getEposideChooseDialog(): Dialog {
        val episodeList = bangumi?.sectionList?.getOrNull(selectedSection)?.episodeList

        // 双保险：数据刷新后仍可能落到空季上，空列表既不能建下标也不能建选择项
        if (episodeList.isNullOrEmpty()) {
            MsgUtil.showMsg("该季暂无剧集")
            dialog = AlertDialog.Builder(requireContext()).setMessage("该季暂无剧集").create()
            return dialog!!
        }

        val choices = Array(episodeList.size) { i ->
            val episode = episodeList[i]
            episode.title + "." + episode.title_long
        }

        val builder = AlertDialog.Builder(requireContext())
        // 选中下标必须夹到合法范围，否则数据刷新导致列表变短时会指向不存在的项
        builder.setSingleChoiceItems(choices, selectedEpisode.coerceIn(0, episodeList.size - 1)) { dialog, which ->
            selectedEpisode = which
            refreshReplies()

            val adapter = episodeRecyclerView!!.adapter as MediaEpisodeAdapter
            adapter.selectedItemIndex = which
            episodeRecyclerView!!.scrollToPosition(which)
            dialog.dismiss()
        }
        dialog = builder.create()

        return dialog!!
    }

    private fun refreshReplies() {
        val episode = currentEpisode() ?: return
        val activity = activity
        if (activity is VideoInfoActivity) {
            activity.setCurrentAid(episode.aid)
        }
    }

    fun onFinishLoad() {
        try {
            val activity = requireActivity()
            if (activity is VideoInfoActivity) {
                activity.crossFade(view)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun formatNumber(num: Int): String {
        return when {
            num >= 100000000 -> String.format("%.1f亿", num / 100000000.0)
            num >= 10000 -> String.format("%.1f万", num / 10000.0)
            else -> num.toString()
        }
    }
}