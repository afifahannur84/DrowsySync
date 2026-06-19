package com.example.drowsysyncapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.drowsysyncapp.network.FatigueLogResponse
import com.example.drowsysyncapp.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * DrowsySyncBackgroundService
 * ─────────────────────────────────────────────────────────────────────────────
 * A persistent Android Foreground Service that polls the Node.js backend every
 * 1.5 seconds for the latest fatigue stage. Based on the stage, it fires:
 *
 *   Stage 0–1 → No alert (clears any existing alert notification)
 *   Stage 2   → Launches SymptomAlertActivity  (semi-transparent warning overlay)
 *   Stage 3   → High-priority full-screen intent → MicrosleepAlertActivity
 *               (pops over Waze / Google Maps / lock screen)
 *
 * Lifecycle:
 *   Start: call  Intent(context, DrowsySyncBackgroundService::class.java)
 *          then  startForegroundService(intent)
 *   Stop:  call  stopService(intent)  from the app, or service calls stopSelf()
 */
class DrowsySyncBackgroundService : Service() {

    companion object {
        private const val TAG = "DrowsySyncService"

        // ── Notification channel IDs ──────────────────────────────────────────
        /** Low-priority channel used for the persistent "Service Running" notice. */
        private const val CHANNEL_PERSISTENT = "drowsysync_persistent"

        /** High-priority channel used for Stage 2/3 fatigue alerts. */
        private const val CHANNEL_ALERT = "drowsysync_alert"

        // ── Notification IDs ──────────────────────────────────────────────────
        /** ID of the mandatory foreground notification (always visible). */
        private const val NOTIF_ID_PERSISTENT = 1

        /** ID of the alert notification (shown on stage transitions). */
        private const val NOTIF_ID_ALERT = 2

        // ── Polling interval ──────────────────────────────────────────────────
        private const val POLL_INTERVAL_MS = 1500L
    }

    // ── Coroutine scope tied to service lifetime ──────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var pollingJob: Job? = null

    // ── State tracking — prevents firing duplicate notifications every second ─
    private var lastAlertedStage: Int = -1

    // ─────────────────────────────────────────────────────────────────────────
    // Service lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Promote this service to the foreground immediately to avoid ANR.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID_PERSISTENT,
                buildPersistentNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID_PERSISTENT, buildPersistentNotification())
        }

        startPolling()
        return START_STICKY // Restart service automatically if killed by OS
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed — stopping polling")
        pollingJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null // Not a bound service

    // ─────────────────────────────────────────────────────────────────────────
    // Polling logic
    // ─────────────────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollingJob = serviceScope.launch {
            while (isActive) {
                fetchAndHandleLatestLog()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun fetchAndHandleLatestLog() {
        try {
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            // Use "DDH4321" as fallback — must match VEHICLE_ID in the Python script exactly
            val vehicleId = prefs.getString("vehicle_id", "DDH4321") ?: "DDH4321"
            Log.d(TAG, "Polling latest log for vehicleId='$vehicleId'")

            val response = RetrofitClient.instance.getLatestVehicleLog(vehicleId)
            if (response.isSuccessful) {
                val latest = response.body()
                if (latest != null) {
                    Log.d(TAG, "Got log: PERCLOS=${latest.perclos}% yawns=${latest.recentYawnCount} stage=${latest.stage} ts=${latest.timestamp}")

                    // ── 1. ALWAYS broadcast metrics to refresh the UI every poll cycle ──────
                    // This must be UNCONDITIONAL so the dashboard never freezes while the
                    // driver stays in Stage 0 or Stage 1 (the early return in handleStageChange
                    // was silently swallowing these updates before).
                    val updateIntent = Intent("com.example.drowsysyncapp.UPDATE_METRICS")
                    updateIntent.setPackage(packageName)
                    updateIntent.putExtra("PERCLOS", latest.perclos)
                    updateIntent.putExtra("YAWNS", latest.recentYawnCount)
                    updateIntent.putExtra("STAGE", latest.stage)
                    updateIntent.putExtra("TIMESTAMP", latest.timestamp)
                    sendBroadcast(updateIntent)

                    // ── 2. CONDITIONALLY fire alert notifications only on stage transitions ─
                    handleStageChange(latest)
                } else {
                    Log.w(TAG, "Got HTTP 200 but body was null — check server response format")
                }
            } else {
                Log.w(TAG, "Poll failed — HTTP ${response.code()} for vehicleId='$vehicleId'")
            }
        } catch (e: Exception) {
            // Network is offline, server not reachable — fail silently
            Log.w(TAG, "Network error during poll: ${e.message}")
        }
    }

    /**
     * Compares the newly fetched stage to the last-alerted stage.
     * Only triggers UI actions on transitions, not every poll cycle.
     */
    private fun handleStageChange(log: FatigueLogResponse) {
        val currentStage = log.stage

        if (currentStage == lastAlertedStage) return // No change — do nothing

        Log.i(TAG, "Stage transition: $lastAlertedStage → $currentStage (${log.status})")
        lastAlertedStage = currentStage

        val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        when (currentStage) {
            0, 1 -> {
                // Normal or Early Fatigue — clear any lingering alert notification
                notifManager.cancel(NOTIF_ID_ALERT)
            }
            2 -> {
                // Active Drowsiness — launch the transparent SymptomAlertActivity
                fireStage2Warning(notifManager, log)
            }
            3 -> {
                // Critical Alarm — full-screen intent directly over any active app
                fireStage3CriticalAlarm(notifManager, log)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Alert notification builders
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Stage 2: Fires a high-priority notification that launches SymptomAlertActivity.
     * No full-screen intent — this shows as a heads-up banner over other apps.
     */
    private fun fireStage2Warning(manager: NotificationManager, log: FatigueLogResponse) {
        val activityIntent = Intent(this, SymptomAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_warning_notification)
            .setContentTitle("⚠️ Early Drowsiness Detected")
            .setContentText("PERCLOS ${log.perclos}% — ${log.recentYawnCount} yawn(s). Tap to review.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(NOTIF_ID_ALERT, notification)
    }

    /**
     * Stage 3: Fires a CRITICAL full-screen intent notification.
     *
     * On Android 10+, setFullScreenIntent() will:
     *   • Pop up over lock screen (requires showWhenLocked + turnScreenOn in manifest)
     *   • Pop up over any running app (requires USE_FULL_SCREEN_INTENT permission)
     *   • Launch MicrosleepAlertActivity directly if the device is actively in-use
     *
     * The driver should see the emergency alert within ~1 second of Stage 3 being detected.
     */
    private fun fireStage3CriticalAlarm(manager: NotificationManager, log: FatigueLogResponse) {
        // Full-screen activity intent — opens MicrosleepAlertActivity directly
        val fullScreenIntent = Intent(this, MicrosleepAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_HISTORY
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 1, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Also build a tap-on-notification intent for when the alert is shown as banner
        val tapIntent = Intent(this, MicrosleepAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 2, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val microsleepFlag = if (log.microsleepActive) " | MICROSLEEP" else ""
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_warning_notification)
            .setContentTitle("🚨 CRITICAL ALARM — Stage 3")
            .setContentText("PERCLOS ${log.perclos}%$microsleepFlag — PULL OVER IMMEDIATELY")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("PERCLOS: ${log.perclos}%$microsleepFlag\n\nYou are critically fatigued. Pull over and rest immediately.")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lock screen
            .setFullScreenIntent(fullScreenPendingIntent, true)   // ← THE KEY LINE
            .setContentIntent(tapPendingIntent)
            .setOngoing(true)     // Cannot be dismissed by the driver while active
            .setAutoCancel(false)
            .build()

        manager.notify(NOTIF_ID_ALERT, notification)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification channel + persistent notification setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        // Persistent channel (low importance — just keeps service alive)
        val persistentChannel = NotificationChannel(
            CHANNEL_PERSISTENT,
            "DrowsySync Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "DrowsySync is actively monitoring for driver fatigue."
        }

        // Alert channel (HIGH importance — shows heads-up and full-screen)
        val alertChannel = NotificationChannel(
            CHANNEL_ALERT,
            "Fatigue Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Critical fatigue alerts that can interrupt other apps."
            // Ensure the alert channel can bypass Do Not Disturb
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(persistentChannel)
        manager.createNotificationChannel(alertChannel)
    }

    private fun buildPersistentNotification(): Notification {
        // Tapping the persistent notification opens MainActivity
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_PERSISTENT)
            .setSmallIcon(R.drawable.ic_warning_notification)
            .setContentTitle("DrowsySync is active")
            .setContentText("Monitoring driver fatigue in the background…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }
}
