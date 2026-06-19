package com.example.drowsysyncapp

import android.Manifest
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.drowsysyncapp.databinding.ActivityMainBinding
import com.example.drowsysyncapp.network.GuestModeRequest
import com.example.drowsysyncapp.network.RetrofitClient
import kotlinx.coroutines.launch
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isMonitoring = false
    private var pulseAnimator: ObjectAnimator? = null

    // ── Live Data Broadcast Receiver ──────────────────────────────────────────
    private val liveDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.drowsysyncapp.UPDATE_METRICS") {
                val perclos = intent.getDoubleExtra("PERCLOS", 0.0)
                val yawns = intent.getIntExtra("YAWNS", 0)
                val timestamp = intent.getLongExtra("TIMESTAMP", 0L)

                // Save to prefs so they survive recreation
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putFloat("last_perclos", perclos.toFloat())
                    .putInt("last_yawns", yawns)
                    .apply()

                // Dynamically update your UI with real data from MongoDB Atlas!
                binding.tvPerclos.text = String.format("%.1f%%", perclos)
                binding.tvYawns.text = yawns.toString()

            }
        }
    }

    companion object {
        const val PREFS_NAME = "drowsysync_prefs"
        const val KEY_LOGGED_IN = "is_logged_in"
        const val KEY_IS_MONITORING = "is_monitoring"
    }

    /** True when startMonitoring() was triggered by the user this session (not a restore). */
    private var isUserInitiatedStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Login routing ──────────────────────────────────────────────────────
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_LOGGED_IN, false)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request notifications permission for Android 13+ to allow popups over other apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        // Request display overlay permission if not granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, 101)
            }
        }

        setupHeader()
        setupDisplayModeToggle()
        setupGuestModeToggle()
        setupMonitoringButton()

        // Restore monitoring state after recreate (e.g. display mode switch)
        // Do NOT call startMonitoring() here — it resets metrics to 0.
        // Instead, only restore UI visuals so the user doesn't see a flash.
        if (prefs.getBoolean(KEY_IS_MONITORING, false)) {
            isMonitoring = true
            isUserInitiatedStart = false // This is a restore, not a fresh start
            restoreMonitoringVisuals()
        }
    }

    // ── Header navigation ──────────────────────────────────────────────────────
    private fun setupHeader() {
        refreshHeaderName()

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.btnEditProfile.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, ChangeOwnerActivity::class.java))
        }
    }

    /** Called from both setupHeader() and onResume() so the name is always fresh. */
    private fun refreshHeaderName() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userName = prefs.getString("user_name", "")
        if (!userName.isNullOrEmpty()) {
            binding.tvHeaderTitle.text = "DrowsySync - $userName"
        } else {
            binding.tvHeaderTitle.text = "DrowsySync"
        }
    }

    // ── Display mode ──────────────────────────────────────────────────────────
    private fun setupDisplayModeToggle() {
        // Restore correct toggle state without triggering loops
        when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.toggleDisplayMode.check(R.id.btnModeLight)
            AppCompatDelegate.MODE_NIGHT_YES -> binding.toggleDisplayMode.check(R.id.btnModeDark)
            else -> binding.toggleDisplayMode.check(R.id.btnModeAuto)
        }

        binding.toggleDisplayMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val targetMode = when (checkedId) {
                R.id.btnModeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.btnModeDark  -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_AUTO_TIME
            }
            if (AppCompatDelegate.getDefaultNightMode() != targetMode) {
                AppCompatDelegate.setDefaultNightMode(targetMode)
            }
        }
    }

    // ── Guest Mode ────────────────────────────────────────────────────────────
    private fun setupGuestModeToggle() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null) ?: return

        binding.switchGuestMode.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                try {
                    val response = RetrofitClient.instance.toggleGuestMode(userId, GuestModeRequest(isChecked))
                    if (response.isSuccessful) {
                        if (isChecked) {
                            Toast.makeText(this@MainActivity, "Guest Mode Activated — Analysis Paused", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Guest Mode Deactivated", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to toggle Guest Mode", Toast.LENGTH_SHORT).show()
                        binding.switchGuestMode.setOnCheckedChangeListener(null)
                        binding.switchGuestMode.isChecked = !isChecked
                        setupGuestModeToggle() // Re-attach listener
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.switchGuestMode.setOnCheckedChangeListener(null)
                    binding.switchGuestMode.isChecked = !isChecked
                    setupGuestModeToggle() // Re-attach listener
                }
            }
        }
    }

    // ── Monitoring button ──────────────────────────────────────────────────────
    private fun setupMonitoringButton() {
        binding.btnMonitoring.setOnClickListener {
            if (!isMonitoring) startMonitoring() else stopMonitoring()
        }
    }

    private fun startMonitoring() {
        isMonitoring = true
        isUserInitiatedStart = true
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_IS_MONITORING, true)
            .putFloat("last_perclos", 0.0f)
            .putInt("last_yawns", 0)
            .apply()

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
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(binding.monitoringRing, "alpha", 1f, 0.45f, 1f).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            start()
        }

        // Show metrics card — reset to 0 because user started a new trip
        binding.cardMetrics.visibility = android.view.View.VISIBLE
        binding.tvPerclos.text = "0.0%"
        binding.tvYawns.text = "0"

        // 🚀 Start the background polling service
        val serviceIntent = Intent(this, DrowsySyncBackgroundService::class.java)
        startForegroundService(serviceIntent)

        // 🚗 Claim the vehicle on the backend so logs map to THIS user
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null)
        if (userId != null) {
            lifecycleScope.launch {
                try {
                    RetrofitClient.instance.claimVehicle(userId)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to claim vehicle: ${e.message}")
                }
            }
        }
    }

    /**
     * Restores monitoring UI visuals after activity recreate (display mode switch etc.).
     * Does NOT reset metrics to 0 — the BroadcastReceiver will repopulate them.
     * Does NOT restart the service — it's already running.
     */
    private fun restoreMonitoringVisuals() {
        val primaryColor = ContextCompat.getColor(this, R.color.primary)

        binding.monitoringRing.setImageResource(R.drawable.circle_monitor_active)
        binding.monitoringStatusText.setText(R.string.monitoring_active)
        binding.monitoringStatusText.setTextColor(primaryColor)
        binding.monitoringIcon.imageTintList = ColorStateList.valueOf(primaryColor)

        binding.btnMonitoring.text = getString(R.string.btn_stop_monitoring)
        binding.btnMonitoring.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.destructive))

        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(binding.monitoringRing, "alpha", 1f, 0.45f, 1f).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            start()
        }

        // Show metrics card and restore the last saved metrics
        binding.cardMetrics.visibility = android.view.View.VISIBLE
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastPerclos = prefs.getFloat("last_perclos", 0.0f)
        val lastYawns = prefs.getInt("last_yawns", 0)
        binding.tvPerclos.text = String.format("%.1f%%", lastPerclos.toDouble())
        binding.tvYawns.text = lastYawns.toString()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_IS_MONITORING, false).apply()
        
        pulseAnimator?.cancel()
        binding.monitoringRing.alpha = 1f
        binding.cardMetrics.visibility = android.view.View.GONE

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

        // 🛑 STOP BACKGROUND SERVICE
        val serviceIntent = Intent(this, DrowsySyncBackgroundService::class.java)
        stopService(serviceIntent)

        // 🚗 Unclaim the vehicle on the backend so isCurrentlyDriving is set to false
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null)
        if (userId != null) {
            lifecycleScope.launch {
                try {
                    RetrofitClient.instance.unclaimVehicle(userId)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to unclaim vehicle: ${e.message}")
                }
            }
        }
    }

    // ── Register/Unregister the Receiver with the Activity Lifecycle ──────────
    // Using onResume/onPause (NOT onStart/onStop) because onStop fires the instant
    // our own alert overlay (MicrosleepAlertActivity / SymptomAlertActivity) launches,
    // which would kill the receiver mid-session and freeze the metrics display.
    override fun onResume() {
        super.onResume()
        // Refresh the header name every time the activity comes to foreground.
        // This covers: first launch after login, returning from EditProfile, etc.
        if (::binding.isInitialized) {
            refreshHeaderName()
        }
        val filter = IntentFilter("com.example.drowsysyncapp.UPDATE_METRICS")
        ContextCompat.registerReceiver(
            this,
            liveDataReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(liveDataReceiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered — safe to ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
    }
}