package com.universalchatbot.chat.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.universalchatbot.chat.R
import com.universalchatbot.chat.ui.ChatActivity

/** The notification an agent's reply shows while the chat is closed. */
internal object ChatNotifications {
    const val CHANNEL_ID = "universalchat_replies"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.universalchat_channel_name), NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, data: Map<String, String>, title: String?, body: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val conversationId = data["ucb_conversation_id"].orEmpty()
        val open = PendingIntent.getActivity(
            context, conversationId.hashCode(), ChatActivity.intent(context, conversationId.toLongOrNull()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title ?: context.getString(R.string.universalchat_new_reply))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // One per conversation, so a newer reply replaces the last unread one.
        NotificationManagerCompat.from(context).notify("universalchat-$conversationId", 1, notification)
    }

}
