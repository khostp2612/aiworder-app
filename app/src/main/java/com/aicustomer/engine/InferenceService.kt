package com.aicustomer.engine

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aicustomer.MainActivity
import com.aicustomer.R

/**
 * 推理前台Service - 类似游戏的CPU调度优先级
 *
 * 作用：
 * 1. 推理时获取前台进程优先级（PROCESS_PRIORITY_IMPORTANT_FOREGROUND），防止系统杀进程
 * 2. 防止CPU被系统降频，确保推理速度最大化
 * 3. 显示推理状态通知，让用户知道AI正在工作
 *
 * HarmonyOS 兼容性说明：
 * - foregroundServiceType 在 API 34+ 设备上使用 dataSync
 * - 在 HarmonyOS (API 31-32) 上不指定类型，避免兼容性问题
 */
class InferenceService : Service() {

    companion object {
        private const val TAG = "InferenceService"
        private const val CHANNEL_ID = "inference_channel"
        private const val NOTIFICATION_ID = 1001

        /** 启动推理前台Service */
        fun start(context: android.content.Context) {
            val intent = Intent(context, InferenceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止推理前台Service */
        fun stop(context: android.content.Context) {
            val intent = Intent(context, InferenceService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("AI正在推理中...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // HarmonyOS 可能不支持 foregroundServiceType，降级到基本 startForeground
            Log.w(TAG, "startForeground with type failed, trying basic mode", e)
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Inference foreground service started")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        Log.i(TAG, "Inference foreground service stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI推理服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AI推理运行状态"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            @Suppress("DEPRECATION")
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI客服")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_silhouette)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
