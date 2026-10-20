package com.gold.chatapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import java.io.FileOutputStream
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.MediaScannerConnection
import androidx.core.net.toUri
import android.net.Uri
import android.os.*
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.auth.oauth2.GoogleCredentials
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.*

class ChatActivity : AppCompatActivity() {

    // UI Components
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var recordButton: ImageButton
    private lateinit var cameraButton: ImageButton
    private lateinit var messagesRecycler: RecyclerView
    private lateinit var recordingLayout: LinearLayout
    private lateinit var recordingTimer: TextView
    private lateinit var redDot: ImageView
    private lateinit var chatNameHeader: TextView
    private lateinit var lockIcon: ImageView
    private lateinit var deleteIcon: ImageView
    private lateinit var slideText: TextView
    private lateinit var textInputContainer: LinearLayout

    // Logic Components
    private lateinit var chatAdapter: ChatMessagesAdapter
    private var messageActionMode: android.view.ActionMode? = null

    private val messageActionModeCallback = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            mode?.menuInflater?.inflate(R.menu.message_selection_menu, menu)
            return true
        }

        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            val count = chatAdapter.getSelectedCount()
            mode?.title = "$count"
            return true
        }

        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean {
            return when (item?.itemId) {
                R.id.action_reply_msg -> {
                    Toast.makeText(this@ChatActivity, "Replying to message", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_star_msg -> {
                    Toast.makeText(this@ChatActivity, "Starred", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_delete_msg -> {
                    Toast.makeText(this@ChatActivity, "Message deleted", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_share_msg, R.id.action_overflow_share -> {
                    Toast.makeText(this@ChatActivity, "Sharing message", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_pin_msg -> {
                    Toast.makeText(this@ChatActivity, "Message pinned", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_verify_security_code -> {
                    Toast.makeText(this@ChatActivity, "Security code verified", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                R.id.action_report_msg -> {
                    Toast.makeText(this@ChatActivity, "Report submitted", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: android.view.ActionMode?) {
            chatAdapter.clearSelection()
            messageActionMode = null
        }
    }
    private val messagesList = mutableListOf<MessageItem>()
    private val db = FirebaseFirestore.getInstance()
    private val client = OkHttpClient()
    private val activeUploads = mutableSetOf<String>()

    // Recording & Media State
    private var mediaRecorder: MediaRecorder? = null
    private var audioPath: String = ""
    private var isRecording = false
    private var isLocked = false
    private var startY = 0f
    private var startX = 0f
    private var recordingSeconds = 0
    private val timerHandler = Handler(Looper.getMainLooper())
    private val recordingAmplitudes = mutableListOf<Float>()
    private val amplitudeHandler = Handler(Looper.getMainLooper())
    private var amplitudeRunnable: Runnable? = null
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_SYSTEM, 100)
    private var vibrator: Vibrator? = null

    // IDs and Tokens
    private lateinit var myId: String
    private lateinit var chatId: String
    private lateinit var otherUserPhone: String
    private var firestoreListener: ListenerRegistration? = null
    private var recipientToken: String? = null
    private var tempFileUri: Uri? = null

    // B2 Configuration
    private val b2KeyId = "005cb4d249b27e40000000002"
    private val b2AppKey = "K005bZcXIuOMKUjwp2gj6WXuGM7dxdI"
    private val b2BucketId = "6cdbc4edb244590b92c70e14"
    private val b2BucketName = "chatapp-voice"
    private var b2ApiUrl: String = ""
    private var b2MainToken: String = ""
    private var b2DownloadUrl: String = ""
    private var cachedUploadUrl: String? = null
    private var cachedUploadToken: String? = null

    private val chatUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val incomingChatId = intent?.getStringExtra("chatId")
            if (incomingChatId == chatId) {
                messagesRecycler.scrollToPosition(messagesList.size - 1)
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show()
        }

    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                val mimeType = contentResolver.getType(it)
                val type = if (mimeType?.contains("video") == true) "video" else "image"

                // Professional Logic: Copy to ChatApp folder before upload
                val targetFile = copyUriToProfessionalFolder(it, type)
                val finalUri = if (targetFile != null) Uri.fromFile(targetFile) else it
                uploadMediaToB2(finalUri, type)
            }
        }

    private val capturePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) tempFileUri?.let { scanFile(it); uploadMediaToB2(it, "image") }
        }

    private val captureVideoLauncher =
        registerForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
            if (success) tempFileUri?.let { scanFile(it); uploadMediaToB2(it, "video") }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        StorageHelper.initChatAppFolders(this)

        setupWindowInsets()
        initVibrator()
        initChatDetails()
        initViews()
        setupToolbar()
        setupMessageInput()
        setupButtons()

        listenForMessages()
        prepareB2Session()
        setupRecordTouchListener()
    }

    private fun setupToolbar() {
        val toolbar =
            findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.chat_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        findViewById<View>(R.id.toolbar_click_area).setOnClickListener {
            val intent = Intent(this, ContactInfoActivity::class.java).apply {
                putExtra("other_phone", otherUserPhone)
                putExtra("contact_name", chatNameHeader.text.toString())
            }
            startActivity(intent)
        }

        findViewById<View>(R.id.btn_chat_video_call)?.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("other_phone", otherUserPhone)
                putExtra("contact_name", chatNameHeader.text.toString())
                putExtra("call_type", "video")
                putExtra("is_incoming", false)
            }
            startActivity(intent)
        }

        findViewById<View>(R.id.btn_chat_voice_call)?.setOnClickListener {
            val intent = Intent(this, CallActivity::class.java).apply {
                putExtra("other_phone", otherUserPhone)
                putExtra("contact_name", chatNameHeader.text.toString())
                putExtra("call_type", "voice")
                putExtra("is_incoming", false)
            }
            startActivity(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.contact_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_edit -> {
                val intent = Intent(this, EditContactActivity::class.java).apply {
                    putExtra("contact_phone", otherUserPhone)
                    putExtra("contact_name", chatNameHeader.text.toString())
                }
                startActivity(intent)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val oldOtherPhone = otherUserPhone
        setIntent(intent)
        initChatDetails()
        // Only restart listener if we switched to a different chat
        if (otherUserPhone != oldOtherPhone) {
            listenForMessages()
        }
    }

    private fun setupWindowInsets() {
        val mainLayout = findViewById<View>(R.id.main_chat_root)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottomPadding = if (imeHeight > 0) imeHeight else systemBars.bottom
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager =
                getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun getLocalContactName(phoneNumber: String): String {
        val prefs = getSharedPreferences("contacts_prefs", MODE_PRIVATE)
        val mapString = prefs.getString("contacts_map", "") ?: ""
        if (mapString.isNotEmpty()) {
            val pairs = mapString.split(";")
            for (pair in pairs) {
                val parts = pair.split("|")
                if (parts.size == 2 && parts[1].trim() == phoneNumber.trim()) return parts[0]
            }
        }
        return phoneNumber
    }

    private fun refreshChatHeader() {
        if (!::chatNameHeader.isInitialized) return
        val name = getLocalContactName(otherUserPhone)
        if (name != otherUserPhone && name.isNotEmpty()) {
            chatNameHeader.text = name
        } else {
            val intentName = intent.getStringExtra("contact_name") ?: ""
            if (intentName.isNotEmpty() && intentName != otherUserPhone) {
                chatNameHeader.text = intentName
            } else {
                chatNameHeader.text = otherUserPhone
            }
        }
    }

    private fun initChatDetails() {
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        myId = prefs.getString("user_phone", "") ?: ""
        otherUserPhone = intent.getStringExtra("other_phone") ?: ""
        chatNameHeader = findViewById(R.id.chat_name)
        refreshChatHeader()

        val sortedPhones = listOf(myId, otherUserPhone).sorted()
        chatId = "chat_${sortedPhones[0]}_${sortedPhones[1]}"

        db.collection("users").document(otherUserPhone).addSnapshotListener { snapshot, _ ->
            if ((snapshot != null) && snapshot.exists()) {
                recipientToken = snapshot.getString("fcmToken")
                val serverName = snapshot.getString("name") ?: ""
                val localName = getLocalContactName(otherUserPhone)
                if (localName == otherUserPhone && serverName.isNotEmpty()) {
                    chatNameHeader.text = serverName
                }
            }
        }
    }

    private fun initViews() {
        messageInput = findViewById(R.id.message_input)
        sendButton = findViewById(R.id.send_button)
        recordButton = findViewById(R.id.record_button)
        cameraButton = findViewById(R.id.camera_button)
        messagesRecycler = findViewById(R.id.messages_recycler)
        recordingLayout = findViewById(R.id.recording_layout)
        textInputContainer = findViewById(R.id.text_input_container)
        recordingTimer = findViewById(R.id.recording_timer)
        redDot = findViewById(R.id.red_dot)
        lockIcon = findViewById(R.id.lock_icon)
        deleteIcon = findViewById(R.id.delete_icon)
        slideText = findViewById(R.id.slide_to_cancel_text)

        val redDotShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.RED)
        }
        redDot.setImageDrawable(redDotShape)

        chatAdapter = ChatMessagesAdapter(messagesList, myId, db, chatId, "")
        chatAdapter.onSelectionChanged = { isInSelection ->
            if (isInSelection) {
                if (messageActionMode == null) {
                    messageActionMode = startActionMode(messageActionModeCallback)
                }
                messageActionMode?.invalidate()
            } else {
                messageActionMode?.finish()
            }
        }
        messagesRecycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRecycler.adapter = chatAdapter
    }

    private fun setupMessageInput() {
        messageInput.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (s.toString().trim().isNotEmpty()) {
                        sendButton.isVisible = true
                        recordButton.isVisible = false
                    } else {
                        sendButton.isVisible = false
                        recordButton.isVisible = true
                    }
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            },
        )
    }

    private fun setupButtons() {
        cameraButton.setOnClickListener { showMediaOptions() }
        sendButton.setOnClickListener {
            if (isRecording && isLocked) {
                lockIcon.isVisible = false
                deleteIcon.isVisible = false
                playStopSound()
                stopRecording(save = true)
            } else {
                val text = messageInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    playSentSound()
                    sendMessage(text)
                }
            }
        }
    }

    private fun prepareCompressedMedia(uri: Uri, type: String): File? {
        return try {
            if (type == "image") {
                val inputStream = contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap != null) {
                    val maxDim = 1280
                    var width = originalBitmap.width
                    var height = originalBitmap.height
                    if (width > maxDim || height > maxDim) {
                        val ratio = width.toFloat() / height.toFloat()
                        if (width > height) {
                            width = maxDim
                            height = (maxDim / ratio).toInt()
                        } else {
                            height = maxDim
                            width = (maxDim * ratio).toInt()
                        }
                    }
                    val resized = Bitmap.createScaledBitmap(originalBitmap, width, height, true)
                    val targetFolder = getPublicMediaFolder("Images")
                    val outFile = File(targetFolder, "IMG_COMP_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(outFile).use { fos ->
                        resized.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                    }
                    scanFile(Uri.fromFile(outFile))
                    return outFile
                }
            }
            val folder = getPublicMediaFolder(if (type == "video") "Video" else "Images")
            val ext = if (type == "video") "mp4" else "jpg"
            val outFile = File(folder, "MEDIA_${System.currentTimeMillis()}.$ext")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            scanFile(Uri.fromFile(outFile))
            outFile
        } catch (e: Throwable) {
            Log.e("COMPRESS", "Error preparing media: ${e.message}")
            null
        }
    }

    private fun getPublicMediaFolder(type: String): File {
        return when (type) {
            "Images" -> StorageHelper.getMediaFolderWithDate(this, StorageHelper.MEDIA_IMAGES)
            "Video" -> StorageHelper.getMediaFolderWithDate(this, StorageHelper.MEDIA_VIDEO)
            "Voice Notes" -> StorageHelper.getMediaFolderWithDate(
                this,
                StorageHelper.MEDIA_VOICE_NOTES
            )

            "Documents" -> StorageHelper.getMediaFolderWithDate(this, StorageHelper.MEDIA_DOCUMENTS)
            "Audio" -> StorageHelper.getMediaFolderWithDate(this, StorageHelper.MEDIA_AUDIO)
            else -> StorageHelper.getMediaFolderWithDate(this, StorageHelper.MEDIA_DOCUMENTS)
        }
    }

    private fun scanFile(uri: Uri) {
        val path = uri.path ?: return
        MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
    }

    private fun copyUriToProfessionalFolder(uri: Uri, type: String): File? {
        val folder = getPublicMediaFolder(if (type == "video") "Video" else "Images")
        val ext = if (type == "video") "mp4" else "jpg"
        val prefix = if (type == "video") "VID" else "IMG"
        val fileName = "${prefix}_${System.currentTimeMillis()}.$ext"
        val targetFile = File(folder, fileName)

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            scanFile(Uri.fromFile(targetFile))
            return targetFile
        } catch (e: Exception) {
            Log.e("STORAGE", "Copy failed: ${e.message}")
            return null
        }
    }

    private fun showMediaOptions() {
        val options = arrayOf("Take Photo", "Record Video", "Gallery")
        AlertDialog.Builder(this)
            .setTitle("Send Media")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> takePhoto()
                    1 -> takeVideo()
                    2 -> pickMediaLauncher.launch("*/*")
                }
            }.show()
    }

    private fun takePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val mediaFolder = getPublicMediaFolder("Images")
            val file = File(mediaFolder, "IMG_${System.currentTimeMillis()}.jpg")
            tempFileUri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            tempFileUri?.let { capturePhotoLauncher.launch(it) }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun takeVideo() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val mediaFolder = getPublicMediaFolder("Video")
            val file = File(mediaFolder, "VID_${System.currentTimeMillis()}.mp4")
            tempFileUri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            tempFileUri?.let { captureVideoLauncher.launch(it) }
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun sendMessage(text: String) {
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myProfilePic = prefs.getString("profileImageUrl", "") ?: ""

        val msgRef = db.collection("chats").document(chatId).collection("messages").document()
        val msg = hashMapOf(
            "sender" to myId,
            "text" to text,
            "status" to 1,
            "messageType" to "text",
            "timestamp" to FieldValue.serverTimestamp(),
            "localTimestamp" to System.currentTimeMillis(),
            "voicePlayed" to false,
            "senderProfileImageUrl" to myProfilePic,
        )

        messageInput.setText("")
        msgRef.set(msg).addOnSuccessListener {
            sendNotificationToRecipient(text)
        }
    }

    private fun uploadMediaToB2(uri: Uri, type: String) {
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myProfilePic = prefs.getString("profileImageUrl", "") ?: ""

        val mediaField = if (type == "image") "imageUrl" else "videoUrl"
        val mediaMap = hashMapOf(
            "sender" to myId,
            "messageType" to type,
            "status" to 0,
            "uploadProgress" to 0,
            "localPath" to uri.toString(),
            "senderProfileImageUrl" to myProfilePic,
            "timestamp" to FieldValue.serverTimestamp(),
            "localTimestamp" to System.currentTimeMillis(),
            mediaField to "loading",
        )

        val docRef = db.collection("chats").document(chatId).collection("messages").document()
        val docId = docRef.id
        activeUploads.add(docId)

        val localMsg = MessageItem(
            documentId = docId,
            sender = myId,
            text = "",
            voiceUrl = null,
            imageUrl = if (type == "image") "loading" else null,
            videoUrl = if (type == "video") "loading" else null,
            messageType = type,
            voiceDuration = null,
            status = 0,
            voicePlayed = false,
            senderProfileImageUrl = myProfilePic,
            amplitudes = null,
            timestamp = Date(),
            uploadProgress = 0,
            localPath = uri.toString(),
            timeRemaining = null,
            localTimestamp = System.currentTimeMillis()
        )
        val existingIndex = messagesList.indexOfFirst { it.documentId == docId }
        if (existingIndex == -1) {
            messagesList.add(localMsg)
            chatAdapter.notifyItemInserted(messagesList.size - 1)
            messagesRecycler.scrollToPosition(messagesList.size - 1)
        }

        docRef.set(mediaMap)
            .addOnSuccessListener {
                Thread {
                    try {
                        if (cachedUploadUrl == null) fetchUploadUrlSync()
                        
                        val compressedFile = prepareCompressedMedia(uri, type)
                        val uploadFile = compressedFile ?: File(uri.path ?: "")
                        val bytes = if (uploadFile.exists()) {
                            uploadFile.readBytes()
                        } else {
                            contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@Thread
                        }

                        val extension = if (type == "image") "jpg" else "mp4"
                        val fileName = "${type}_${System.currentTimeMillis()}.$extension"

                        val contentType = if (type == "image") {
                            "image/jpeg".toMediaType()
                        } else {
                            "video/mp4".toMediaType()
                        }
                        val rawBody = bytes.toRequestBody(contentType)

                        val progressBody = ProgressRequestBody(
                            rawBody,
                            object : ProgressRequestBody.UploadProgressListener {
                                var lastUpdate = 0L
                                var lastPercent = -1

                                override fun onProgress(
                                    bytesWritten: Long,
                                    contentLength: Long,
                                    elapsedMs: Long,
                                ) {
                                    val p = if (contentLength > 0) {
                                        (100L * bytesWritten / contentLength).toInt()
                                    } else {
                                        0
                                    }

                                    val now = System.currentTimeMillis()
                                    if (p != lastPercent && (now - lastUpdate > 1500 || p == 100)) {
                                        lastUpdate = now
                                        lastPercent = p

                                        val updates =
                                            mutableMapOf<String, Any>("uploadProgress" to p)

                                        if (elapsedMs > 1000 && p > 5 && p < 100) {
                                            val speed =
                                                bytesWritten.toDouble() / elapsedMs.toDouble()
                                            val remainingBytes = contentLength - bytesWritten
                                            val remainingMs = (remainingBytes / speed).toLong()
                                            val remainingSecs =
                                                (remainingMs / 1000).coerceAtLeast(1)
                                            updates["timeRemaining"] = "$remainingSecs sec left"
                                        }

                                        db.collection("chats").document(chatId)
                                            .collection("messages")
                                            .document(docId).update(updates)
                                            .addOnFailureListener { ex ->
                                                Log.e("B2_PROGRESS", "Update failed: ${ex.message}")
                                            }
                                    }
                                }
                            },
                        )

                        val upUrl = cachedUploadUrl ?: return@Thread
                        val upToken = cachedUploadToken ?: return@Thread

                        val request = Request.Builder()
                            .url(upUrl)
                            .addHeader("Authorization", upToken)
                            .addHeader("X-Bz-File-Name", URLEncoder.encode(fileName, "UTF-8"))
                            .addHeader("X-Bz-Content-Sha1", "do_not_verify")
                            .post(progressBody).build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val bodyStr = response.body?.string() ?: ""
                                val upName = JSONObject(bodyStr).getString("fileName")
                                val finalUrl = "$b2DownloadUrl/file/$b2BucketName/$upName"
                                runOnUiThread {
                                    val updates = if (type == "image") {
                                        mapOf(
                                            "imageUrl" to finalUrl,
                                            "status" to 1,
                                            "uploadProgress" to 100,
                                            "timeRemaining" to FieldValue.delete(),
                                        )
                                    } else {
                                        mapOf(
                                            "videoUrl" to finalUrl,
                                            "status" to 1,
                                            "uploadProgress" to 100,
                                            "timeRemaining" to FieldValue.delete(),
                                        )
                                    }
                                    db.collection("chats").document(chatId).collection("messages")
                                        .document(docId).update(updates)
                                        .addOnSuccessListener {
                                            activeUploads.remove(docId)
                                            val msgText = if (type == "image") "Sent a photo" else "Sent a video"
                                            sendNotificationToRecipient(msgText)
                                        }
                                        .addOnFailureListener { ex ->
                                            Log.e(
                                                "B2_STATUS",
                                                "Final status update failed: ${ex.message}"
                                            )
                                        }
                                }
                            } else {
                                runOnUiThread {
                                    activeUploads.remove(docId)
                                    db.collection("chats").document(chatId).collection("messages")
                                        .document(docId).update("status", 4)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("B2_UPLOAD", "Error: ${e.message}")
                        runOnUiThread {
                            activeUploads.remove(docId)
                            db.collection("chats").document(chatId).collection("messages")
                                .document(docId).update("status", 4)
                        }
                    }
                }.start()
            }
    }

    private fun prepareB2Session() {
        val auth = Base64.encodeToString("$b2KeyId:$b2AppKey".toByteArray(), Base64.NO_WRAP)
        val authInit = Request.Builder()
            .url("https://api.backblazeb2.com/b2api/v2/b2_authorize_account")
            .addHeader("Authorization", "Basic $auth")
            .build()

        client.newCall(authInit).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Handler(Looper.getMainLooper()).postDelayed({ prepareB2Session() }, 2000)
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: return
                    try {
                        val json = JSONObject(body)
                        b2MainToken = json.getString("authorizationToken")
                        b2ApiUrl = json.getString("apiUrl")
                        b2DownloadUrl = json.getString("downloadUrl")
                        fetchUploadUrlSync()
                        runOnUiThread { chatAdapter.updateToken(b2MainToken) }
                    } catch (ex: Exception) {
                        Log.e("B2_SESSION", "Parsing error: ${ex.message}")
                    }
                }
            },
        )
    }

    private fun fetchUploadUrlSync() {
        try {
            val getUrlRequest = Request.Builder()
                .url("$b2ApiUrl/b2api/v2/b2_get_upload_url")
                .addHeader("Authorization", b2MainToken)
                .post(
                    JSONObject().put("bucketId", b2BucketId).toString()
                        .toRequestBody("application/json".toMediaType()),
                )
                .build()
            client.newCall(getUrlRequest).execute().use { res ->
                val body = JSONObject(res.body?.string() ?: "")
                cachedUploadUrl = body.getString("uploadUrl")
                cachedUploadToken = body.getString("authorizationToken")
            }
        } catch (ex: Exception) {
            Log.e("B2_URL", "Fetch failed: ${ex.message}")
        }
    }

    private fun listenForMessages() {
        firestoreListener?.remove()
        firestoreListener = db.collection("chats").document(chatId).collection("messages")
            .orderBy("localTimestamp", Query.Direction.ASCENDING)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshots, e ->
                if ((e != null) || (snapshots == null)) return@addSnapshotListener

                val batch = db.batch()
                var hasUpdates = false

                val newMessages = snapshots.map { doc ->
                    val message = doc.toObject(MessageItem::class.java)
                    message.documentId = doc.id

                    if ((message.timestamp == null) && (doc.metadata.hasPendingWrites())) {
                        message.timestamp = Date()
                    }

                    if ((message.sender != myId) && (message.status < 3)) {
                        // Symmetrical: Symmetrically auto-mark all message types (including voice/media) as read/seen if active in chat screen
                        val messageDoc = db.collection("chats")
                            .document(chatId)
                            .collection("messages")
                            .document(doc.id)
                        batch.update(messageDoc, "status", 3)
                        hasUpdates = true
                    }

                    val isMyMsg = message.sender == myId
                    val isPending = message.status == 0
                    val isNotUploading = !activeUploads.contains(doc.id)

                    if (isMyMsg && isPending && isNotUploading) {
                        if ((message.messageType == "image") || (message.messageType == "video")) {
                            val path = message.localPath
                            if (!path.isNullOrEmpty()) {
                                activeUploads.add(doc.id)
                                retryUploadMedia(doc.id, path.toUri(), message.messageType)
                            }
                        } else if (message.messageType == "voice") {
                            val path = message.localPath
                            if (!path.isNullOrEmpty()) {
                                activeUploads.add(doc.id)
                                retryUploadAudio(doc.id, path)
                            }
                        }
                    }
                    message
                }

                if (hasUpdates) {
                    batch.commit().addOnSuccessListener {
                        Log.d("STATUS_UPDATE", "Batch status update successful")
                    }.addOnFailureListener { ex ->
                        Log.e("STATUS_UPDATE", "Batch failed: ${ex.message}")
                    }
                }

                runOnUiThread {
                    val oldSize = messagesList.size
                    val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(
                        object : androidx.recyclerview.widget.DiffUtil.Callback() {
                            override fun getOldListSize() = oldSize
                            override fun getNewListSize() = newMessages.size
                            override fun areItemsTheSame(o: Int, n: Int) =
                                messagesList[o].documentId == newMessages[n].documentId

                            override fun areContentsTheSame(o: Int, n: Int) =
                                messagesList[o] == newMessages[n]
                        }
                    )

                    messagesList.clear()
                    messagesList.addAll(newMessages)
                    diffResult.dispatchUpdatesTo(chatAdapter)

                    // Forced scroll only if new messages arrived
                    if (newMessages.size > oldSize) {
                        messagesRecycler.scrollToPosition(messagesList.size - 1)
                    }
                }
            }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecordTouchListener() {
        recordButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        startY = event.rawY
                        startX = event.rawX
                        isLocked = false
                        textInputContainer.isVisible = false
                        recordingLayout.isVisible = true
                        lockIcon.isVisible = true
                        deleteIcon.isVisible = true
                        slideText.isVisible = true
                        v.animate().scaleX(1.5f).scaleY(1.5f).setDuration(200).start()
                        playStartSound()
                        startRecording()
                    } else {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isRecording && !isLocked) {
                        val diffY = startY - event.rawY
                        val diffX = startX - event.rawX
                        if (diffY > 250) {
                            isLocked = true
                            vibrate(100)
                            lockIcon.animate().translationY(-150f).setDuration(200).start()
                            slideText.isVisible = false
                            sendButton.isVisible = true
                        }
                        if (diffX > 200) cancelRecording(v)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
                    if (!isLocked && isRecording) finishRecording()
                    true
                }

                else -> false
            }
        }
    }

    private fun cancelRecording(v: View) {
        textInputContainer.isVisible = true
        recordingLayout.isVisible = false
        lockIcon.isVisible = false
        deleteIcon.isVisible = false
        playDeleteSound()
        stopRecording(save = false)
        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
    }

    private fun finishRecording() {
        textInputContainer.isVisible = true
        recordingLayout.isVisible = false
        lockIcon.isVisible = false
        deleteIcon.isVisible = false
        playStopSound()
        stopRecording(save = true)
    }

    private fun startRecording() {
        recordingAmplitudes.clear()
        val voiceFolder = getPublicMediaFolder("Voice Notes")
        val file = File(voiceFolder, "AUD_${System.currentTimeMillis()}.m4a")
        audioPath = file.absolutePath

        // CRITICAL FIX: Ensure parent directories exist before preparing MediaRecorder
        file.parentFile?.mkdirs()

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(audioPath)
            try {
                prepare()
                start()
                isRecording = true
                startTimer()
                startAmplitudeTracking()
            } catch (ex: Exception) {
                Log.e("RECORDER", "Error starting recording: ${ex.message}")
                runOnUiThread {
                    Toast.makeText(
                        this@ChatActivity,
                        "Recording failed. Please try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun startAmplitudeTracking() {
        amplitudeRunnable = object : Runnable {
            override fun run() {
                if ((isRecording) && (mediaRecorder != null)) {
                    try {
                        val raw = mediaRecorder?.maxAmplitude ?: 0
                        recordingAmplitudes.add((raw.toFloat() / 32767f).coerceIn(0.1f, 1.0f))
                        amplitudeHandler.postDelayed(this, 50) // Increased resolution
                    } catch (ex: Exception) {
                        Log.e("AMPLITUDE", "Tracking failed: ${ex.message}")
                    }
                }
            }
        }
        amplitudeRunnable?.let { amplitudeHandler.post(it) }
    }

    private fun stopRecording(save: Boolean) {
        amplitudeRunnable?.let { amplitudeHandler.removeCallbacks(it) }
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (ex: Exception) {
            Log.e("RECORDER", "Stop failed: ${ex.message}")
        }
        mediaRecorder = null
        isRecording = false
        stopTimer()
        if (save) {
            MediaScannerConnection.scanFile(this, arrayOf(audioPath), null, null)
            uploadAudioToB2(audioPath)
        } else {
            File(audioPath).apply { if (exists()) delete() }
        }
    }

    private fun uploadAudioToB2(path: String) {
        val file = File(path)
        val retriever = MediaMetadataRetriever()
        var dur = "00:00"
        try {
            retriever.setDataSource(path)
            val msValue = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong() ?: 0L
            val totalSecs = msValue / 1000
            dur = String.format(
                Locale.getDefault(),
                "%d:%02d",
                totalSecs / 60,
                totalSecs % 60,
            )
            retriever.release()
        } catch (ex: Exception) {
            Log.e("RETRIEVER", "Duration extraction failed: ${ex.message}")
        }

        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myProfilePic = prefs.getString("profileImageUrl", "") ?: ""

        val voiceMap = hashMapOf(
            "sender" to myId,
            "messageType" to "voice",
            "voiceUrl" to "loading",
            "voiceDuration" to dur,
            "status" to 0,
            "uploadProgress" to 0,
            "voicePlayed" to false,
            "localPath" to path,
            "senderProfileImageUrl" to myProfilePic,
            "timestamp" to FieldValue.serverTimestamp(),
            "localTimestamp" to System.currentTimeMillis(),
            "amplitudes" to ArrayList(recordingAmplitudes),
        )

        val docRef = db.collection("chats").document(chatId).collection("messages").document()
        val docId = docRef.id
        activeUploads.add(docId)

        docRef.set(voiceMap)
            .addOnSuccessListener {
                Thread {
                    try {
                        if (cachedUploadUrl == null) fetchUploadUrlSync()
                        val rawBody = file.asRequestBody("audio/mp4".toMediaType())
                        val progressBody = ProgressRequestBody(
                            rawBody,
                            object : ProgressRequestBody.UploadProgressListener {
                                var lastUpdate = 0L
                                var lastPercent = -1

                                override fun onProgress(
                                    bytesWritten: Long,
                                    contentLength: Long,
                                    elapsedMs: Long,
                                ) {
                                    val p = if (contentLength > 0) {
                                        (100L * bytesWritten / contentLength).toInt()
                                    } else {
                                        0
                                    }

                                    val now = System.currentTimeMillis()
                                    if (p != lastPercent && (now - lastUpdate > 500 || p % 5 == 0)) {
                                        lastUpdate = now
                                        lastPercent = p

                                        val updates =
                                            mutableMapOf<String, Any>("uploadProgress" to p)

                                        if (elapsedMs > 1000 && p > 5 && p < 100) {
                                            val speed =
                                                bytesWritten.toDouble() / elapsedMs.toDouble()
                                            val remainingBytes = contentLength - bytesWritten
                                            val remainingMs = (remainingBytes / speed).toLong()
                                            val remainingSecs =
                                                (remainingMs / 1000).coerceAtLeast(1)
                                            updates["timeRemaining"] = "$remainingSecs sec left"
                                        }

                                        db.collection("chats").document(chatId)
                                            .collection("messages")
                                            .document(docId).update(updates)
                                            .addOnFailureListener { ex ->
                                                Log.e("B2_PROGRESS", "Update failed: ${ex.message}")
                                            }
                                    }
                                }
                            },
                        )
                        val upUrl = cachedUploadUrl ?: return@Thread
                        val upToken = cachedUploadToken ?: return@Thread

                        val req = Request.Builder().url(upUrl)
                            .addHeader("Authorization", upToken)
                            .addHeader(
                                "X-Bz-File-Name",
                                URLEncoder.encode(
                                    "voice_${System.currentTimeMillis()}.m4a",
                                    "UTF-8",
                                ),
                            )
                            .addHeader("X-Bz-Content-Sha1", "do_not_verify").post(progressBody)
                            .build()
                        client.newCall(req).execute().use { response ->
                            if (response.isSuccessful) {
                                val bodyStr = response.body?.string() ?: ""
                                val upName = JSONObject(bodyStr).getString("fileName")
                                val finalUrl = "$b2DownloadUrl/file/$b2BucketName/$upName"
                                runOnUiThread {
                                    activeUploads.remove(docId)
                                    db.collection("chats").document(chatId).collection("messages")
                                        .document(docId).update(
                                            mapOf(
                                                "voiceUrl" to finalUrl,
                                                "status" to 1,
                                                "uploadProgress" to 100,
                                                "timeRemaining" to FieldValue.delete(),
                                            ),
                                        )
                                        .addOnSuccessListener {
                                            sendNotificationToRecipient("Sent a voice note")
                                        }
                                        .addOnFailureListener { ex ->
                                            Log.e("B2_VOICE", "Status update failed: ${ex.message}")
                                        }
                                }
                            } else {
                                runOnUiThread {
                                    activeUploads.remove(docId)
                                    db.collection("chats").document(chatId).collection("messages")
                                        .document(docId).update("status", 4)
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        Log.e("B2_VOICE", "Upload failed: ${ex.message}")
                        runOnUiThread {
                            activeUploads.remove(docId)
                            db.collection("chats").document(chatId).collection("messages")
                                .document(docId).update("status", 4)
                        }
                    }
                }.start()
            }
    }

    private fun retryUploadMedia(docId: String, uri: Uri, type: String) {
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myProfilePic = prefs.getString("profileImageUrl", "") ?: ""
        Thread {
            try {
                if (cachedUploadUrl == null) fetchUploadUrlSync()
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes() ?: return@Thread
                val extension = if (type == "image") "jpg" else "mp4"
                val fileName = "${type}_${System.currentTimeMillis()}.$extension"

                val contentType = if (type == "image") {
                    "image/jpeg".toMediaType()
                } else {
                    "video/mp4".toMediaType()
                }
                val rawBody = bytes.toRequestBody(contentType)
                val progressBody = ProgressRequestBody(
                    rawBody,
                    object : ProgressRequestBody.UploadProgressListener {
                        var lastUpdate = 0L
                        var lastPercent = -1

                        override fun onProgress(
                            bytesWritten: Long,
                            contentLength: Long,
                            elapsedMs: Long,
                        ) {
                            val p = if (contentLength > 0) {
                                (100L * bytesWritten / contentLength).toInt()
                            } else {
                                0
                            }

                            val now = System.currentTimeMillis()
                            if (p != lastPercent && (now - lastUpdate > 500 || p % 5 == 0)) {
                                lastUpdate = now
                                lastPercent = p

                                val updates = mutableMapOf<String, Any>("uploadProgress" to p)

                                if (elapsedMs > 1000 && p > 5 && p < 100) {
                                    val speed = bytesWritten.toDouble() / elapsedMs.toDouble()
                                    val remainingBytes = contentLength - bytesWritten
                                    val remainingMs = (remainingBytes / speed).toLong()
                                    val remainingSecs = (remainingMs / 1000).coerceAtLeast(1)
                                    updates["timeRemaining"] = "$remainingSecs sec left"
                                }

                                db.collection("chats").document(chatId).collection("messages")
                                    .document(docId).update(updates)
                                    .addOnFailureListener { ex ->
                                        Log.e("B2_PROGRESS", "Update failed: ${ex.message}")
                                    }
                            }
                        }
                    },
                )

                val upUrl = cachedUploadUrl ?: return@Thread
                val upToken = cachedUploadToken ?: return@Thread
                val request = Request.Builder().url(upUrl).addHeader("Authorization", upToken)
                    .addHeader("X-Bz-File-Name", URLEncoder.encode(fileName, "UTF-8"))
                    .addHeader("X-Bz-Content-Sha1", "do_not_verify").post(progressBody).build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        val upName = JSONObject(bodyStr).getString("fileName")
                        val finalUrl = "$b2DownloadUrl/file/$b2BucketName/$upName"
                        val updates = if (type == "image") {
                            mapOf(
                                "imageUrl" to finalUrl,
                                "status" to 1,
                                "uploadProgress" to 100,
                                "timeRemaining" to FieldValue.delete(),
                            )
                        } else {
                            mapOf(
                                "videoUrl" to finalUrl,
                                "status" to 1,
                                "uploadProgress" to 100,
                                "timeRemaining" to FieldValue.delete(),
                            )
                        }
                        db.collection("chats").document(chatId).collection("messages")
                            .document(docId)
                            .update(updates).addOnSuccessListener {
                                activeUploads.remove(docId)
                                val msgText = if (type == "image") "Sent a photo" else "Sent a video"
                                sendNotificationToRecipient(msgText)
                            }
                    } else {
                        activeUploads.remove(docId)
                        db.collection("chats").document(chatId).collection("messages")
                            .document(docId).update("status", 4)
                    }
                }
            } catch (_: Exception) {
                activeUploads.remove(docId)
                db.collection("chats").document(chatId).collection("messages").document(docId)
                    .update("status", 4)
            }
        }.start()
    }

    private fun retryUploadAudio(docId: String, path: String) {
        val file = File(path)
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myProfilePic = prefs.getString("profileImageUrl", "") ?: ""
        Thread {
            try {
                if (cachedUploadUrl == null) fetchUploadUrlSync()
                val rawBody = file.asRequestBody("audio/mp4".toMediaType())
                val progressBody = ProgressRequestBody(
                    rawBody,
                    object : ProgressRequestBody.UploadProgressListener {
                        var lastUpdate = 0L
                        var lastPercent = -1

                        override fun onProgress(
                            bytesWritten: Long,
                            contentLength: Long,
                            elapsedMs: Long,
                        ) {
                            val p = if (contentLength > 0) {
                                (100L * bytesWritten / contentLength).toInt()
                            } else {
                                0
                            }

                            val now = System.currentTimeMillis()
                            if (p != lastPercent && (now - lastUpdate > 500 || p % 5 == 0)) {
                                lastUpdate = now
                                lastPercent = p

                                val updates = mutableMapOf<String, Any>("uploadProgress" to p)

                                if (elapsedMs > 1000 && p > 5 && p < 100) {
                                    val speed = bytesWritten.toDouble() / elapsedMs.toDouble()
                                    val remainingBytes = contentLength - bytesWritten
                                    val remainingMs = (remainingBytes / speed).toLong()
                                    val remainingSecs = (remainingMs / 1000).coerceAtLeast(1)
                                    updates["timeRemaining"] = "$remainingSecs sec left"
                                }

                                db.collection("chats").document(chatId).collection("messages")
                                    .document(docId).update(updates)
                                    .addOnFailureListener { ex ->
                                        Log.e("B2_PROGRESS", "Update failed: ${ex.message}")
                                    }
                            }
                        }
                    },
                )
                val upUrl = cachedUploadUrl ?: return@Thread
                val upToken = cachedUploadToken ?: return@Thread
                val req = Request.Builder().url(upUrl).addHeader("Authorization", upToken)
                    .addHeader(
                        "X-Bz-File-Name",
                        URLEncoder.encode("voice_${System.currentTimeMillis()}.m4a", "UTF-8")
                    )
                    .addHeader("X-Bz-Content-Sha1", "do_not_verify").post(progressBody).build()

                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyStr = response.body?.string() ?: ""
                        val upName = JSONObject(bodyStr).getString("fileName")
                        val finalUrl = "$b2DownloadUrl/file/$b2BucketName/$upName"
                        db.collection("chats").document(chatId).collection("messages")
                            .document(docId).update(
                                mapOf(
                                    "voiceUrl" to finalUrl,
                                    "status" to 1,
                                    "uploadProgress" to 100,
                                    "timeRemaining" to FieldValue.delete(),
                                ),
                            ).addOnSuccessListener {
                                activeUploads.remove(docId)
                                sendNotificationToRecipient("Sent a voice note")
                            }
                    } else {
                        activeUploads.remove(docId)
                        db.collection("chats").document(chatId).collection("messages")
                            .document(docId).update("status", 4)
                    }
                }
            } catch (_: Exception) {
                activeUploads.remove(docId)
                db.collection("chats").document(chatId).collection("messages").document(docId)
                    .update("status", 4)
            }
        }.start()
    }

    private fun startTimer() {
        recordingSeconds = 0
        timerHandler.post(
            object : Runnable {
                override fun run() {
                    if (isRecording) {
                        recordingTimer.text = String.format(
                            Locale.getDefault(),
                            "%d:%02d",
                            recordingSeconds / 60,
                            recordingSeconds % 60,
                        )
                        recordingSeconds++
                        redDot.isVisible = !redDot.isVisible
                        timerHandler.postDelayed(this, 1000)
                    }
                }
            },
        )
    }

    private fun stopTimer() = timerHandler.removeCallbacksAndMessages(null)

    private fun playSentSound() = toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)

    private fun playStartSound() {
        toneGenerator.startTone(ToneGenerator.TONE_DTMF_S, 150)
        vibrate(50)
    }

    private fun playStopSound() = toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 150)

    private fun playDeleteSound() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_NACK, 200)
        vibrate(100)
    }

    private fun vibrate(d: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(d)
        }
    }

    private fun sendNotificationToRecipient(messageText: String) {
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myProfilePic = prefs.getString("profileImageUrl", "") ?: ""
        val myName = prefs.getString("user_name", "") ?: ""

        if (!recipientToken.isNullOrEmpty()) {
            sendFcmV1Notification(recipientToken!!, myId, myName, messageText, myProfilePic)
        } else {
            db.collection("users").document(otherUserPhone).get().addOnSuccessListener { snapshot ->
                if (snapshot != null && snapshot.exists()) {
                    val token = snapshot.getString("fcmToken") ?: ""
                    if (token.isNotEmpty()) {
                        recipientToken = token
                        sendFcmV1Notification(token, myId, myName, messageText, myProfilePic)
                    }
                }
            }
        }
    }

    private fun sendFcmV1Notification(
        token: String,
        senderPhone: String,
        senderName: String,
        messageText: String,
        profilePic: String,
    ) {
        if (token.isEmpty()) return
        Thread {
            try {
                val inputStream = applicationContext.assets.open("serviceAccountKey.json")
                val googleCreds = GoogleCredentials.fromStream(inputStream)
                    .createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging"))
                googleCreds.refreshIfExpired()
                val accessToken = googleCreds.accessToken?.tokenValue

                if (accessToken == null) {
                    Log.e("FCM_SEND", "Failed to get access token")
                    return@Thread
                }

                val root = JSONObject()
                val message = JSONObject()
                val data = JSONObject().apply {
                    put("sender_phone", senderPhone)
                    put("sender_name", senderName)
                    put("chatId", chatId)
                    put("sender_profile_pic", profilePic)
                    put("body", messageText)
                }

                val android = JSONObject().apply {
                    put("priority", "high")
                }

                message.put("token", token)
                message.put("data", data)
                message.put("android", android)
                root.put("message", message)

                val request = Request.Builder()
                    .url("https://fcm.googleapis.com/v1/projects/chatapp-adaae/messages:send")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .post(
                        root.toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build()

                Log.d("FCM_SEND", "Payload: ${root.toString(2)}")

                client.newCall(request).execute().use { response ->
                    val respBody = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        Log.d("FCM_SEND", "Success: $respBody")
                    } else {
                        Log.e("FCM_SEND", "Error ${response.code}: $respBody")
                    }
                }
            } catch (e: Exception) {
                Log.e("FCM_V1", "Fatal error sending notification: ${e.message}")
            }
        }.start()
    }

    private val contactUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.gold.chatapp.CONTACT_UPDATED") {
                val oldPhone = intent.getStringExtra("old_phone") ?: ""
                val newPhone = intent.getStringExtra("new_phone") ?: ""
                if (oldPhone == otherUserPhone || newPhone == otherUserPhone) {
                    if (newPhone.isNotEmpty()) {
                        otherUserPhone = newPhone
                    }
                    refreshChatHeader()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshChatHeader()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.gold.chatapp.UPDATE_CHAT")
        val contactFilter = IntentFilter("com.gold.chatapp.CONTACT_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chatUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(contactUpdateReceiver, contactFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(chatUpdateReceiver, filter)
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(contactUpdateReceiver, contactFilter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(chatUpdateReceiver)
            unregisterReceiver(contactUpdateReceiver)
        } catch (ex: Exception) {
            Log.e("RECEIVER", "Unregister failed: ${ex.message}")
        }
        firestoreListener?.remove()
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator.release()
    }
}
