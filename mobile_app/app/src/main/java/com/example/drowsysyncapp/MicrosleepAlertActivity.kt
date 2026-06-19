package com.example.drowsysyncapp

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.drowsysyncapp.databinding.ActivityMicrosleepAlertBinding

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MicrosleepAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMicrosleepAlertBinding
    private var bounceAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMicrosleepAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bounce the alert triangle icon up and down
        bounceAnimator = ObjectAnimator.ofFloat(
            binding.root.getChildAt(0), "translationY", 0f, -24f, 0f
        ).apply {
            duration = 600
            repeatCount = ObjectAnimator.INFINITE
            start()
        }

        binding.btnDismiss.setOnClickListener {
            bounceAnimator?.cancel()

            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            val userId = prefs.getString("user_id", null)
            if (userId != null) {
                lifecycleScope.launch {
                    try {
                        com.example.drowsysyncapp.network.RetrofitClient.instance.dismissAlarm(userId)
                    } catch (e: Exception) {
                        android.util.Log.e("MicrosleepAlert", "Failed to dismiss alarm: ${e.message}")
                    }
                }
            }

            // Go back to Dashboard and clear the alert stack
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bounceAnimator?.cancel()
    }
}
