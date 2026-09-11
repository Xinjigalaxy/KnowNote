package com.xinjigalaxy.knownotes.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 搜索历史（需求文档 3.4「支持搜索历史与常用筛选条件记忆」）。
 *
 * 用自增主键不合适——同一个词搜多次只该留一条，所以 keyword 直接做主键，
 * 重复搜索走 touch() 累加次数，便于按「常搜」排序给出建议。
 * 这张表是 v1 → v2 迁移（user_version + CREATE TABLE）真实落地的载体。
 */
@Entity(tableName = "search_history")
data class SearchHistory(
    @PrimaryKey
    @ColumnInfo(name = "keyword") val keyword: String,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "use_count") val useCount: Int = 1,
)
