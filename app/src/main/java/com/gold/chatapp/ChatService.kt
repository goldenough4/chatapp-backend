package com.gold.chatapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

class ChatService : Service() {
    private var listenerRegistration: ListenerRegistration? = null
    private val channelId = "chat_channel_v2"
    private val notificationId = 101

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP_SERVICE") {
            stopSelf()
            return START_NOT_STICKY
        }

        val chatId = intent?.getStringExtra("CHAT_ID") ?: return START_NOT_STICKY

        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Chat Synced")
            .setContentText("Ensuring instant message delivery...")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Minimal intrusion
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        // Android 14 Fix: Explicitly declare Service Type
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId,
                    notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                    },
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e("CHAT_SERVICE", "Failed to start foreground: ${e.message}")
        }

        startListening(chatId)

        return START_STICKY
    }

    private fun startListening(chatId: String) {
        listenerRegistration?.remove()

        // Optimized Listener: We only care about new additions
        listenerRegistration = FirebaseFirestore.getInstance()
            .collection("chats").document(chatId).collection("messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { _, error ->
                if (error != null) {
                    Log.e("CHAT_SERVICE", "Firestore error: ${error.message}")
                    return@addSnapshotListener
                }

                // Trigger UI Refresh
                val updateIntent = Intent("com.gold.chatapp.UPDATE_CHAT")
                updateIntent.setPackage(packageName) // Security: only send to our app
                sendBroadcast(updateIntent)
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Chat Background Sync",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Keeps chat messages up to date"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d("CHAT_SERVICE", "Service Destroyed - Cleaning up listeners")
        listenerRegistration?.remove()
        super.onDestroy()
    }
}
