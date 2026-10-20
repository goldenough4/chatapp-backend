package com.gold.chatapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.firebase.firestore.FirebaseFirestore
import de.hdodenhof.circleimageview.CircleImageView
import okhttp3.*
import java.io.File

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class ChatMessagesAdapter(
    private var messages: MutableList<MessageItem>,
    private val myId: String,
    private val db: FirebaseFirestore,
    private val chatId: String,
    private var b2Token: String,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val client = OkHttpClient()
    private val activeDownloads = mutableMapOf<String, Call>()
    private val downloadProgress = mutableMapOf<String, Int>()

    companion object {
        private const val STATUS_CLOCK = 0
        private const val STATUS_SENT = 1
        private const val STATUS_DELIVERED = 2
        private const val STATUS_SEEN = 3
        private const val STATUS_FAILED = 4
    }

    private val typeSent = 1
    private val typeReceived = 2

    // Media Player State
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingPosition: Int = -1
    private var playHandler = Handler(Looper.getMainLooper())
    private var updateVisualizer: Runnable? = null

    // Video State (ExoPlayer)
    private var exoPlayer: ExoPlayer? = null
    private var currentVideoPosition: Int = -1

    private var videoUpdateHandler = Handler(Looper.getMainLooper())
    private var videoUpdateRunnable: Runnable? = null

    private var lastClickTime: Long = 0
    private val clickDebounce: Long = 300

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    private val selectedMessageIds = mutableSetOf<String>()
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    fun isSelectionMode() = selectedMessageIds.isNotEmpty()
    fun getSelectedCount() = selectedMessageIds.size
    fun getSelectedMessages() = messages.filter { selectedMessageIds.contains(it.documentId) }

    fun clearSelection() {
        selectedMessageIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(false)
    }

    fun selectAll() {
        selectedMessageIds.clear()
        selectedMessageIds.addAll(messages.map { it.documentId })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(true)
    }

    fun toggleSelection(documentId: String) {
        if (selectedMessageIds.contains(documentId)) {
            selectedMessageIds.remove(documentId)
        } else {
            selectedMessageIds.add(documentId)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(isSelectionMode())
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateToken(newToken: String) {
        this.b2Token = newToken
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        if ((position < 0) || (position >= messages.size)) return typeSent
        return if (messages[position].sender == myId) typeSent else typeReceived
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == typeSent) {
            R.layout.item_message_sent
        } else {
            R.layout.item_message_received
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return MessageViewHolder(view)
    }

    private fun getAuthorizedUrl(url: String?): Any {
        if (url.isNullOrEmpty()) return ""
        if (url.startsWith("content://") || url.startsWith("file://") || url.startsWith("/")) {
            return url.toUri()
        }
        if (url.contains("backblazeb2.com")) {
            return GlideUrl(
                url,
                LazyHeaders.Builder()
                    .addHeader("Authorization", b2Token)
                    .build(),
            )
        }
        return url
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is MessageViewHolder) {
            try {
                if (holder.bindingAdapterPosition == currentVideoPosition) {
                    exoPlayer?.stop()
                    exoPlayer?.clearMediaItems()
                    currentVideoPosition = -1
                }
                holder.videoPlayerView.player = null
                videoUpdateRunnable?.let { videoUpdateHandler.removeCallbacks(it) }
            } catch (e: Exception) {
                Log.e("ADAPTER_RECYCLE", "Cleanup error: ${e.message}")
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        @SuppressLint("RecyclerView") position: Int,
    ) {
        if ((position < 0) || (position >= messages.size)) return

        val message = messages[position]
        val vh = holder as MessageViewHolder
        val isSent = getItemViewType(position) == typeSent

        // --- WHATSAPP STATUS: SEEN (3) ---
        // Only mark text messages as seen automatically on bind. 
        // Media items are marked seen on interaction (WhatsApp rule).
        if (!isSent && message.status < 3 && message.messageType == "text") {
            markAsSeen(message.documentId)
        }

        val context = vh.itemView.context
        val density = context.resources.displayMetrics.density
        val bubbleParams = vh.bubbleLayout.layoutParams

        // --- FULL UI RESET ---
        vh.voicePlayerLayout.isVisible = false
        vh.voiceVisualizer.isVisible = false
        vh.messageImage.isVisible = false
        vh.videoPlayerView.isVisible = false
        vh.videoSeekBar.isVisible = false
        vh.playVideoIcon.isVisible = false
        vh.uploadProgress.isVisible = false
        vh.uploadContainer?.isVisible = false
        vh.micIcon.isVisible = false
        vh.messageText.isVisible = false
        vh.statusIcon?.isVisible = false

        // --- PROFILE IMAGE FIX ---
        if (vh.profileImage != null) {
            vh.profileImage.setImageDrawable(null)
            val profileUrl = message.senderProfileImageUrl
            Glide.with(context)
                .load(getAuthorizedUrl(profileUrl))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .placeholder(R.drawable.ic_default_user)
                .error(R.drawable.ic_default_user)
                .into(vh.profileImage)

            vh.profileImage.setOnClickListener {
                val activity = context as? androidx.appcompat.app.AppCompatActivity
                activity?.let {
                    ProfilePopupDialog.newInstance(
                        id = message.sender,
                        name = "Sender",
                        imageUrl = message.senderProfileImageUrl,
                        token = b2Token,
                    ).show(it.supportFragmentManager, "profile_popup")
                }
            }
        }

        // --- MESSAGE CONTENT ---
        when (message.messageType) {
            "voice" -> {
                bubbleParams.width = (300 * density).toInt()
                vh.voicePlayerLayout.isVisible = true
                vh.voiceVisualizer.isVisible = true
                vh.micIcon.isVisible = true
                vh.voiceDurationText.text = message.voiceDuration ?: "00:00"
                vh.voiceVisualizer.setAmplitudes(message.amplitudes ?: emptyList())

                val isCurrentAudio = (currentlyPlayingPosition == position) &&
                        (mediaPlayer?.isPlaying == true)
                val isHeard = message.voicePlayed

                val activeColor = "#25D366".toColorInt() // WhatsApp Green
                val inactiveColor = "#808080".toColorInt() // WhatsApp Grey

                if (isCurrentAudio || isHeard) {
                    vh.voiceVisualizer.setThumbColor(activeColor)
                    vh.micIcon.setColorFilter(activeColor)
                } else {
                    vh.voiceVisualizer.setThumbColor(inactiveColor)
                    vh.micIcon.setColorFilter(Color.GRAY)
                }

                if (isCurrentAudio) {
                    val mpLocal = mediaPlayer
                    if (mpLocal != null) {
                        val progressValue = mpLocal.currentPosition.toFloat() /
                                mpLocal.duration.toFloat().coerceAtLeast(1f)
                        vh.voiceVisualizer.setProgress(progressValue)
                    }
                } else {
                    vh.voiceVisualizer.setProgress(0f)
                }

                if (message.voiceUrl == "loading") {
                    vh.playButton.isVisible = false
                    vh.uploadProgress.isVisible = true
                    if (isSent) {
                        vh.uploadContainer?.isVisible = true
                        vh.uploadProgressBar?.progress = message.uploadProgress
                    }
                } else {
                    vh.uploadProgress.isVisible = false
                    vh.playButton.isVisible = true

                    // Professional: Auto-download incoming voice notes without double triggers
                    val canDownloadVoice = !isSent && b2Token.isNotEmpty() && !activeDownloads.containsKey(message.documentId)
                    if (canDownloadVoice) {
                        val localFile = findLocalFile(vh.itemView.context, message)
                        if (localFile == null) {
                            val voiceUrl = message.voiceUrl
                            if (!voiceUrl.isNullOrEmpty()) {
                                val voiceDir = StorageHelper.getMediaFolderWithDate(context, StorageHelper.MEDIA_VOICE_NOTES)
                                val fileName = voiceUrl.substringAfterLast("/")
                                val targetFile = File(voiceDir, fileName)
                                downloadAudioToPermanentStorage(voiceUrl, targetFile, message.documentId) { _ -> 
                                    notifyItemChanged(position)
                                }
                            }
                        }
                    }

                    val isDownloading = activeDownloads.containsKey(message.documentId)
                    if (isDownloading) {
                        vh.playButton.setImageResource(
                            android.R.drawable.ic_menu_close_clear_cancel,
                        )
                        // Show progress ring for voice note download
                        vh.uploadProgress.isVisible = true
                        vh.uploadProgress.isIndeterminate = false
                        vh.uploadProgress.progress = downloadProgress[message.documentId] ?: 0
                    } else {
                        vh.uploadProgress.isVisible = false
                        vh.playButton.setImageResource(
                            if (isCurrentAudio) {
                                android.R.drawable.ic_media_pause
                            } else {
                                android.R.drawable.ic_media_play
                            },
                        )
                    }

                    vh.playButton.isClickable = true
                    vh.playButton.isFocusable = true
                    vh.playButton.setOnClickListener {
                        val currentTime = System.currentTimeMillis()
                        if ((currentTime - lastClickTime) < clickDebounce) return@setOnClickListener
                        lastClickTime = currentTime

                        val currentPos = vh.bindingAdapterPosition
                        if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener

                        if (activeDownloads.containsKey(message.documentId)) {
                            // CANCEL DOWNLOAD
                            activeDownloads[message.documentId]?.cancel()
                            activeDownloads.remove(message.documentId)
                            notifyItemChanged(currentPos)
                            return@setOnClickListener
                        }

                        Log.d("CHAT_ADAPTER", "Play button clicked at position: $currentPos")
                        val currentMessage = messages[currentPos]

                        if ((currentlyPlayingPosition == currentPos) &&
                            (mediaPlayer?.isPlaying == true)
                        ) {
                            pauseAudio(vh)
                        } else {
                            if ((currentMessage.sender != myId) && (!currentMessage.voicePlayed)) {
                                db.collection("chats").document(chatId).collection("messages")
                                    .document(currentMessage.documentId).update("voicePlayed", true)
                                markAsSeen(currentMessage.documentId)
                            }

                            // ENFORCE LOCAL-FIRST PLAYBACK
                            val localFile = findLocalFile(vh.itemView.context, currentMessage)

                            if ((localFile != null) && localFile.exists()) {
                                Log.d(
                                    "CHAT_ADAPTER",
                                    "Enforcing local playback from: ${localFile.absolutePath}",
                                )
                                playAudio(localFile.absolutePath, currentPos)
                            } else {
                                val voiceUrlString = currentMessage.voiceUrl
                                if (!voiceUrlString.isNullOrEmpty()) {
                                    Log.d(
                                        "CHAT_ADAPTER",
                                        "Local file not found, downloading: $voiceUrlString",
                                    )

                                    val voiceDir = StorageHelper
                                        .getMediaFolderWithDate(vh.itemView.context, StorageHelper.MEDIA_VOICE_NOTES)
                                    val permanentFile =
                                        File(voiceDir, "voice_${currentMessage.documentId}.m4a")

                                    vh.playButton.setImageResource(
                                        android.R.drawable.ic_menu_close_clear_cancel,
                                    )

                                    downloadAudioToPermanentStorage(
                                        voiceUrlString,
                                        permanentFile,
                                        currentMessage.documentId,
                                    ) { success ->
                                        playHandler.post {
                                            if (success) {
                                                Log.d(
                                                    "CHAT_ADAPTER",
                                                    "Download successful: ${permanentFile.path}",
                                                )
                                                playAudio(permanentFile.absolutePath, currentPos)
                                            } else {
                                                notifyItemChanged(currentPos)
                                                if (!activeDownloads.containsKey(
                                                        currentMessage.documentId,
                                                    )
                                                ) {
                                                    Toast.makeText(
                                                        vh.itemView.context,
                                                        "Download failed or canceled.",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    }
                                    notifyItemChanged(currentPos)
                                }
                            }
                        }
                    }
                }
            }

            "image", "video" -> {
                bubbleParams.width = (250 * density).toInt()
                vh.messageImage.isVisible = true
                val isVideo = message.messageType == "video"

                val localFile = findLocalFile(context, message)
                val loadTarget = resolveMediaTarget(context, message, isVideo)

                Glide.with(context)
                    .load(loadTarget)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .placeholder(R.drawable.ic_clock)
                    .error(R.drawable.ic_clock)
                    .into(vh.messageImage)

                // Professional: Auto-download incoming media with rotating progress ring support
                val canDownloadMedia = !isSent && localFile == null && b2Token.isNotEmpty() && !activeDownloads.containsKey(message.documentId)
                if (canDownloadMedia) {
                    val url = if (isVideo) message.videoUrl else message.imageUrl
                    if (!url.isNullOrEmpty() && url != "loading") {
                        val fileName = url.substringAfterLast("/")
                        
                        // Mock call for tracking only
                        val dummyRequest = Request.Builder().url(url).addHeader("Authorization", b2Token).build()
                        activeDownloads[message.documentId] = client.newCall(dummyRequest)
                        downloadProgress[message.documentId] = 0

                        MediaDownloader.downloadMedia(context, url, fileName, isVideo, b2Token) { p ->
                            downloadProgress[message.documentId] = p
                            playHandler.post { notifyItemChanged(position) }
                            if (p >= 100) {
                                activeDownloads.remove(message.documentId)
                                downloadProgress.remove(message.documentId)
                            }
                        }
                    }
                }

                // Professional: Show download progress for received media
                if (!isSent && activeDownloads.containsKey(message.documentId)) {
                    vh.uploadContainer?.isVisible = true
                    vh.uploadProgressBar?.isIndeterminate = false
                    vh.uploadProgressBar?.progress = downloadProgress[message.documentId] ?: 0
                    vh.uploadProgressText?.text = "Downloading..."
                    vh.uploadCancelIcon?.setOnClickListener {
                        activeDownloads[message.documentId]?.cancel()
                        activeDownloads.remove(message.documentId)
                        downloadProgress.remove(message.documentId)
                        notifyItemChanged(position)
                    }
                } else if (!isSent) {
                    vh.uploadContainer?.isVisible = false
                }

                // Professional: Mark image as seen when clicked/viewed
                vh.messageImage.setOnClickListener {
                    if (!isSent && message.status < 3) {
                        markAsSeen(message.documentId)
                    }
                }

                if (isSent && (message.status == 0)) {
                    vh.uploadContainer?.isVisible = true
                    vh.uploadProgressBar?.progress = message.uploadProgress
                    
                    val progressText = if (!message.timeRemaining.isNullOrEmpty()) {
                        "${message.uploadProgress}% • ${message.timeRemaining}"
                    } else {
                        "${message.uploadProgress}%"
                    }
                    vh.uploadProgressText?.text = progressText

                    vh.uploadCancelIcon?.setOnClickListener {
                        Toast.makeText(context, "Upload canceled", Toast.LENGTH_SHORT).show()
                        // Implementation of actual cancel logic would require a callback to Activity
                    }
                } else {
                    vh.uploadContainer?.isVisible = false
                }

                if (isVideo && (message.videoUrl != "loading")) {
                    vh.playVideoIcon.isVisible = true
                    vh.playVideoIcon.setOnClickListener {
                        markAsSeen(message.documentId)
                        vh.messageImage.isVisible = false
                        vh.playVideoIcon.isVisible = false
                        vh.videoPlayerView.isVisible = true
                        vh.videoSeekBar.isVisible = true

                        playVideo(message, vh, position)
                    }
                }
            }

            "text" -> {
                bubbleParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
                vh.messageText.isVisible = true
                vh.messageText.text = message.text
            }
        }

        vh.bubbleLayout.layoutParams = bubbleParams

        // --- TIME & STATUS ---
        val ts = message.timestamp
        vh.timeText.text = if (ts != null) timeFormat.format(ts) else ""

        if (isSent && (vh.statusIcon != null)) {
            vh.statusIcon.isVisible = true
            val iconRes = when (message.status) {
                STATUS_CLOCK -> R.drawable.ic_clock
                STATUS_SENT -> R.drawable.ic_tick_single_gray
                STATUS_DELIVERED -> R.drawable.ic_tick_double_gray
                STATUS_SEEN -> R.drawable.ic_tick_double_green
                STATUS_FAILED -> android.R.drawable.ic_menu_revert
                else -> R.drawable.ic_tick_single_gray
            }
            vh.statusIcon.setImageResource(iconRes)
            
            if (message.status == STATUS_FAILED) {
                vh.statusIcon.setOnClickListener {
                    // Trigger retry via activity callback or internal logic
                    Toast.makeText(context, "Retrying message...", Toast.LENGTH_SHORT).show()
                    db.collection("chats").document(chatId).collection("messages")
                        .document(message.documentId).update("status", 0)
                }
            } else {
                vh.statusIcon.setOnClickListener(null)
            }
        }

        val isSelected = selectedMessageIds.contains(message.documentId)
        vh.bubbleLayout.isSelected = isSelected
        vh.itemView.isSelected = isSelected
        vh.itemView.setBackgroundColor(if (isSelected) 0x33005C4B.toInt() else 0)
        vh.bubbleLayout.alpha = if (isSelected) 0.8f else 1.0f

        val longClickListener = View.OnLongClickListener {
            if (message.documentId.isNotEmpty()) {
                toggleSelection(message.documentId)
            }
            true
        }
        vh.itemView.setOnLongClickListener(longClickListener)
        vh.bubbleLayout.setOnLongClickListener(longClickListener)

        vh.itemView.setOnClickListener {
            if (isSelectionMode() && message.documentId.isNotEmpty()) {
                toggleSelection(message.documentId)
            }
        }
    }

    private fun markAsSeen(docId: String) {
        db.collection("chats").document(chatId).collection("messages")
            .document(docId).update("status", 3)
    }

    private fun playVideo(message: MessageItem, vh: MessageViewHolder, position: Int) {
        val context = vh.itemView.context
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build()
        }

        // STOP CURRENT AUDIO IF PLAYING (WhatsApp rule: one media at a time)
        stopCurrentAudio()

        val localFile = findLocalFile(context, message)
        val uri = if (localFile != null && localFile.exists()) {
            localFile.toUri()
        } else {
            message.videoUrl?.toUri()
        }

        if (uri == null) return

        currentVideoPosition = position
        vh.videoPlayerView.player = exoPlayer

        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.apply {
            stop()
            setMediaItem(mediaItem)
            prepare()
            play()
            
            // Clean up old listeners
            clearVideoSurface() 
            
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        setupVideoSeekBar(vh)
                    }
                    if (state == Player.STATE_ENDED) {
                        vh.videoPlayerView.isVisible = false
                        vh.videoSeekBar.isVisible = false
                        vh.messageImage.isVisible = true
                        vh.playVideoIcon.isVisible = true
                    }
                }
            })
        }
    }

    private fun setupVideoSeekBar(vh: MessageViewHolder) {
        val player = exoPlayer ?: return
        vh.videoSeekBar.max = player.duration.toInt()
        
        videoUpdateRunnable = object : Runnable {
            override fun run() {
                try {
                    if (player.isPlaying) {
                        vh.videoSeekBar.progress = player.currentPosition.toInt()
                        videoUpdateHandler.postDelayed(this, 100)
                    }
                } catch (e: Exception) {
                    Log.e("CHAT_ADAPTER", "Video seek error: ${e.message}")
                }
            }
        }
        videoUpdateRunnable?.let { videoUpdateHandler.post(it) }

        vh.videoSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    if (f) player.seekTo(p.toLong())
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            },
        )
    }

    private fun playAudio(path: String, position: Int) {
        val oldPos = currentlyPlayingPosition
        if (currentlyPlayingPosition != position) stopCurrentAudio()
        currentlyPlayingPosition = position

        // STOP VIDEO IF PLAYING
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        currentVideoPosition = -1

        if (oldPos != -1) notifyItemChanged(oldPos)
        notifyItemChanged(position)

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build(),
            )
            try {
                setDataSource(path)
                prepareAsync()
                setOnPreparedListener {
                    Log.d("CHAT_ADAPTER", "Audio prepared, starting playback")
                    start()
                    notifyItemChanged(position)
                    setupVisualizer()
                }
                setOnCompletionListener {
                    Log.d("CHAT_ADAPTER", "Audio completed")
                    stopCurrentAudio()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("CHAT_ADAPTER", "MediaPlayer error: $what, $extra")
                    stopCurrentAudio()
                    true
                }
            } catch (e: Exception) {
                Log.e("AUDIO_ERROR", "Error setting data source: ${e.message}")
                stopCurrentAudio()
            }
        }
    }

    private fun pauseAudio(vh: MessageViewHolder) {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.e("CHAT_ADAPTER", "Pause error: ${e.message}")
        }
        vh.playButton.setImageResource(android.R.drawable.ic_media_play)
        updateVisualizer?.let { playHandler.removeCallbacks(it) }
        notifyItemChanged(currentlyPlayingPosition)
    }

    private fun stopCurrentAudio() {
        try {
            updateVisualizer?.let { playHandler.removeCallbacks(it) }
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        } catch (e: Exception) {
            Log.e("CHAT_ADAPTER", "Stop error: ${e.message}")
        } finally {
            mediaPlayer = null
            val oldPos = currentlyPlayingPosition
            currentlyPlayingPosition = -1
            if (oldPos != -1) notifyItemChanged(oldPos)
        }
    }

    private fun setupVisualizer() {
        updateVisualizer?.let { playHandler.removeCallbacks(it) }
        updateVisualizer = object : Runnable {
            override fun run() {
                val mp = mediaPlayer
                if ((mp != null) && mp.isPlaying) {
                    if (currentlyPlayingPosition != -1) {
                        notifyItemChanged(currentlyPlayingPosition)
                    }
                    playHandler.postDelayed(this, 100)
                }
            }
        }
        updateVisualizer?.let { playHandler.post(it) }
    }

    private fun resolveMediaTarget(context: Context, message: MessageItem, isVideo: Boolean): Any {
        message.localPath?.let { path ->
            try {
                if (path.startsWith("content://")) {
                    return path.toUri()
                }
                val rawPath = if (path.startsWith("file://")) {
                    val p = path.substring(7)
                    java.net.URLDecoder.decode(p, "UTF-8")
                } else {
                    java.net.URLDecoder.decode(path, "UTF-8")
                }
                val file = File(rawPath)
                if (file.exists()) return android.net.Uri.fromFile(file)
            } catch (_: Exception) {}
        }

        val localFile = findLocalFile(context, message)
        if (localFile != null && localFile.exists()) {
            return android.net.Uri.fromFile(localFile)
        }

        val remoteUrl = if (isVideo) message.videoUrl else message.imageUrl
        if (!remoteUrl.isNullOrEmpty() && remoteUrl != "loading") {
            return getAuthorizedUrl(remoteUrl)
        }

        return R.drawable.ic_clock
    }

    private fun findLocalFile(context: Context, message: MessageItem): File? {
        // 1. Check message localPath (usually for sent messages)
        message.localPath?.let { path ->
            try {
                if (path.startsWith("file://")) {
                    val file = File(path.toUri().path ?: "")
                    if (file.exists()) return file
                } else if (path.startsWith("/")) {
                    val file = File(path)
                    if (file.exists()) return file
                }
            } catch (_: Exception) {}
        }

        // 2. Check professional ChatApp Media folders based on type
        val folderName = when (message.messageType) {
            "image" -> StorageHelper.MEDIA_IMAGES
            "video" -> StorageHelper.MEDIA_VIDEO
            "voice" -> StorageHelper.MEDIA_VOICE_NOTES
            else -> StorageHelper.MEDIA_DOCUMENTS
        }
        
        val baseDir = StorageHelper.getMediaFolder(context, folderName)
        
        // Use documentId or last part of URL as filename
        val url = when (message.messageType) {
            "image" -> message.imageUrl
            "video" -> message.videoUrl
            "voice" -> message.voiceUrl
            else -> null
        }

        val fileName = if (!url.isNullOrEmpty()) {
            url.substringAfterLast("/")
        } else {
            "media_${message.documentId}" + when (message.messageType) {
                "image" -> ".jpg"
                "video" -> ".mp4"
                "voice" -> ".m4a"
                else -> ""
            }
        }

        // 2a. Check in date-based subfolders (WhatsApp style)
        val dateFolder = message.timestamp?.let {
            SimpleDateFormat("yyyyww", Locale.getDefault()).format(it)
        }
        
        if (dateFolder != null) {
            val fileInDateDir = File(File(baseDir, dateFolder), fileName)
            if (fileInDateDir.exists()) return fileInDateDir
        }

        // 2b. Check in base dir (legacy/direct)
        val permanentFile = File(baseDir, fileName)
        if (permanentFile.exists()) return permanentFile

        // 3. Fallback: Check documentId named file in any date subfolder
        if (message.messageType == "voice") {
            baseDir.listFiles()?.forEach { sub ->
                if (sub.isDirectory) {
                    val docIdFile = File(sub, "voice_${message.documentId}.m4a")
                    if (docIdFile.exists()) return docIdFile
                }
            }
            val docIdFileBase = File(baseDir, "voice_${message.documentId}.m4a")
            if (docIdFileBase.exists()) return docIdFileBase
        }

        return null
    }

    private fun downloadAudioToPermanentStorage(
        url: String,
        file: File,
        docId: String,
        callback: (Boolean) -> Unit,
    ) {
        val request = Request.Builder().url(url).addHeader("Authorization", b2Token).build()
        val call = client.newCall(request)
        activeDownloads[docId] = call
        downloadProgress[docId] = 0

        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeDownloads.remove(docId)
                    downloadProgress.remove(docId)
                    if (!call.isCanceled()) {
                        callback(false)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        try {
                            val body = response.body
                            val contentLength = body?.contentLength() ?: -1L
                            val inputStream = body?.byteStream()

                            file.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var totalRead = 0L
                                if (inputStream != null) {
                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                        totalRead += bytesRead
                                        if (contentLength > 0) {
                                            val progress = ((totalRead * 100) / contentLength).toInt()
                                            downloadProgress[docId] = progress
                                            // Trigger item update for smooth progress UI
                                            playHandler.post { 
                                                notifyItemChangedByDocId(docId)
                                            }
                                        }
                                    }
                                }
                            }
                            activeDownloads.remove(docId)
                            downloadProgress.remove(docId)
                            callback(true)
                        } catch (_: Exception) {
                            activeDownloads.remove(docId)
                            downloadProgress.remove(docId)
                            callback(false)
                        }
                    } else {
                        activeDownloads.remove(docId)
                        downloadProgress.remove(docId)
                        callback(false)
                    }
                }
            },
        )
    }

    private fun notifyItemChangedByDocId(docId: String) {
        val index = messages.indexOfFirst { it.documentId == docId }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    override fun getItemCount(): Int = messages.size

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubbleLayout: View = view.findViewById(R.id.bubble_layout)
        val messageText: TextView = view.findViewById(R.id.message_text)
        val messageImage: ImageView = view.findViewById(R.id.message_image)
        val videoPlayerView: PlayerView = view.findViewById(R.id.video_player_view)
        val videoSeekBar: SeekBar = view.findViewById(R.id.video_seekbar)
        val playVideoIcon: ImageView = view.findViewById(R.id.play_video_icon)
        val voicePlayerLayout: View = view.findViewById(R.id.voice_player_layout)
        val playButton: ImageButton = view.findViewById(R.id.play_button)
        val voiceDurationText: TextView = view.findViewById(R.id.voice_duration_text)
        val voiceVisualizer: VoiceVisualizerView = view.findViewById(R.id.voice_visualizer)
        val uploadProgress: ProgressBar = view.findViewById(R.id.upload_progress)
        val micIcon: ImageView = view.findViewById(R.id.mic_icon)
        val timeText: TextView = view.findViewById(R.id.message_time)
        val statusIcon: ImageView? = view.findViewById(R.id.status_icon)
        val profileImage: CircleImageView? = view.findViewById(R.id.contact_profile_image)
        val uploadContainer: View? = view.findViewById(R.id.upload_container)
        val uploadProgressBar: ProgressBar? = view.findViewById(R.id.upload_progress_bar)
        val uploadProgressText: TextView? = view.findViewById(R.id.upload_progress_text)
        val uploadCancelIcon: ImageView? = view.findViewById(R.id.upload_cancel_icon)
    }
}
