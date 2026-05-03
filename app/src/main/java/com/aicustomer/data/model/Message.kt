package com.aicustomer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 对话消息实体
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: String,
    val role: String,          // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val importanceScore: Float = 0f,  // 重要性评分 0-10
    val isVoice: Boolean = false       // 是否语音消息
)
