package com.aicustomer.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 记忆实体 - 存储长期和短期记忆
 */
@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String,           // "long_term" | "short_term"
    val content: String,        // 记忆内容
    val source: String,         // 来源对话ID
    val importanceScore: Float, // 重要性评分 0-10
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray? = null,  // 向量嵌入
    val tags: String = "",      // 标签，逗号分隔
    val createdAt: Long = System.currentTimeMillis(),
    val accessedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0    // 被检索次数
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Memory
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** FloatArray -> ByteArray (用于Room存储) */
        fun FloatArray.toByteArray(): ByteArray {
            val buffer = java.nio.ByteBuffer.allocate(size * 4)
            buffer.asFloatBuffer().put(this)
            return buffer.array()
        }

        /** ByteArray -> FloatArray (从Room读取) */
        fun ByteArray.toFloatArray(): FloatArray {
            val buffer = java.nio.ByteBuffer.wrap(this)
            val floatBuffer = buffer.asFloatBuffer()
            val result = FloatArray(floatBuffer.remaining())
            floatBuffer.get(result)
            return result
        }
    }
}
