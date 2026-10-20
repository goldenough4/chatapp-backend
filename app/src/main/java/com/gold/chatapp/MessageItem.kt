package com.gold.chatapp

import androidx.annotation.Keep
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

@Keep
class MessageItem {
    @get:Exclude
    @set:Exclude
    var documentId: String = ""

    var sender: String = ""
    var text: String = ""
    var voiceUrl: String? = null
    var imageUrl: String? = null
    var videoUrl: String? = null
    var messageType: String = "text"
    var voiceDuration: String? = "00:00"

    // Status Logic: 0=Clock, 1=Sent, 2=Delivered(Grey), 3=Seen(Blue/Green), 4=Failed/Retry
    var status: Int = 0

    var voicePlayed: Boolean = false
    var localPath: String? = null
    var uploadProgress: Int = 0
    var timeRemaining: String? = null
    var localTimestamp: Long = 0L

    @get:PropertyName("senderProfileImageUrl")
    @set:PropertyName("senderProfileImageUrl")
    var senderProfileImageUrl: String = ""

    var amplitudes: List<Float>? = null

    @ServerTimestamp
    var timestamp: Date? = null

    // Required empty constructor for Firebase
    constructor()

    // Full constructor for manual creation
    constructor(
        documentId: String,
        sender: String,
        text: String,
        voiceUrl: String?,
        imageUrl: String?,
        videoUrl: String?,
        messageType: String,
        voiceDuration: String?,
        status: Int,
        voicePlayed: Boolean,
        senderProfileImageUrl: String,
        amplitudes: List<Float>?,
        timestamp: Date?,
        uploadProgress: Int,
        localPath: String?,
        timeRemaining: String? = null,
        localTimestamp: Long = 0L,
    ) {
        this.documentId = documentId
        this.sender = sender
        this.text = text
        this.voiceUrl = voiceUrl
        this.imageUrl = imageUrl
        this.videoUrl = videoUrl
        this.messageType = messageType
        this.voiceDuration = voiceDuration
        this.status = status
        this.voicePlayed = voicePlayed
        this.senderProfileImageUrl = senderProfileImageUrl
        this.amplitudes = amplitudes
        this.timestamp = timestamp
        this.uploadProgress = uploadProgress
        this.localPath = localPath
        this.timeRemaining = timeRemaining
        this.localTimestamp = localTimestamp
    }

    // FIX: Added imageUrl, videoUrl, and text to equals to ensure UI refreshes on all changes
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageItem) return false
        if (documentId != other.documentId) return false
        if (status != other.status) return false
        if (uploadProgress != other.uploadProgress) return false
        if (localTimestamp != other.localTimestamp) return false
        if (timeRemaining != other.timeRemaining) return false
        if (text != other.text) return false
        if (voiceUrl != other.voiceUrl) return false
        if (imageUrl != other.imageUrl) return false
        if (videoUrl != other.videoUrl) return false
        if (localPath != other.localPath) return false
        if (voicePlayed != other.voicePlayed) return false
        return senderProfileImageUrl == other.senderProfileImageUrl
    }

    override fun hashCode(): Int {
        var result = documentId.hashCode()
        result = 31 * result + status
        result = 31 * result + text.hashCode()
        result = 31 * result + (voiceUrl?.hashCode() ?: 0)
        result = 31 * result + (imageUrl?.hashCode() ?: 0)
        result = 31 * result + (videoUrl?.hashCode() ?: 0)
        result = 31 * result + (localPath?.hashCode() ?: 0)
        result = 31 * result + (timeRemaining?.hashCode() ?: 0)
        result = 31 * result + localTimestamp.hashCode()
        result = 31 * result + voicePlayed.hashCode()
        result = 31 * result + uploadProgress
        return result
    }
}
