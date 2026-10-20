package com.gold.chatapp

import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class OtpVerificationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET: String = "EXTRA_TARGET"
        const val EXTRA_TYPE: String = "EXTRA_TYPE"
        const val EXTRA_DEBUG_OTP: String = "EXTRA_DEBUG_OTP"
    }

    private lateinit var btnBack: ImageButton
    private lateinit var tvTarget: TextView
    private lateinit var etOtpCode: TextInputEditText
    private lateinit var btnVerifyOtp: MaterialButton
    private lateinit var tvResendCode: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvDebugOtp: TextView

    private var target: String = ""
    private var targetType: String = "email"
    private var countDownTimer: CountDownTimer? = null
    private var canResend = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp_verification)

        target = intent.getStringExtra(EXTRA_TARGET) ?: ""
        val typeExtra = intent.getStringExtra(EXTRA_TYPE)
        targetType = typeExtra ?: if (target.contains("@")) "email" else "phone"
        val debugOtp = intent.getStringExtra(EXTRA_DEBUG_OTP)

        btnBack = findViewById(R.id.btn_back)
        tvTarget = findViewById(R.id.tv_target)
        etOtpCode = findViewById(R.id.et_otp_code)
        btnVerifyOtp = findViewById(R.id.btn_verify_otp)
        tvResendCode = findViewById(R.id.tv_resend_code)
        progressBar = findViewById(R.id.progress_bar)
        tvDebugOtp = findViewById(R.id.tv_debug_otp)

        tvTarget.text = target

        if (!debugOtp.isNullOrEmpty()) {
            tvDebugOtp.text = getString(R.string.dev_otp_format, debugOtp)
            tvDebugOtp.visibility = View.VISIBLE
        } else {
            tvDebugOtp.visibility = View.GONE
        }

        btnBack.setOnClickListener {
            finish()
        }

        btnVerifyOtp.setOnClickListener {
            performOtpVerification()
        }

        tvResendCode.setOnClickListener {
            if (canResend) {
                performResendOtp()
            }
        }

        startResendTimer()
    }

    private fun performOtpVerification() {
        val code = etOtpCode.text?.toString()?.trim() ?: ""

        if (code.length != 6) {
            Toast.makeText(this, "Please enter 6-digit code", Toast.LENGTH_SHORT).show()
            return
        }

        setLoadingState(isLoading = true)

        OtpSocketManager.verifyOtp(this, target, code) { success, message ->
            setLoadingState(isLoading = false)
            if (success) {
                Toast.makeText(this, "OTP Verified Successfully!", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performResendOtp() {
        setLoadingState(isLoading = true)
        canResend = false

        OtpSocketManager.resendOtp(this, target, targetType) { success, message, newDebugOtp ->
            setLoadingState(isLoading = false)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            if (success) {
                if (!newDebugOtp.isNullOrEmpty()) {
                    tvDebugOtp.text = getString(R.string.dev_otp_format, newDebugOtp)
                    tvDebugOtp.visibility = View.VISIBLE
                }
                startResendTimer()
            } else {
                canResend = true
                tvResendCode.text = getString(R.string.resend_code_now)
            }
        }
    }

    private fun startResendTimer() {
        canResend = false
        countDownTimer?.cancel()

        countDownTimer = object : CountDownTimer(60000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                tvResendCode.text = getString(R.string.resend_timer_format, seconds)
            }

            override fun onFinish() {
                canResend = true
                tvResendCode.text = getString(R.string.resend_code_now)
            }
        }.start()
    }

    private fun setLoadingState(isLoading: Boolean) {
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        btnVerifyOtp.isEnabled = !isLoading
        etOtpCode.isEnabled = !isLoading
        btnBack.isEnabled = !isLoading
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        super.onDestroy()
    }
}
