package com.example.drowsysyncapp

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import androidx.appcompat.app.AppCompatActivity
import com.example.drowsysyncapp.databinding.ActivitySymptomAlertBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SymptomAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySymptomAlertBinding
    private var countDownTimer: CountDownTimer? = null
    private val totalSeconds = 10
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

        binding = ActivitySymptomAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start playing warning sound
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer.create(applicationContext, alarmUri)
            mediaPlayer?.isLooping = false
            mediaPlayer?.start()
        } catch (e: Exception) {
            android.util.Log.e("SymptomAlert", "Failed to start warning sound: ${e.message}")
        }

        startCountdown()

        binding.btnImAwake.setOnClickListener {
            stopWarningSound()
            countDownTimer?.cancel()

            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val userId = prefs.getString("user_id", null)
            if (userId != null) {
                lifecycleScope.launch {
                    try {
                        com.example.drowsysyncapp.network.RetrofitClient.instance.dismissAlarm(userId)
                    } catch (e: Exception) {
                        android.util.Log.e("SymptomAlert", "Failed to dismiss alarm: ${e.message}")
                    }
                }
            }

            finish() // returns to Dashboard
        }
    }

    private fun startCountdown() {
        countDownTimer = object : CountDownTimer(totalSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000).toInt()
                binding.tvCountdown.text = getString(R.string.escalating_in, secondsLeft)
                // Progress bar goes from 100 → 0 as countdown progresses
                binding.progressCountdown.progress = (secondsLeft * 100) / totalSeconds
            }
            override fun onFinish() {
                stopWarningSound()
                binding.progressCountdown.progress = 0
                val intent = Intent(this@SymptomAlertActivity, MicrosleepAlertActivity::class.java)
                startActivity(intent)
                finish()
            }
        }.start()
    }

    private fun stopWarningSound() {
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
        countDownTimer?.cancel()
        stopWarningSound()
    }
}
