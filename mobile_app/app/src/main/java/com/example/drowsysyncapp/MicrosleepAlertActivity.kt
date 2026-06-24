package com.example.drowsysyncapp

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.drowsysyncapp.databinding.ActivityMicrosleepAlertBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MicrosleepAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMicrosleepAlertBinding
    private var bounceAnimator: ObjectAnimator? = null
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lockscreen and turn screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        binding = ActivityMicrosleepAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start playing critical alarm sound
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer.create(applicationContext, alarmUri)
            mediaPlayer?.isLooping = true
            mediaPlayer?.setOnCompletionListener {
                it.start() // Fallback to ensure continuous loop
            }
            mediaPlayer?.start()
        } catch (e: Exception) {
            android.util.Log.e("MicrosleepAlert", "Failed to start alarm sound: ${e.message}")
        }

        // Bounce the alert triangle icon up and down
        bounceAnimator = ObjectAnimator.ofFloat(
            binding.root.getChildAt(0), "translationY", 0f, -24f, 0f
        ).apply {
            duration = 600
            repeatCount = ObjectAnimator.INFINITE
            start()
        }

        binding.btnDismiss.setOnClickListener {
            stopAlarmSound()
            bounceAnimator?.cancel()

            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val userId = prefs.getString("user_id", null)
            if (userId != null) {
                lifecycleScope.launch {
                    try {
                        com.example.drowsysyncapp.network.RetrofitClient.instance.dismissAlarm(userId)
                        // Clear locally cached metrics so dashboard shows 0 on return
                        prefs.edit()
                            .putFloat("last_perclos", 0.0f)
                            .putInt("last_yawns", 0)
                            .putInt("last_stage", 0)
                            .apply()
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

    private fun stopAlarmSound() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bounceAnimator?.cancel()
        stopAlarmSound()
    }
}
