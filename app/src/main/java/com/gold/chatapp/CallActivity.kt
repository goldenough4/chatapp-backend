package com.gold.chatapp

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CallActivity : AppCompatActivity() {

    companion object {
        @Volatile
        private var globalFactory: PeerConnectionFactory? = null

        @Synchronized
        fun getPeerConnectionFactory(context: Context, eglBase: EglBase?): PeerConnectionFactory {
            if (globalFactory == null) {
                try {
                    val initOptions =
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                            .setEnableInternalTracer(true)
                            .createInitializationOptions()
                    PeerConnectionFactory.initialize(initOptions)
                } catch (_: Exception) {
                }

                val options = PeerConnectionFactory.Options()
                globalFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setVideoEncoderFactory(
                        DefaultVideoEncoderFactory(
                            eglBase?.eglBaseContext,
                            true,
                            true
                        )
                    )
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                    .createPeerConnectionFactory()
            }
            return globalFactory!!
        }
    }

    private lateinit var callRootLayout: View
    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var localVideoCard: MaterialCardView
    private lateinit var callProfileImage: ImageView
    private lateinit var contactNameText: TextView
    private lateinit var statusText: TextView
    private lateinit var videoFloatingActions: LinearLayout

    private lateinit var voiceControlsCard: MaterialCardView
    private lateinit var incomingActionsLayout: LinearLayout
    private lateinit var btnAcceptCall: FloatingActionButton
    private lateinit var btnDeclineCall: FloatingActionButton

    private lateinit var btnSpeaker: FloatingActionButton
    private lateinit var btnToggleVideo: FloatingActionButton
    private lateinit var btnMute: FloatingActionButton
    private lateinit var btnMore: FloatingActionButton
    private lateinit var btnShare: FloatingActionButton
    private lateinit var btnEndCall: FloatingActionButton
    private lateinit var btnSwitchCamera: FloatingActionButton
    private lateinit var btnEffects: FloatingActionButton

    private var otherPhone: String = ""
    private var contactName: String = ""
    private var callType: String = "voice" // "voice" or "video"
    private var isIncoming: Boolean = false
    private var callId: String = ""

    private val db = FirebaseFirestore.getInstance()
    private var callListener: ListenerRegistration? = null
    private var callerCandidatesListener: ListenerRegistration? = null
    private var calleeCandidatesListener: ListenerRegistration? = null

    private var audioManager: AudioManager? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var isSpeakerOn = false
    private var isMuted = false
    private var isVideoEnabled = true

    // WebRTC Core
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var myPhone: String = ""
    private var callSeconds = 0
    private val callTimerHandler = Handler(Looper.getMainLooper())
    private var callTimerRunnable: Runnable? = null

    private val isEndingCall = AtomicBoolean(false)
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private fun startCallTimer() {
        callSeconds = 0
        callTimerRunnable = object : Runnable {
            override fun run() {
                val mins = callSeconds / 60
                val secs = callSeconds % 60
                statusText.text = String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
                callSeconds++
                callTimerHandler.postDelayed(this, 1000)
            }
        }
        callTimerHandler.post(callTimerRunnable!!)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Acquire WakeLock to hardware wake up screen on sleep/locked state
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager

            @Suppress("DEPRECATION")
            val wl = pm?.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "ChatApp:CallActivityWakeLock"
            )
            wl?.acquire(10000L)
        } catch (_: Exception) {
        }

        // Turn screen on and show over keyguard/lockscreen on all Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_call)

        val prefs = getSharedPreferences("chat_app_prefs", Context.MODE_PRIVATE)
        myPhone = prefs.getString("user_phone", "") ?: ""

        otherPhone = intent.getStringExtra("other_phone") ?: ""
        contactName = intent.getStringExtra("contact_name") ?: otherPhone
        callType = intent.getStringExtra("call_type") ?: "voice"
        isIncoming = intent.getBooleanExtra("is_incoming", false)
        callId = intent.getStringExtra("call_id") ?: ""

        if (callId.isEmpty()) {
            val sorted = listOf(myPhone, otherPhone).sorted()
            callId = "call_${sorted[0]}_${sorted[1]}"
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        initViews()
        setupCallUI()

        if (isIncoming) {
            startRingingAndVibration()
            listenForCallCancellation()
        } else {
            checkCallPermissionsAndStart()
        }
    }

    private fun startRingingAndVibration() {
        try {
            val ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, ringUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build()
            }
            ringtone?.play()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 1000, 1000),
                        0
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 1000, 1000), 0)
            }
        } catch (e: Exception) {
            Log.e("RIDE", "Ring failed: ${e.message}")
        }
    }

    private fun stopRingingAndVibration() {
        try {
            ringtone?.stop()
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun listenForCallCancellation() {
        val callDoc = db.collection("calls").document(callId)
        callListener = callDoc.addSnapshotListener { sn, _ ->
            if (sn != null && sn.exists()) {
                val status = sn.getString("status") ?: ""
                if (status == "ended") {
                    stopRingingAndVibration()
                    endCall()
                }
            }
        }
    }

    private fun initViews() {
        callRootLayout = findViewById(R.id.call_root_layout)
        remoteVideoView = findViewById(R.id.remote_video_view)
        localVideoView = findViewById(R.id.local_video_view)
        localVideoCard = findViewById(R.id.local_video_card)
        callProfileImage = findViewById(R.id.call_profile_image)
        contactNameText = findViewById(R.id.call_contact_name)
        statusText = findViewById(R.id.call_status_text)
        videoFloatingActions = findViewById(R.id.video_floating_actions)

        voiceControlsCard = findViewById(R.id.voice_controls_card)
        incomingActionsLayout = findViewById(R.id.incoming_call_actions_layout)
        btnAcceptCall = findViewById(R.id.btn_accept_call)
        btnDeclineCall = findViewById(R.id.btn_decline_call)

        btnSpeaker = findViewById(R.id.btn_speaker)
        btnToggleVideo = findViewById(R.id.btn_toggle_video)
        btnMute = findViewById(R.id.btn_mute)
        btnMore = findViewById(R.id.btn_more)
        btnShare = findViewById(R.id.btn_share)
        btnEndCall = findViewById(R.id.btn_end_call)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
        btnEffects = findViewById(R.id.btn_effects)
    }

    private fun setupCallUI() {
        contactNameText.text = contactName

        if (isIncoming) {
            statusText.text = "Incoming $callType call..."
            voiceControlsCard.isVisible = false
            incomingActionsLayout.isVisible = true
            // Mark call as ringing in Firestore so caller UI switches to "Ringing..." immediately
            try {
                db.collection("calls").document(callId).update("status", "ringing")
            } catch (_: Exception) {
            }
        } else {
            statusText.text = "Calling..."
            voiceControlsCard.isVisible = true
            incomingActionsLayout.isVisible = false
        }

        val isVideoMode = callType == "video"
        remoteVideoView.isVisible = false
        localVideoCard.isVisible = false
        videoFloatingActions.isVisible = isVideoMode
        callProfileImage.isVisible = true

        // Load contact profile picture
        db.collection("users").document(otherPhone).get().addOnSuccessListener { sn ->
            if (sn != null && sn.exists()) {
                val avatar = sn.getString("profileImageUrl") ?: ""
                val name = sn.getString("name") ?: contactName
                contactNameText.text = name
                if (avatar.isNotEmpty()) {
                    Glide.with(this)
                        .load(avatar)
                        .circleCrop()
                        .placeholder(R.drawable.ic_default_user)
                        .error(R.drawable.ic_default_user)
                        .into(callProfileImage)
                }
            }
        }

        btnEndCall.setOnClickListener { endCall() }
        btnDeclineCall.setOnClickListener {
            stopRingingAndVibration()
            try {
                db.collection("calls").document(callId).update("status", "ended")
            } catch (_: Exception) {
            }
            endCall()
        }

        btnAcceptCall.setOnClickListener {
            stopRingingAndVibration()
            callListener?.remove()
            incomingActionsLayout.isVisible = false
            voiceControlsCard.isVisible = true
            statusText.text = "Connecting..."
            checkCallPermissionsAndStart()
        }

        btnMute.setOnClickListener {
            isMuted = !isMuted
            localAudioTrack?.setEnabled(!isMuted)
            btnMute.alpha = if (isMuted) 0.5f else 1.0f
            Toast.makeText(
                this,
                if (isMuted) "Microphone muted" else "Microphone unmuted",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            audioManager?.isSpeakerphoneOn = isSpeakerOn
            btnSpeaker.alpha = if (isSpeakerOn) 1.0f else 0.6f
            Toast.makeText(
                this,
                if (isSpeakerOn) "Speaker turned ON" else "Speaker turned OFF",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnToggleVideo.setOnClickListener {
            isVideoEnabled = !isVideoEnabled
            localVideoTrack?.setEnabled(isVideoEnabled)
            localVideoCard.isVisible = isVideoEnabled
            btnToggleVideo.alpha = if (isVideoEnabled) 1.0f else 0.5f
        }

        btnSwitchCamera.setOnClickListener {
            if (videoCapturer is CameraVideoCapturer) {
                (videoCapturer as CameraVideoCapturer).switchCamera(null)
            }
        }

        btnMore.setOnClickListener {
            Toast.makeText(this, "Call options menu", Toast.LENGTH_SHORT).show()
        }

        btnShare.setOnClickListener {
            Toast.makeText(this, "Sharing call link", Toast.LENGTH_SHORT).show()
        }

        btnEffects.setOnClickListener {
            Toast.makeText(this, "Video filters coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkCallPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            initWebRtcAndSignaling()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initWebRtcAndSignaling()
        } else {
            Toast.makeText(this, "Permissions required for call", Toast.LENGTH_SHORT).show()
            endCall()
        }
    }

    private fun initWebRtcAndSignaling() {
        try {
            eglBase = EglBase.create()

            // Initialize Renderers safely
            remoteVideoView.init(eglBase?.eglBaseContext, null)
            remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            remoteVideoView.setEnableHardwareScaler(true)

            localVideoView.init(eglBase?.eglBaseContext, null)
            localVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            localVideoView.setEnableHardwareScaler(true)
            localVideoView.setZOrderMediaOverlay(true)

            // Safe WebRTC Factory Initialization
            peerConnectionFactory = getPeerConnectionFactory(this, eglBase)

            // Local Audio Track
            val audioConstraints = MediaConstraints()
            val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("101", audioSource)

            // Local Video Track
            if (callType == "video") {
                videoCapturer = createCameraCapturer()
                if (videoCapturer != null) {
                    surfaceTextureHelper =
                        SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
                    val videoSource =
                        peerConnectionFactory?.createVideoSource(videoCapturer!!.isScreencast)
                    videoCapturer?.initialize(
                        surfaceTextureHelper,
                        applicationContext,
                        videoSource?.capturerObserver
                    )
                    videoCapturer?.startCapture(1280, 720, 30)

                    localVideoTrack = peerConnectionFactory?.createVideoTrack("102", videoSource)
                    localVideoTrack?.addSink(localVideoView)
                    localVideoCard.isVisible = true
                }
            }

            // Create PeerConnection
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        if (candidate == null) return
                        val candidateMap = hashMapOf(
                            "sdpMid" to candidate.sdpMid,
                            "sdpMLineIndex" to candidate.sdpMLineIndex,
                            "sdp" to candidate.sdp
                        )
                        val collection = if (!isIncoming) "callerCandidates" else "calleeCandidates"
                        db.collection("calls").document(callId).collection(collection)
                            .add(candidateMap)
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {
                        val track = transceiver?.receiver?.track()
                        if (track is VideoTrack) {
                            runOnUiThread {
                                track.addSink(remoteVideoView)
                                remoteVideoView.isVisible = true
                                callProfileImage.isVisible = false
                            }
                        }
                    }

                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        runOnUiThread {
                            if (state == PeerConnection.IceConnectionState.CONNECTED) {
                                startCallTimer()
                                val isVideoMode = callType == "video"
                                if (isVideoMode) {
                                    remoteVideoView.isVisible = true
                                    callProfileImage.isVisible = false
                                }
                            } else if (state == PeerConnection.IceConnectionState.DISCONNECTED || state == PeerConnection.IceConnectionState.FAILED) {
                                statusText.text = "Call Ended"
                                endCall()
                            }
                        }
                    }

                    override fun onIceConnectionReceivingChange(p0: Boolean) {}
                    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                    override fun onAddStream(p0: MediaStream?) {}
                    override fun onRemoveStream(p0: MediaStream?) {}
                    override fun onDataChannel(p0: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                })

            // Add local tracks
            localAudioTrack?.let { peerConnection?.addTrack(it, listOf("media_stream")) }
            localVideoTrack?.let { peerConnection?.addTrack(it, listOf("media_stream")) }

            // Start Signaling
            setupSignaling()

        } catch (e: Throwable) {
            Log.e("WEBRTC", "Error initializing WebRTC: ${e.message}")
            Toast.makeText(this, "Failed to start call", Toast.LENGTH_SHORT).show()
            endCall()
        }
    }

    private fun setupSignaling() {
        val callDoc = db.collection("calls").document(callId)

        if (!isIncoming) {
            // CALLER FLOW
            val callData = hashMapOf(
                "caller" to myPhone,
                "callee" to otherPhone,
                "callType" to callType,
                "status" to "calling",
                "timestamp" to System.currentTimeMillis()
            )
            callDoc.set(callData)

            // Send FCM high priority call push notification to wake up screen-off / background receiver
            db.collection("users").document(otherPhone).get().addOnSuccessListener { sn ->
                if (sn != null && sn.exists()) {
                    val calleeToken = sn.getString("fcmToken") ?: ""
                    val prefs = getSharedPreferences("chat_app_prefs", Context.MODE_PRIVATE)
                    val myName = prefs.getString("user_name", "") ?: myPhone
                    if (calleeToken.isNotEmpty()) {
                        sendFcmV1CallNotification(calleeToken, myPhone, myName, callType, callId)
                    }
                }
            }

            val mediaConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(
                    MediaConstraints.KeyValuePair(
                        "OfferToReceiveVideo",
                        if (callType == "video") "true" else "false"
                    )
                )
            }

            peerConnection?.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc == null) return
                    peerConnection?.setLocalDescription(SdpObserverAdapter(), desc)
                    val offerMap = hashMapOf("type" to "offer", "sdp" to desc.description)
                    callDoc.update("offer", offerMap)
                }
            }, mediaConstraints)

            // Listen for Answer and Ringing Status
            callListener = callDoc.addSnapshotListener { sn, _ ->
                if (sn != null && sn.exists()) {
                    val status = sn.getString("status") ?: ""
                    if (status == "ringing") {
                        runOnUiThread { statusText.text = "Ringing..." }
                    } else if (status == "answered" || status == "connected") {
                        runOnUiThread { statusText.text = "Connected" }
                    } else if (status == "ended") {
                        endCall()
                        return@addSnapshotListener
                    }

                    val answerMap = sn.get("answer") as? Map<*, *>
                    if (answerMap != null && peerConnection?.remoteDescription == null) {
                        val sdp = answerMap["sdp"] as? String ?: ""
                        if (sdp.isNotEmpty()) {
                            val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                            peerConnection?.setRemoteDescription(SdpObserverAdapter(), desc)
                            runOnUiThread { statusText.text = "Connected" }
                        }
                    }
                }
            }

            // Listen for Callee ICE Candidates
            calleeCandidatesListener =
                callDoc.collection("calleeCandidates").addSnapshotListener { snapshots, _ ->
                    if (snapshots != null) {
                        for (doc in snapshots.documentChanges) {
                            if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                                try {
                                    val data = doc.document.data
                                    val sdpMid = data["sdpMid"] as? String
                                    val sdpMLineIndex =
                                        (data["sdpMLineIndex"] as? Long)?.toInt() ?: 0
                                    val sdp = data["sdp"] as? String ?: ""
                                    if (sdp.isNotEmpty()) {
                                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                                        peerConnection?.addIceCandidate(candidate)
                                    }
                                } catch (e: Exception) {
                                    Log.e("ICE", "Error adding ice candidate: ${e.message}")
                                }
                            }
                        }
                    }
                }

        } else {
            // CALLEE FLOW
            callDoc.update("status", "answered")

            callDoc.get().addOnSuccessListener { sn ->
                if (sn != null && sn.exists()) {
                    val offerMap = sn.get("offer") as? Map<*, *>
                    if (offerMap != null) {
                        val sdp = offerMap["sdp"] as? String ?: ""
                        if (sdp.isNotEmpty()) {
                            val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
                            peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
                                override fun onSetSuccess() {
                                    val mediaConstraints = MediaConstraints().apply {
                                        mandatory.add(
                                            MediaConstraints.KeyValuePair(
                                                "OfferToReceiveAudio",
                                                "true"
                                            )
                                        )
                                        mandatory.add(
                                            MediaConstraints.KeyValuePair(
                                                "OfferToReceiveVideo",
                                                if (callType == "video") "true" else "false"
                                            )
                                        )
                                    }
                                    peerConnection?.createAnswer(object : SdpObserverAdapter() {
                                        override fun onCreateSuccess(answerDesc: SessionDescription?) {
                                            if (answerDesc == null) return
                                            peerConnection?.setLocalDescription(
                                                SdpObserverAdapter(),
                                                answerDesc
                                            )
                                            val answerMapObj = hashMapOf(
                                                "type" to "answer",
                                                "sdp" to answerDesc.description
                                            )
                                            callDoc.update("answer", answerMapObj)
                                            runOnUiThread { statusText.text = "Connected" }
                                        }
                                    }, mediaConstraints)
                                }
                            }, desc)
                        }
                    }
                }
            }

            // Listen for Caller ICE Candidates
            callerCandidatesListener =
                callDoc.collection("callerCandidates").addSnapshotListener { snapshots, _ ->
                    if (snapshots != null) {
                        for (doc in snapshots.documentChanges) {
                            if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                                try {
                                    val data = doc.document.data
                                    val sdpMid = data["sdpMid"] as? String
                                    val sdpMLineIndex =
                                        (data["sdpMLineIndex"] as? Long)?.toInt() ?: 0
                                    val sdp = data["sdp"] as? String ?: ""
                                    if (sdp.isNotEmpty()) {
                                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                                        peerConnection?.addIceCandidate(candidate)
                                    }
                                } catch (e: Exception) {
                                    Log.e("ICE", "Error adding ice candidate: ${e.message}")
                                }
                            }
                        }
                    }
                }

            // Listen for Call Termination
            callListener = callDoc.addSnapshotListener { sn, _ ->
                if (sn != null && sn.exists()) {
                    val status = sn.getString("status") ?: ""
                    if (status == "ended") endCall()
                }
            }
        }
    }

    private fun sendFcmV1CallNotification(
        calleeToken: String,
        callerPhone: String,
        callerName: String,
        callType: String,
        callId: String
    ) {
        if (calleeToken.isEmpty()) return
        Thread {
            try {
                val inputStream = applicationContext.assets.open("serviceAccountKey.json")
                val googleCreds = com.google.auth.oauth2.GoogleCredentials.fromStream(inputStream)
                    .createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging"))
                googleCreds.refreshIfExpired()
                val accessToken = googleCreds.accessToken?.tokenValue ?: return@Thread

                val root = org.json.JSONObject()
                val message = org.json.JSONObject()
                val data = org.json.JSONObject().apply {
                    put("type", "call")
                    put("caller_phone", callerPhone)
                    put("caller_name", callerName)
                    put("call_type", callType)
                    put("call_id", callId)
                }

                val android = org.json.JSONObject().apply {
                    put("priority", "high")
                }

                message.put("token", calleeToken)
                message.put("data", data)
                message.put("android", android)
                root.put("message", message)

                val requestBody =
                    root.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = okhttp3.Request.Builder()
                    .url("https://fcm.googleapis.com/v1/projects/chatapp-adaae/messages:send")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .post(requestBody)
                    .build()

                okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                    Log.d("CALL_FCM", "Call FCM response code: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("CALL_FCM", "Failed to send call FCM notification: ${e.message}")
            }
        }.start()
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        val deviceNames = enumerator.deviceNames

        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    private fun endCall() {
        if (!isEndingCall.compareAndSet(false, true)) return

        stopRingingAndVibration()
        callTimerRunnable?.let { callTimerHandler.removeCallbacks(it) }

        try {
            callListener?.remove()
            callerCandidatesListener?.remove()
            calleeCandidatesListener?.remove()
        } catch (_: Exception) {
        }

        val currentCallId = callId
        val currentPeerConn = peerConnection
        val currentCapturer = videoCapturer
        val currentTextureHelper = surfaceTextureHelper
        val currentLocalVideoTrack = localVideoTrack
        val currentLocalAudioTrack = localAudioTrack

        peerConnection = null
        videoCapturer = null
        surfaceTextureHelper = null
        localVideoTrack = null
        localAudioTrack = null

        backgroundExecutor.execute {
            try {
                if (currentCallId.isNotEmpty()) {
                    db.collection("calls").document(currentCallId).update("status", "ended")
                }
            } catch (_: Exception) {
            }

            try {
                currentCapturer?.stopCapture()
            } catch (_: Exception) {
            }
            try {
                currentCapturer?.dispose()
            } catch (_: Exception) {
            }
            try {
                currentTextureHelper?.dispose()
            } catch (_: Exception) {
            }

            try {
                currentLocalVideoTrack?.dispose()
            } catch (_: Exception) {
            }
            try {
                currentLocalAudioTrack?.dispose()
            } catch (_: Exception) {
            }

            try {
                currentPeerConn?.close()
                currentPeerConn?.dispose()
            } catch (_: Exception) {
            }
        }

        runOnUiThread {
            try {
                localVideoView.release()
                remoteVideoView.release()
            } catch (_: Exception) {
            }

            try {
                audioManager?.mode = AudioManager.MODE_NORMAL
                audioManager?.isSpeakerphoneOn = false
                audioManager?.isMicrophoneMute = false
            } catch (_: Exception) {
            }

            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        endCall()
    }

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
