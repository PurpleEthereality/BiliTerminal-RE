package com.RobinNotBad.BiliClient.adapter.dynamic

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.base.BaseActivity
import com.RobinNotBad.BiliClient.model.Dynamic

/**
 * 话题下的动态列表（26.10.04 批次 6 的 C10）。
 *
 * 与用户空间动态列表（[UserDynamicAdapter]）同构：第 0 位是头部占位，
 * 动态从第 1 位起。之所以保留头部占位，是因为
 * [DynamicHolder.getManageListener] 的列表版重载按 `realPosition + 1`
 * 反推需要刷新的位置（其余动态列表也都是 `data.size + 1`），
 * 少了头部就会刷错行；顺带用它显示话题名。
 */
class TopicDynamicAdapter(
    private val context: Context,
    private val dynamicList: ArrayList<Dynamic>,
    private val topicName: String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.text)
    }

    override fun getItemViewType(position: Int): Int = if (position == 0) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) {
            val view = LayoutInflater.from(context).inflate(R.layout.cell_goto, parent, false)
            return HeaderHolder(view)
        }
        val view = LayoutInflater.from(context).inflate(R.layout.cell_dynamic, parent, false)
        return DynamicHolder(view, context as BaseActivity, false)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderHolder) {
            holder.text.text = "#$topicName"
            holder.text.isClickable = false
            return
        }
        if (holder !is DynamicHolder) return

        val realPosition = position - 1
        if (realPosition < 0 || realPosition >= dynamicList.size) return

        val dynamic = dynamicList[realPosition]
        holder.showDynamic(context, dynamic, true)

        if (dynamic.dynamic_forward != null) {
            if (holder.childDynamicHolder == null) {
                holder.childDynamicHolder =
                    DynamicHolder(holder.cell_dynamic_child, context as BaseActivity, true)
            }
            holder.childDynamicHolder!!.showDynamic(context, dynamic.dynamic_forward!!, true)
            holder.cell_dynamic_child.visibility = View.VISIBLE
        } else {
            holder.cell_dynamic_child.visibility = View.GONE
        }

        holder.item_dynamic_delete!!.setOnLongClickListener(
            DynamicHolder.getManageListener(context as BaseActivity, dynamicList, realPosition, this)
        )
        if (dynamic.canDelete || dynamic.canEdit)
            holder.item_dynamic_delete!!.visibility = View.VISIBLE
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        // 复用前清掉「同 URL 跳过加载」的缓存，否则 recycled 的 holder 会残留上一条动态的头图/配图
        if (holder is DynamicHolder) holder.clearImageCache()
    }

    override fun getItemCount(): Int = dynamicList.size + 1
}
