package com.gold.chatapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        // Optimization: Cache B2 download token for high-speed notifications
        private var cachedToken: String? = null
        private var tokenExpiry: Long = 0L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val b2KeyId = "005cb4d249b27e40000000002"
    private val b2AppKey = "K005bZcXIuOMKUjwp2gj6WXuGM7dxdI"

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCM", "Message received from: ${remoteMessage.from}")
        Log.d("FCM", "Payload: ${remoteMessage.data}")

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "ChatApp:WakeLock",
        )

        wakeLock.acquire(10000L)

        try {
            val type = remoteMessage.data["type"] ?: ""
            if (type == "call") {
                val callerPhone = remoteMessage.data["caller_phone"] ?: ""
                val callerName = remoteMessage.data["caller_name"] ?: callerPhone
                val callType = remoteMessage.data["call_type"] ?: "voice"
                val callId = remoteMessage.data["call_id"] ?: ""

                val callIntent = Intent(this, CallActivity::class.java).apply {
                    putExtra("other_phone", callerPhone)
                    putExtra("contact_name", callerName)
                    putExtra("call_type", callType)
                    putExtra("is_incoming", true)
                    putExtra("call_id", callId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }

                val pendingIntent = PendingIntent.getActivity(
                    this,
                    callerPhone.hashCode(),
                    callIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )

                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val channelId = "call_channel_v2"
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build()

                    val channel = NotificationChannel(
                        channelId,
                        "Incoming Calls",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Incoming Voice & Video Calls"
                        enableLights(true)
                        enableVibration(true)
                        setSound(ringtoneUri, audioAttributes)
                        lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                    }
                    notificationManager.createNotificationChannel(channel)
                }

                val notification = NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.drawable.ic_stat_notification)
                    .setContentTitle("Incoming $callType call")
                    .setContentText("$callerName is calling...")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(pendingIntent, true)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build()

                notificationManager.notify(callerPhone.hashCode(), notification)
                return
            }

            val senderPhone = remoteMessage.data["sender_phone"] ?: ""
            val senderNamePayload = remoteMessage.data["sender_name"] ?: ""
            val messageBody = remoteMessage.data["body"] ?: "New Message"
            val chatId = remoteMessage.data["chatId"] ?: ""
            val profilePicUrl = remoteMessage.data["sender_profile_pic"] ?: ""

            if (senderPhone.isNotEmpty()) {
                val contactName = getLocalContactName(senderPhone, senderNamePayload)
                val notificationId = senderPhone.hashCode()

                serviceScope.launch {
                    var largeIcon: Bitmap? = null
                    if (profilePicUrl.isNotEmpty()) {
                        val token = getB2DownloadToken()
                        largeIcon = getBitmapFromB2(profilePicUrl, token)
                    }

                    withContext(Dispatchers.Main) {
                        sendNotification(
                            contactName,
                            messageBody,
                            senderPhone,
                            largeIcon,
                            notificationId,
                        )
                    }
                }

                val updateIntent = Intent("com.gold.chatapp.UPDATE_CHAT")
                updateIntent.putExtra("chatId", chatId)
                updateIntent.setPackage(packageName)
                sendBroadcast(updateIntent)

                updateStatusToDelivered(senderPhone)
            }
        } catch (e: Exception) {
            Log.e("NOTIF_ERROR", "Failure: ${e.message}")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun getB2DownloadToken(): String? {
        // Optimization: Use cached token if valid to prevent network delay on every message
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < tokenExpiry) {
            return cachedToken
        }

        return try {
            val authString = "$b2KeyId:$b2AppKey"
            val encodedAuth = Base64.encodeToString(authString.toByteArray(), Base64.NO_WRAP)
            val url = URL("https://api.backblazeb2.com/b2api/v2/b2_authorize_account")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Basic $encodedAuth")
            if (conn.responseCode == 200) {
                val bodyStr = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(bodyStr)
                val token = json.getString("authorizationToken")
                
                // Cache for 23 hours (B2 tokens usually last 24h)
                cachedToken = token
                tokenExpiry = now + (23 * 60 * 60 * 1000L)
                token
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("FCM", "B2 token fetch failed: ${e.message}")
            null
        }
    }

    private suspend fun getBitmapFromB2(imageUrl: String, token: String?): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(imageUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    doInput = true
                    connectTimeout = 5000
                    if (!token.isNullOrEmpty()) {
                        setRequestProperty("Authorization", token)
                    }
                    connect()
                }
                if (connection.responseCode == 200) {
                    BitmapFactory.decodeStream(connection.inputStream)
                } else {
                    null
                }
            } catch (ex: Exception) {
                Log.e("B2_BITMAP_ERROR", "Error decoding: ${ex.message}")
                null
            }
        }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
        }
        val rect = android.graphics.Rect(
            (bitmap.width - size) / 2,
            (bitmap.height - size) / 2,
            (bitmap.width + size) / 2,
            (bitmap.height + size) / 2,
        )
        val cropped = if (bitmap.width != size || bitmap.height != size) {
            Bitmap.createBitmap(bitmap, rect.left, rect.top, size, size)
        } else {
            bitmap
        }
        paint.shader = android.graphics.BitmapShader(
            cropped,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP,
        )
        val rectF = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawOval(rectF, paint)
        return output
    }

    private fun getDefaultUserBitmap(): Bitmap {
        return try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_default_user)
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable?.setBounds(0, 0, canvas.width, canvas.height)
            drawable?.draw(canvas)
            getCircularBitmap(bitmap)
        } catch (_: Exception) {
            Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        }
    }

    private fun sendNotification(
        title: String,
        messageBody: String,
        senderPhone: String,
        largeIcon: Bitmap?,
        notificationId: Int,
    ) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "chat_messages_v5"
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val vPattern = longArrayOf(0, 500, 200, 500)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = NotificationChannel(
                channelId,
                "Urgent Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Chat Notifications"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = vPattern
                setSound(defaultSoundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setShowBadge(true)
                setBypassDnd(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("sender_phone", senderPhone)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val circularIconBitmap = if (largeIcon != null) {
            getCircularBitmap(largeIcon)
        } else {
            getDefaultUserBitmap()
        }

        val personIcon = IconCompat.createWithBitmap(circularIconBitmap)

        val displayTitle = if (title.isNotBlank()) title else if (senderPhone.isNotBlank()) senderPhone else "Message"
        val shortcutIdKey = if (senderPhone.isNotBlank()) senderPhone else "chat_shortcut"

        val contactPerson = Person.Builder()
            .setName(displayTitle)
            .setIcon(personIcon)
            .setKey(shortcutIdKey)
            .setImportant(true)
            .build()

        val shortcut = ShortcutInfoCompat.Builder(this, shortcutIdKey)
            .setShortLabel(displayTitle)
            .setLongLived(true)
            .setIcon(personIcon)
            .setIntent(intent)
            .setPerson(contactPerson)
            .build()

        try {
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        } catch (ex: Exception) {
            Log.e("FCM_SHORTCUT", "Error pushing shortcut: ${ex.message}")
        }

        val localUser = Person.Builder()
            .setName("Me")
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(localUser)
            .addMessage(messageBody, System.currentTimeMillis(), contactPerson)
            .setGroupConversation(false)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setLargeIcon(circularIconBitmap)
            .setStyle(messagingStyle)
            .setContentTitle(displayTitle)
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setVibrate(vPattern)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setShortcutId(shortcutIdKey)
            .setShortcutInfo(shortcut)
            .setOnlyAlertOnce(false)

        notificationManager.notify(notificationId, builder.build())
    }

    private fun updateStatusToDelivered(senderPhone: String) {
        val prefs = getSharedPreferences("chat_app_prefs", Context.MODE_PRIVATE)
        val myPhone = prefs.getString("user_phone", "") ?: ""
        if (myPhone.isNotEmpty()) {
            val sorted = listOf(myPhone, senderPhone).sorted()
            val chatDocId = "chat_${sorted[0]}_${sorted[1]}"

            FirebaseFirestore.getInstance().collection("chats").document(chatDocId)
                .collection("messages")
                .whereEqualTo("sender", senderPhone)
                .whereEqualTo("status", 1)
                .get()
                .addOnSuccessListener { snapshots ->
                    if ((snapshots != null) && (!snapshots.isEmpty)) {
                        val batch = FirebaseFirestore.getInstance().batch()
                        for (doc in snapshots) {
                            batch.update(doc.reference, "status", 2)
                        }
                        batch.commit().addOnSuccessListener {
                            Log.d("FCM", "Batch status 2 update successful for $senderPhone")
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("FCM", "Status 2 update failed: ${e.message}")
                }
        }
    }

    private fun getLocalContactName(phoneNumber: String, payloadName: String = ""): String {
        val prefs = getSharedPreferences("contacts_prefs", Context.MODE_PRIVATE)
        val mapString = prefs.getString("contacts_map", "") ?: ""
        if (mapString.isNotEmpty()) {
            val pairs = mapString.split(";")
            for (pair in pairs) {
                val parts = pair.split("|")
                if ((parts.size == 2) && (parts[1].trim() == phoneNumber.trim())) return parts[0]
            }
        }
        if (payloadName.isNotEmpty()) return payloadName
        return phoneNumber
    }

    override fun onNewToken(token: String) {
        val prefs = getSharedPreferences("chat_app_prefs", Context.MODE_PRIVATE)
        val myPhone = prefs.getString("user_phone", "") ?: ""
        if (myPhone.isNotEmpty()) {
            FirebaseFirestore.getInstance().collection("users")
                .document(myPhone).update("fcmToken", token)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
