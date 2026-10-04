package com.RobinNotBad.BiliClient.adapter.video

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.video.series.SeriesInfoActivity
import com.RobinNotBad.BiliClient.model.Series
import com.RobinNotBad.BiliClient.model.VideoCard

class SeriesCardAdapter(
    val context: Context,
    // 26.10.04 批次 3（B5）：类型由 List 改为 MutableList —— 翻页时要往同一个列表里追加，
    // 否则 notifyItemRangeInserted 报出的数量与 getItemCount() 对不上。
    val seasonList: MutableList<Series>
) : RecyclerView.Adapter<VideoCardHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoCardHolder {
        val view = LayoutInflater.from(this.context).inflate(R.layout.cell_video_list, parent, false)
        return VideoCardHolder(view)
    }

    override fun onBindViewHolder(holder: VideoCardHolder, position: Int) {
        if (position < 0 || position >= seasonList.size)
            return
        val series = seasonList[position]

        val videoCard = VideoCard(series.title, series.intro, series.total, series.cover, 0, "", "series")
        holder.showVideoCard(videoCard, context)

        holder.itemView.setOnClickListener {
            val intent = Intent(context, SeriesInfoActivity::class.java)
            intent.putExtra("type", series.type)
            intent.putExtra("mid", series.mid)
            intent.putExtra("sid", series.id)
            intent.putExtra("name", series.title)
            // 详情页头部的封面/简介/总数此前恒为空：这三个字段在列表数据里本来就有，
            // 只是没传过去，导致详情页永远显示占位图、"这里没有简介哦"、"共"。
            intent.putExtra("cover", series.cover)
            intent.putExtra("intro", series.intro)
            intent.putExtra("total", series.total)
            context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int {
        return seasonList.size
    }
}