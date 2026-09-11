package com.xinjigalaxy.knownotes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xinjigalaxy.knownotes.data.model.SearchHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    /** 最近搜过的词（搜索框聚焦时展示）。 */
    @Query("SELECT * FROM search_history ORDER BY last_used_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = MAX_SUGGESTIONS): Flow<List<SearchHistory>>

    /** 搜得最多的词（「常用」建议）。 */
    @Query(
        "SELECT * FROM search_history ORDER BY use_count DESC, last_used_at DESC LIMIT :limit"
    )
    fun observeTop(limit: Int = MAX_SUGGESTIONS): Flow<List<SearchHistory>>

    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun count(): Int

    @Query("SELECT keyword FROM search_history ORDER BY last_used_at DESC LIMIT :keep")
    suspend fun recentKeywords(keep: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: SearchHistory): Long

    /** 重复搜索：只累加次数、刷新时间，不新增行。 */
    @Query(
        "UPDATE search_history SET use_count = use_count + 1, last_used_at = :now " +
            "WHERE keyword = :keyword"
    )
    suspend fun touch(keyword: String, now: Long)

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    suspend fun delete(keyword: String)

    /** 只保留最近 keep 条，其余清掉（避免无限增长）。 */
    @Query("DELETE FROM search_history WHERE keyword NOT IN (:keywords)")
    suspend fun deleteExcept(keywords: List<String>)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    companion object {
        const val MAX_SUGGESTIONS = 12
    }
}
