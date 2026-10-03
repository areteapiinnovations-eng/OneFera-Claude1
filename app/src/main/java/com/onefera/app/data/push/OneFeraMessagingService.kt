package com.onefera.app.data.push

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.onefera.app.MainActivity
import com.onefera.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives FCM messages. In the background Android shows the notification itself; in the
 * foreground we show it here, unless it's a chat the user already has open.
 */
@AndroidEntryPoint
class OneFeraMessagingService : FirebaseMessagingService() {

    @Inject lateinit var registrar: PushRegistrar
    @Inject lateinit var activeConversation: ActiveConversation

    override fun onNewToken(token: String) {
        registrar.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val open = data[DeepLinks.EXTRA]
        if (open != null && open == "chat:${activeConversation.id}") return
        val title = message.notification?.title ?: data["title"] ?: getString(R.string.app_name)
        val body = message.notification?.body ?: data["body"] ?: return
        show(title, body, open, tag = data["conversationId"] ?: data["postId"])
    }

    @SuppressLint("MissingPermission") // checked just below
    private fun show(title: String, body: String, open: String?, tag: String?) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DeepLinks.EXTRA, open)
        }
        val pending = PendingIntent.getActivity(this, (open ?: body).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, getString(R.string.default_notification_channel_id))
            .setSmallIcon(R.drawable.ic_stat_onefera)
            .setColor(ContextCompat.getColor(this, R.color.onefera_violet))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { manager.notify(tag, 0, notification) }
    }
}
