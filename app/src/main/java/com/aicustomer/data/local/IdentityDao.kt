package com.aicustomer.data.local

import androidx.room.*
import com.aicustomer.data.model.Identity

@Dao
interface IdentityDao {

    @Insert
    suspend fun insert(identity: Identity): Long

    @Update
    suspend fun update(identity: Identity)

    @Delete
    suspend fun delete(identity: Identity)

    @Query("SELECT * FROM identities WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): Identity?

    @Query("SELECT * FROM identities ORDER BY createdAt DESC")
    suspend fun getAll(): List<Identity>

    @Query("UPDATE identities SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE identities SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)
}
