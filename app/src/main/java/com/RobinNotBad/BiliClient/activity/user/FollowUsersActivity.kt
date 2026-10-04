package com.RobinNotBad.BiliClient.activity.user

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog

import com.RobinNotBad.BiliClient.R
import com.RobinNotBad.BiliClient.activity.InputDialogActivity
import com.RobinNotBad.BiliClient.activity.base.RefreshListActivity
import com.RobinNotBad.BiliClient.adapter.user.FollowGroupAdapter
import com.RobinNotBad.BiliClient.adapter.user.UserListAdapter
import com.RobinNotBad.BiliClient.api.FollowApi
import com.RobinNotBad.BiliClient.model.FollowTag
import com.RobinNotBad.BiliClient.model.UserInfo
import com.RobinNotBad.BiliClient.util.CenterThreadPool
import com.RobinNotBad.BiliClient.util.MsgUtil
import com.RobinNotBad.BiliClient.util.SharedPreferencesUtil

class FollowUsersActivity : RefreshListActivity() {

    private var mid: Long = 0
    private var userList: ArrayList<UserInfo> = ArrayList()
    private var adapter: UserListAdapter? = null
    private var groupAdapter: FollowGroupAdapter? = null
    private var mode: Int = 0
    private var groupMode: Boolean = false

    // 分组增删改（C21）：InputDialogActivity 的回调槽，同一时刻只可能有一个在等结果
    private var pendingInputCallback: ((String) -> Unit)? = null
    private val inputLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = pendingInputCallback
        pendingInputCallback = null
        if (result.resultCode == RESULT_OK) {
            callback?.invoke(result.data?.getStringExtra("input_text") ?: "")
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mode = intent.getIntExtra("mode", 0)
        mid = intent.getLongExtra("mid", -1)

        if (mode < 0 || mode > 1 || mid == -1L) {
            finish()
            return
        }

        val currentUserMid = SharedPreferencesUtil.getLong(SharedPreferencesUtil.mid, 0)
        groupMode = mode == 0 && mid == currentUserMid && SharedPreferencesUtil.getBoolean("follow_group_mode", false)

        setPageName(if (mode == 0) "关注列表" else "粉丝列表")

        recyclerView.setHasFixedSize(true)

        userList = ArrayList()

        if (groupMode) {
            setupGroupBar()
            loadGroupMode()
        } else {
            loadNormalMode()
        }
    }

    /** 分组管理条只在分组模式下点亮（其它列表页看不到这一行） */
    private fun setupGroupBar() {
        findViewById<View>(R.id.groupBar).visibility = View.VISIBLE
        findViewById<TextView>(R.id.groupCreate).setOnClickListener { showCreateGroupDialog() }
    }

    private fun loadNormalMode() {
        CenterThreadPool.run {
            try {
                val result = if (mode == 0) FollowApi.getFollowingList(mid, page, userList) else FollowApi.getFollowerList(mid, page, userList)
                adapter = UserListAdapter(this, userList)
                setOnLoadMoreListener { page -> continueLoading(page) }
                setRefreshing(false)
                setAdapter(adapter!!)

                if (result == 1) {
                    Log.e("debug", "到底了")
                    bottom = true
                }
            } catch (e: Exception) {
                if (e.message != null && (e.message!!.startsWith("22115") || e.message!!.startsWith("22118"))) {
                    finish()
                    MsgUtil.showMsg(e.message!!)
                } else {
                    loadFail(e)
                }
            }
        }
    }

    private fun loadGroupMode() {
        CenterThreadPool.run {
            try {
                val tagList = FollowApi.getFollowTags()
                runOnUiThread {
                    groupAdapter = FollowGroupAdapter(this@FollowUsersActivity)
                    groupAdapter!!.setOnGroupExpandListener { tagid -> loadGroupUsers(tagid) }
                    groupAdapter!!.setOnGroupLongClickListener { tag -> showGroupMenu(tag) }
                    setAdapter(groupAdapter!!)
                    // 空分组也要列出来，否则刚建好的分组在列表里看不到、也就没法改名/删除
                    for (tag in tagList) {
                        groupAdapter!!.addGroup(tag, ArrayList())
                    }
                    groupAdapter!!.notifyDataSetChanged()
                    setRefreshing(false)
                }
            } catch (e: Exception) {
                if (e.message != null && (e.message!!.startsWith("22115") || e.message!!.startsWith("22118"))) {
                    finish()
                    MsgUtil.showMsg(e.message!!)
                } else {
                    loadFail(e)
                }
            }
        }
    }

    // ==================== 分组增删改（26.10.04 批次 7 的 C21） ====================

    /** 默认分组（tagid 0）与特别关注（-10）是系统分组，不能改名/删除 */
    private fun isEditableTag(tag: FollowTag): Boolean = tag.tagid > 0

    private fun showGroupMenu(tag: FollowTag) {
        if (!isEditableTag(tag)) {
            MsgUtil.showMsg("默认分组和特别关注不能改名或删除")
            return
        }
        AlertDialog.Builder(this)
            .setTitle(tag.name)
            .setItems(arrayOf("重命名分组", "删除分组")) { _, which ->
                when (which) {
                    0 -> showRenameGroupDialog(tag)
                    1 -> showDeleteGroupDialog(tag)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCreateGroupDialog() {
        pendingInputCallback = { name ->
            val check = FollowApi.checkTagName(name)
            if (check.isNotEmpty()) {
                MsgUtil.showMsg(check)
            } else {
                CenterThreadPool.run {
                    try {
                        val code = FollowApi.createFollowTag(name)
                        runOnUiThread {
                            if (code == 0) {
                                MsgUtil.showMsg("分组已创建")
                                loadGroupMode()
                            } else {
                                MsgUtil.showMsg(FollowApi.tagErrorMsg(code))
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { MsgUtil.err("创建分组失败", e) }
                    }
                }
            }
        }
        inputLauncher.launch(
            Intent(this, InputDialogActivity::class.java)
                .putExtra("title", "新建分组")
                .putExtra("hint", "请输入分组名（最多 ${FollowApi.TAG_NAME_MAX_LENGTH} 个字）")
        )
    }

    private fun showRenameGroupDialog(tag: FollowTag) {
        pendingInputCallback = { name ->
            val check = FollowApi.checkTagName(name)
            if (check.isNotEmpty()) {
                MsgUtil.showMsg(check)
            } else if (name.trim() == tag.name) {
                // 名字没变就别浪费一次请求
            } else {
                CenterThreadPool.run {
                    try {
                        val code = FollowApi.renameFollowTag(tag.tagid, name)
                        runOnUiThread {
                            if (code == 0) {
                                MsgUtil.showMsg("已重命名为「${name.trim()}」")
                                loadGroupMode()
                            } else {
                                MsgUtil.showMsg(FollowApi.tagErrorMsg(code))
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { MsgUtil.err("重命名分组失败", e) }
                    }
                }
            }
        }
        inputLauncher.launch(
            Intent(this, InputDialogActivity::class.java)
                .putExtra("title", "重命名分组")
                .putExtra("initial_text", tag.name)
        )
    }

    private fun showDeleteGroupDialog(tag: FollowTag) {
        AlertDialog.Builder(this)
            .setTitle("删除分组")
            .setMessage("确定删除「${tag.name}」吗？\n分组里的关注不会取关，只是回到默认分组。")
            .setPositiveButton("删除") { _, _ ->
                CenterThreadPool.run {
                    try {
                        val code = FollowApi.deleteFollowTag(tag.tagid)
                        runOnUiThread {
                            if (code == 0) {
                                MsgUtil.showMsg("分组已删除")
                                loadGroupMode()
                            } else {
                                MsgUtil.showMsg(FollowApi.tagErrorMsg(code))
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { MsgUtil.err("删除分组失败", e) }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun loadGroupUsers(tagid: Int) {
        CenterThreadPool.run {
            try {
                val tagUsers: MutableList<UserInfo> = ArrayList()
                val result = FollowApi.getFollowTagUsers(tagid, 1, tagUsers)
                runOnUiThread {
                    groupAdapter!!.updateGroupUsers(tagid, tagUsers)
                }
                if (result == 0 && tagUsers.size == 20) {
                    loadMoreGroupUsers(tagid, tagUsers.size)
                }
            } catch (e: Exception) {
                Log.e("debug", "加载分组用户失败", e)
            }
        }
    }

    private fun loadMoreGroupUsers(tagid: Int, currentCount: Int) {
        CenterThreadPool.run {
            try {
                val page = (currentCount / 20) + 1
                val tagUsers: MutableList<UserInfo> = ArrayList()
                val result = FollowApi.getFollowTagUsers(tagid, page, tagUsers)
                runOnUiThread {
                    groupAdapter!!.addGroupUsers(tagid, tagUsers)
                }
                if (result == 0 && tagUsers.size == 20) {
                    loadMoreGroupUsers(tagid, currentCount + tagUsers.size)
                }
            } catch (e: Exception) {
                Log.e("debug", "加载分组用户失败", e)
            }
        }
    }

    private fun continueLoading(page: Int) {
        if (groupMode) {
            setRefreshing(false)
            return
        }
        CenterThreadPool.run {
            try {
                val list: MutableList<UserInfo> = ArrayList()
                val result = if (mode == 0) FollowApi.getFollowingList(mid, page, list) else FollowApi.getFollowerList(mid, page, list)
                Log.e("debug", "下一页")
                runOnUiThread {
                    userList.addAll(list)
                    adapter!!.notifyItemRangeInserted(userList.size - list.size, list.size)
                }
                if (result == 1) {
                    Log.e("debug", "到底了")
                    bottom = true
                }
                setRefreshing(false)
            } catch (e: Exception) {
                if (e.message != null && (e.message!!.startsWith("22115") || e.message!!.startsWith("22118"))) {
                    finish()
                    MsgUtil.showMsg(e.message!!)
                } else {
                    loadFail(e)
                }
            }
        }
    }
}