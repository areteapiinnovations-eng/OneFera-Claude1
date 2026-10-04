package com.onefera.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.onefera.app.data.push.PresenceTracker
import com.onefera.app.data.push.PushRegistrar
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OneFeraApplication : Application() {

    @Inject lateinit var presence: PresenceTracker
    @Inject lateinit var push: PushRegistrar

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        presence.start()
        push.start()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            getString(R.string.default_notification_channel_id),
            getString(R.string.default_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Likes, follows, messages and order updates" }
        manager.createNotificationChannel(channel)
    }
}
