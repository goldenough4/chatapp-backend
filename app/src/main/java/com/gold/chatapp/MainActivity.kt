package com.gold.chatapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    private val client = OkHttpClient()

    private val messagesFragment = MessagesFragment()
    private val storiesFragment = StoriesFragment()
    private val callsFragment = CallsFragment()

    // BACKBLAZE CREDENTIALS (Sync with your private bucket)
    private val b2KeyId = "005cb4d249b27e40000000002"
    private val b2AppKey = "K005bZcXIuOMKUjwp2gj6WXuGM7dxdI"

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val postNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true
        }
        if (!postNotification) Log.d("PERM", "Notification permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            val gApi = GoogleApiAvailability.getInstance()
            val status = gApi.isGooglePlayServicesAvailable(this)
            if (status != ConnectionResult.SUCCESS && gApi.isUserResolvableError(status)) {
                gApi.getErrorDialog(this, status, 9000)?.show()
            }
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            Log.e("FIREBASE_INIT", "Error: ${e.message}")
        }

        checkAndRequestPermissions()
        requestIgnoreBatteryOptimizations()
        ensureFcmTokenIsCurrent()

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.main_toolbar)
        setSupportActionBar(toolbar)

        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.tab_layout)
        val addContactButton = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.add_contact_button)

        val fragments = listOf(messagesFragment, storiesFragment, callsFragment)
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }
        viewPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Messages"
                1 -> "Stories"
                2 -> "Calls"
                else -> ""
            }
        }.attach()

        addContactButton.setOnClickListener {
            startActivity(Intent(this, AddContactActivity::class.java))
        }

        listenForIncomingCalls()

        // Fetch Initial B2 Token for the Messages Fragment
        fetchB2Token { token, dlUrl ->
            if (token.isNotEmpty()) {
                getSharedPreferences("chat_app_prefs", MODE_PRIVATE).edit {
                    putString("b2_auth_token", token)
                    putString("b2_download_url", dlUrl)
                }
                runOnUiThread {
                    if (messagesFragment.isAdded) messagesFragment.updateFragmentToken(token)
                }
            }
        }

        handleNotificationIntent(intent)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        if (intent == null) return

        // Handle System Share Intents
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            // Logic: We are already in MainActivity. The user sees their chats.
            // When they click a chat, we should pass the share data forward.
            // For now, we'll store it in a temporary location or pass it via intent.
            // Professional implementation: Show a "Share to..." toast or similar.
            Toast.makeText(this, "Select a chat to share", Toast.LENGTH_SHORT).show()
        }

        val senderPhone = intent.getStringExtra("sender_phone")
        if (!senderPhone.isNullOrEmpty()) {
            val contactName = getLocalContactName(senderPhone)
            val chatIntent = Intent(this, ChatActivity::class.java).apply {
                putExtra("other_phone", senderPhone)
                putExtra("contact_name", contactName)
                // Use singleTop behavior via flags to avoid clearing the activity stack
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(chatIntent)
            // Clear the extra so it doesn't re-trigger on next resume
            intent.removeExtra("sender_phone")
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

    private fun ensureFcmTokenIsCurrent() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
                val myPhone = prefs.getString("user_phone", "") ?: ""
                if (myPhone.isNotEmpty()) {
                    FirebaseFirestore.getInstance().collection("users").document(myPhone)
                        .update("fcmToken", token)
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            @Suppress("DEPRECATION")
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            @Suppress("DEPRECATION")
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val list = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (list.isNotEmpty()) requestMultiplePermissionsLauncher.launch(list.toTypedArray())
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("BATTERY", "Optimization request failed")
            }
        }
    }

    private fun fetchB2Token(callback: (String, String) -> Unit) {
        val auth = Base64.encodeToString("$b2KeyId:$b2AppKey".toByteArray(), Base64.NO_WRAP)
        val request = Request.Builder()
            .url("https://api.backblazeb2.com/b2api/v2/b2_authorize_account")
            .addHeader("Authorization", "Basic $auth")
            .build()

        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback("", "")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            callback("", "")
                            return
                        }
                        val bodyString = it.body?.string() ?: "{}"
                        val json = JSONObject(bodyString)
                        callback(
                            json.optString("authorizationToken", ""),
                            json.optString("downloadUrl", ""),
                        )
                    }
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        listenForIncomingCalls()
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        // Ensure token is always fresh when user returns to app
        fetchB2Token { token, dlUrl ->
            if (token.isNotEmpty()) {
                prefs.edit {
                    putString("b2_auth_token", token)
                    putString("b2_download_url", dlUrl)
                }
                runOnUiThread {
                    if (messagesFragment.isAdded) messagesFragment.updateFragmentToken(token)
                }
            }
        }
    }

    private var incomingCallListener: com.google.firebase.firestore.ListenerRegistration? = null

    private fun listenForIncomingCalls() {
        incomingCallListener?.remove()
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val myPhone = prefs.getString("user_phone", "") ?: ""
        if (myPhone.isEmpty()) return

        incomingCallListener = FirebaseFirestore.getInstance().collection("calls")
            .whereEqualTo("callee", myPhone)
            .whereEqualTo("status", "calling")
            .addSnapshotListener { snapshots, _ ->
                if (snapshots != null) {
                    for (doc in snapshots.documentChanges) {
                        if (doc.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val data = doc.document.data
                            val caller = data["caller"] as? String ?: ""
                            val callType = data["callType"] as? String ?: "voice"
                            val callId = doc.document.id

                            if (caller.isNotEmpty()) {
                                val intent = Intent(this, CallActivity::class.java).apply {
                                    putExtra("other_phone", caller)
                                    putExtra("call_type", callType)
                                    putExtra("is_incoming", true)
                                    putExtra("call_id", callId)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                }
                                startActivity(intent)
                            }
                        }
                    }
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        incomingCallListener?.remove()
    }
}
