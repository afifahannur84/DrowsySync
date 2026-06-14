package com.example.drowsysyncapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.example.drowsysyncapp.databinding.ActivityEmailVerificationBinding

class EmailVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmailVerificationBinding
    private var countDownTimer: CountDownTimer? = null
    private lateinit var otpFields: List<EditText>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmailVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        otpFields = listOf(
            binding.etOtp1, binding.etOtp2, binding.etOtp3,
            binding.etOtp4, binding.etOtp5, binding.etOtp6
        )

        setupOtpBoxes()
        startResendCountdown()

        binding.btnResend.setOnClickListener {
            // Clear OTP
            otpFields.forEach { it.setText("") }
            otpFields[0].requestFocus()
            startResendCountdown()
        }
    }

    // ── OTP auto-focus & completion ────────────────────────────────────────────
    private fun setupOtpBoxes() {
        otpFields.forEachIndexed { index, field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString() ?: ""
                    if (text.length == 1 && index < otpFields.lastIndex) {
                        otpFields[index + 1].requestFocus()
                    }
                    if (otpFields.all { it.text?.length == 1 }) {
                        onOtpComplete()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            // Handle backspace to go to previous field
            field.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DEL
                    && field.text?.isEmpty() == true
                    && index > 0
                ) {
                    otpFields[index - 1].requestFocus()
                    true
                } else false
            }
        }
    }

    private fun onOtpComplete() {
        // Show verified state after a short delay
        Handler(Looper.getMainLooper()).postDelayed({
            binding.layoutUnverified.visibility = View.GONE
            binding.layoutVerified.visibility = View.VISIBLE
            // Animate the check icon
            binding.ivVerifiedIcon.animate().scaleX(1.2f).scaleY(1.2f).setDuration(300)
                .withEndAction {
                    binding.ivVerifiedIcon.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                }.start()

            // Save login state and navigate to Dashboard
            Handler(Looper.getMainLooper()).postDelayed({
                getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(MainActivity.KEY_LOGGED_IN, true).apply()
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            }, 2000)
        }, 500)
    }

    // ── 60-second resend countdown ─────────────────────────────────────────────
    private fun startResendCountdown() {
        binding.tvResendCountdown.visibility = View.VISIBLE
        binding.btnResend.visibility = View.GONE
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(60_000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt()
                binding.tvResendCountdown.text = getString(R.string.resend_in, sec)
            }
            override fun onFinish() {
                binding.tvResendCountdown.visibility = View.GONE
                binding.btnResend.visibility = View.VISIBLE
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
