package com.aicustomer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 身份配置实体
 */
@Entity(tableName = "identities")
data class Identity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,               // 角色名，如"客服小助手"
    val personality: String,         // 性格特征
    val speakingStyle: String,       // 说话风格
    val expertise: String,           // 专业领域
    val taboos: String = "",         // 禁忌话题
    val greeting: String = "",       // 开场白
    val isActive: Boolean = false,   // 是否当前激活身份
    val createdAt: Long = System.currentTimeMillis()
)
