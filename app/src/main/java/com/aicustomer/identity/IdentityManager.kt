package com.aicustomer.identity

import android.util.Log
import com.aicustomer.data.local.AppDatabase
import com.aicustomer.data.local.IdentityDao
import com.aicustomer.data.model.Identity

class IdentityManager(
    private val database: AppDatabase
) {
    private val identityDao: IdentityDao = database.identityDao()

    val presets: List<Identity> = listOf(
        Identity(
            name = "客服小助手",
            personality = "友善、耐心、专业",
            speakingStyle = "亲切自然，使用敬语，回答简洁明了",
            expertise = "产品咨询、售后服务、常见问题解答",
            greeting = "您好！我是客服小助手，很高兴为您服务，请问有什么可以帮您的？"
        ),
        Identity(
            name = "专业顾问",
            personality = "严谨、权威、细致",
            speakingStyle = "专业术语准确，逻辑清晰，建议有理有据",
            expertise = "行业分析、技术方案、决策建议",
            greeting = "您好，我是您的专业顾问，我将为您提供专业的建议和分析。"
        ),
        Identity(
            name = "品牌代言人",
            personality = "热情、活力、时尚",
            speakingStyle = "充满激情，善于推荐，语言生动有趣",
            expertise = "品牌故事、产品亮点、活动推广",
            greeting = "嗨！欢迎来到我们的品牌世界！让我带你发现更多精彩！"
        ),
        Identity(
            name = "学习伙伴",
            personality = "鼓励、启发、温暖",
            speakingStyle = "循循善诱，善于用比喻解释复杂概念，经常给予鼓励",
            expertise = "知识解答、学习方法、技能提升",
            greeting = "你好呀！我是你的学习伙伴，一起探索新知识吧！有什么想了解的？"
        )
    )

    /** 纯读取，无副作用 */
    suspend fun getActiveIdentity(): Identity? {
        return identityDao.getActive()
    }

    suspend fun getAllIdentities(): List<Identity> {
        return identityDao.getAll()
    }

    /** 激活身份（新建身份自动插入并激活） */
    suspend fun activateIdentity(identity: Identity) {
        identityDao.deactivateAll()
        if (identity.id == 0L) {
            val id = identityDao.insert(identity)
            identityDao.activate(id)
        } else {
            identityDao.activate(identity.id)
        }
    }

    suspend fun createIdentity(identity: Identity): Long {
        return identityDao.insert(identity)
    }

    suspend fun deleteIdentity(identity: Identity) {
        identityDao.delete(identity)
        Log.d("IdentityManager", "Deleted identity: ${identity.name}")
    }

    suspend fun initFromPresetsIfNeeded() {
        val existing = identityDao.getAll()
        if (existing.isEmpty()) {
            for (preset in presets) {
                identityDao.insert(preset)
            }
            // 查询第一个预设的真实ID并激活
            val all = identityDao.getAll()
            if (all.isNotEmpty()) {
                identityDao.activate(all.first().id)
                Log.d("IdentityManager", "Activated default identity: ${all.first().name} (id=${all.first().id})")
            }
        }
    }
}
