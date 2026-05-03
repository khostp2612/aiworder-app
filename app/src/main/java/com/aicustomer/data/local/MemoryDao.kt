package com.aicustomer.data.local

import androidx.room.*
import com.aicustomer.data.model.Memory

@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(memory: Memory): Long

    @Update
    suspend fun update(memory: Memory)

    @Delete
    suspend fun delete(memory: Memory)

    @Query("SELECT * FROM memories WHERE type = :type ORDER BY importanceScore DESC")
    suspend fun getByType(type: String): List<Memory>

    @Query("SELECT * FROM memories WHERE type = 'long_term' ORDER BY importanceScore DESC LIMIT :limit")
    suspend fun getTopLongTerm(limit: Int = 1000): List<Memory>

    @Query("SELECT * FROM memories WHERE type = 'short_term' AND createdAt > :since ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentShortTerm(since: Long, limit: Int = 20): List<Memory>

    @Query("SELECT * FROM memories WHERE importanceScore >= :minScore ORDER BY importanceScore DESC")
    suspend fun getByMinImportance(minScore: Float): List<Memory>

    @Query("SELECT COUNT(*) FROM memories WHERE type = :type")
    suspend fun countByType(type: String): Int

    @Query("DELETE FROM memories WHERE type = 'short_term' AND createdAt < :before")
    suspend fun cleanOldShortTerm(before: Long)

    @Query("DELETE FROM memories WHERE type = :type")
    suspend fun deleteAllByType(type: String)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE memories SET accessedAt = :accessedAt, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun touchAccess(id: Long, accessedAt: Long)

    @Query("SELECT * FROM memories WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Memory>
}
