package com.gold.chatapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging

class LoginActivity : AppCompatActivity() {

    private lateinit var phoneInput: TextInputEditText
    private lateinit var emailInput: TextInputEditText
    private lateinit var continueButton: MaterialButton
    private val db = FirebaseFirestore.getInstance()

    // 1. REGISTER THE PERMISSION REQUESTER
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (isGranted) {
            initiateOtpVerification()
        } else {
            Toast.makeText(this, "Notifications are required for chat alerts", Toast.LENGTH_LONG)
                .show()
            initiateOtpVerification()
        }
    }

    // 2. REGISTER THE OTP VERIFICATION LAUNCHER
    private val otpLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        continueButton.isEnabled = true
        if (result.resultCode == RESULT_OK) {
            performLogin()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val savedUser = prefs.getString("user_phone", null)
        val profileDone = prefs.getBoolean("profile_done", false)

        if (!savedUser.isNullOrEmpty() && profileDone) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        phoneInput = findViewById(R.id.phone_input)
        emailInput = findViewById(R.id.email_input)
        continueButton = findViewById(R.id.login_button)

        continueButton.setOnClickListener {
            checkPermissionsAndLogin()
        }
    }

    private fun checkPermissionsAndLogin() {
        val phoneNumber = phoneInput.text.toString().trim()
        val email = emailInput.text.toString().trim()

        if (phoneNumber.isEmpty() && email.isEmpty()) {
            Toast.makeText(this, "Enter phone number or email", Toast.LENGTH_SHORT).show()
            return
        }

        // CHECK IF WE NEED TO ASK FOR NOTIFICATION PERMISSION (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                initiateOtpVerification()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            initiateOtpVerification()
        }
    }

    private fun initiateOtpVerification() {
        val phoneNumber = phoneInput.text.toString().trim()
        val email = emailInput.text.toString().trim()
        val target = phoneNumber.ifEmpty { email }
        val targetType = if (phoneNumber.isNotEmpty()) "phone" else "email"

        continueButton.isEnabled = false

        OtpSocketManager.requestOtp(this, target, targetType) { success, message, debugOtp ->
            if (success) {
                val intent = Intent(this, OtpVerificationActivity::class.java).apply {
                    putExtra(OtpVerificationActivity.EXTRA_TARGET, target)
                    putExtra(OtpVerificationActivity.EXTRA_TYPE, targetType)
                    putExtra(OtpVerificationActivity.EXTRA_DEBUG_OTP, debugOtp)
                }
                otpLauncher.launch(intent)
            } else {
                continueButton.isEnabled = true
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performLogin() {
        val prefs = getSharedPreferences("chat_app_prefs", MODE_PRIVATE)
        val phoneNumber = phoneInput.text.toString().trim()
        val email = emailInput.text.toString().trim()
        val info = phoneNumber.ifEmpty { email }

        // Disable button to prevent double login
        continueButton.isEnabled = false

        prefs.edit {
            putString("user", info)
            putString("user_phone", info)
        }

        // Ensure we get a fresh token
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result else ""

            if (token.isEmpty()) {
                Log.w("LOGIN", "FCM Token fetch failed, proceeding with empty token")
            }

            // SetOptions.merge() prevents overwriting existing profile data
            val userMap = mutableMapOf<String, Any>(
                "phone" to info,
                "fcmToken" to token,
            )

            // Only add default profileImageUrl if the document doesn't exist
            db.collection("users").document(info)
                .set(userMap, SetOptions.merge())
                .addOnSuccessListener {
                    if (!isFinishing) {
                        val intent = Intent(this, ProfileSetupActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                }
                .addOnFailureListener { e ->
                    continueButton.isEnabled = true
                    Toast.makeText(this, "Login failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}
