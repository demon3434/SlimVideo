package com.example.slimvideo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SlimVideoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "视频瘦身任务"
            val descriptionText = "显示后台视频压缩进度与结果通知"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "slimvideo_compression_channel"
    }
}
