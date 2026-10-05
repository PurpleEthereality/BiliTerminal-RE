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
        holder.stats.text = buildStats(topic)
        holder.itemView.setOnClickListener { onTopicClick(topic) }
    }

    override fun getItemCount(): Int = topics.size

    companion object {
        /**
         * 拼「N 讨论 · M 浏览」这类统计文案。抽成纯函数便于 JVM 单测（不依赖 View）。
         *
         * 降级策略（**两个字段都试，绝不显示 "0 讨论" 这种假数据**）：
         * 1. `discuss > 0` → 「N 讨论 · M 浏览」（`pub/search` 的真实形态，26.10.05 起的主力路径）；
         * 2. 否则 `dynamics > 0` → 「N 动态 · M 浏览」（兼容旧 `rcmd` 形态的数据，或将来换回带
         *    `dynamics` 的端点——那时不该因为 `discuss` 恰好为 0 就把动态数丢掉）；
         * 3. 两者都为 0 → 只显示「M 浏览」；
         * 4. 连 `view` 也是 0 → 显示 `-`，**不留空白**（空白会让人以为布局坏了）。
         */
        fun buildStats(topic: Topic): String {
            val view = StringUtil.toWan(topic.view)
            return when {
                topic.discuss > 0 -> "${
                    StringUtil.toWan(topic.discuss)
                } 讨论 · $view 浏览"

                topic.dynamics > 0 -> "${
                    StringUtil.toWan(topic.dynamics)
                } 动态 · $view 浏览"

                topic.view > 0 -> "$view 浏览"
                else -> "-"
            }
        }
    }
}
