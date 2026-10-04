package com.RobinNotBad.BiliClient.adapter.dynamic

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.model.Topic
import com.RobinNotBad.BiliClient.util.StringUtil

/**
 * 话题广场列表（26.10.04 批次 6 的 C10）。
 *
 * 只负责展示推荐话题，点一下交给 [onTopicClick]（由页面自己决定跳去话题动态列表）。
 */
class TopicAdapter(
    private val topics: List<Topic>,
    private val onTopicClick: (Topic) -> Unit
) : RecyclerView.Adapter<TopicAdapter.TopicHolder>() {

    class TopicHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.topic_name)
        val stats: TextView = itemView.findViewById(R.id.topic_stats)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopicHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.cell_topic, parent, false)
        return TopicHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: TopicHolder, position: Int) {
        val topic = topics[position]
        holder.name.text = topic.name
        holder.stats.text = "${StringUtil.toWan(topic.dynamics)} 动态 · ${StringUtil.toWan(topic.view)} 浏览"
        holder.itemView.setOnClickListener { onTopicClick(topic) }
    }

    override fun getItemCount(): Int = topics.size
}
