package com.RobinNotBad.BiliClient.helper.sql

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.RobinNotBad.BiliClient.util.MsgUtil

class DownloadSqlHelper(context: Context?) : SQLiteOpenHelper(context, "download.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table download(id INTEGER primary key autoincrement," +
                "type TEXT," +
                "state TEXT," +
                "aid BIGINT," +
                "cid BIGINT," +
                "qn INTEGER," +
                "title TEXT," +
                "child TEXT," +
                "cover TEXT," +
                "download_type TEXT DEFAULT 'video'," +
                "audio_url TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion >= newVersion) return
        try {
            // 逐级迁移：任何旧版本升上来都先尝试 ALTER 补列，保住历史下载记录（续传进度、任务列表）；
            // 此前是 oldVersion == 3 && newVersion == 4 的精确相等判断，0/1/2 版本升上来会直接 drop table，
            // 用户升级 App 就丢光全部下载记录。
            // 只有 ALTER 本身失败（列已存在/表结构损坏）才降级重建，此时已无法保住数据。
            if (oldVersion < 4) {
                try {
                    db.execSQL("ALTER TABLE download ADD COLUMN download_type TEXT DEFAULT 'video'")
                    db.execSQL("ALTER TABLE download ADD COLUMN audio_url TEXT")
                } catch (e: Throwable) {
                    db.execSQL("drop table if exists download")
                    onCreate(db)
                }
            }
        } catch (e: Throwable) {
            MsgUtil.err(e)
        }
    }
}