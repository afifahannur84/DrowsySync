package com.example.drowsysyncapp

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.example.drowsysyncapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isMonitoring = false
    private val latencyHandler = Handler(Looper.getMainLooper())
    private val monitoringHandler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private var dotAnimator: ObjectAnimator? = null

    companion object {
        const val PREFS_NAME = "drowsysync_prefs"
        const val KEY_LOGGED_IN = "is_logged_in"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Login routing ──────────────────────────────────────────────────────
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_LOGGED_IN, false)) {
            startActivity(Intent(this, RegistrationActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupDisplayModeToggle()
        setupMonitoringButton()
        startLatencyUpdater()
        startLatencyDotPulse()
    }

    // ── Header navigation ──────────────────────────────────────────────────────
    private fun setupHeader() {
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, ChangeOwnerActivity::class.java))
        }
    }

    // ── Display mode ──────────────────────────────────────────────────────────
    private fun setupDisplayModeToggle() {
        binding.toggleDisplayMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnModeAuto  -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                R.id.btnModeLight -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                R.id.btnModeDark  -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }
        binding.toggleDisplayMode.check(R.id.btnModeAuto)
    }

    // ── Monitoring button ──────────────────────────────────────────────────────
    private fun setupMonitoringButton() {
        binding.btnMonitoring.setOnClickListener {
            if (!isMonitoring) startMonitoring() else stopMonitoring()
        }
    }

    private fun startMonitoring() {
        isMonitoring = true

        val primaryColor = ContextCompat.getColor(this, R.color.primary)

        // Ring → active
        binding.monitoringRing.setImageResource(R.drawable.circle_monitor_active)
        binding.monitoringStatusText.setText(R.string.monitoring_active)
        binding.monitoringStatusText.setTextColor(primaryColor)
        binding.monitoringIcon.imageTintList = ColorStateList.valueOf(primaryColor)

        // Button → Stop (destructive)
        binding.btnMonitoring.text = getString(R.string.btn_stop_monitoring)
        binding.btnMonitoring.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.destructive))

        // Pulse animation on the ring
        pulseAnimator = ObjectAnimator.ofFloat(binding.monitoringRing, "alpha", 1f, 0.45f, 1f).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            start()
        }

        // Auto-trigger symptom alert after 5 s (mirrors the React demo)
        monitoringHandler.postDelayed({
            if (isMonitoring) {
                startActivity(Intent(this, SymptomAlertActivity::class.java))
            }
        }, 5000)
    }

    private fun stopMonitoring() {
        isMonitoring = false
        monitoringHandler.removeCallbacksAndMessages(null)
        pulseAnimator?.cancel()
        binding.monitoringRing.alpha = 1f

        val mutedColor = ContextCompat.getColor(this, R.color.muted_fg_light)

        // Ring → inactive
        binding.monitoringRing.setImageResource(R.drawable.circle_monitor_inactive)
        binding.monitoringStatusText.setText(R.string.monitoring_inactive)
        binding.monitoringStatusText.setTextColor(mutedColor)
        binding.monitoringIcon.imageTintList = ColorStateList.valueOf(mutedColor)

        // Button → Start (primary)
        binding.btnMonitoring.text = getString(R.string.btn_start_monitoring)
        binding.btnMonitoring.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
    }

    // ── Latency updater ───────────────────────────────────────────────────────
    private fun startLatencyUpdater() {
        val runnable = object : Runnable {
            override fun run() {
                val latency = (8..28).random()
                binding.tvLatency.text = "${latency}ms"
                latencyHandler.postDelayed(this, 2000)
            }
        }
        latencyHandler.post(runnable)
    }

    private fun startLatencyDotPulse() {
        dotAnimator = ObjectAnimator.ofFloat(binding.latencyDot, "alpha", 1f, 0.2f, 1f).apply {
            duration = 1500
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    // ── Resume: reset monitoring state when returning ─────────────────────────
    override fun onResume() {
        super.onResume()
        if (isMonitoring) stopMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        latencyHandler.removeCallbacksAndMessages(null)
        monitoringHandler.removeCallbacksAndMessages(null)
        pulseAnimator?.cancel()
        dotAnimator?.cancel()
    }
}