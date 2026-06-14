package com.example.drowsysyncapp

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import androidx.appcompat.app.AppCompatActivity
import com.example.drowsysyncapp.databinding.ActivitySymptomAlertBinding

class SymptomAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySymptomAlertBinding
    private var countDownTimer: CountDownTimer? = null
    private val totalSeconds = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySymptomAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startCountdown()

        binding.btnImAwake.setOnClickListener {
            countDownTimer?.cancel()
            finish() // returns to Dashboard (onResume will reset monitoring state)
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
                binding.progressCountdown.progress = 0
                val intent = Intent(this@SymptomAlertActivity, MicrosleepAlertActivity::class.java)
                startActivity(intent)
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
