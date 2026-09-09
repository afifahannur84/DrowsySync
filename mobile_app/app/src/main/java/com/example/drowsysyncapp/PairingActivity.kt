package com.example.drowsysyncapp

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.drowsysyncapp.network.PairingRequest
import com.example.drowsysyncapp.network.UnpairRequest
import com.example.drowsysyncapp.network.RetrofitClient
import kotlinx.coroutines.launch

/**
 * PairingActivity — one-time screen to link the user's account with their Pi device.
 *
 * The user enters the Device Serial (printed on the Pi's label / startup screen).
 * This is the same approach used by commercial products (routers, IoT devices) —
 * every unit ships with a unique serial number on the device or in the manual.
 *
 * To find your Pi's serial:
 *  - Check the sticker/label on your Pi case
 *  - OR run:  cat /proc/cpuinfo | grep Serial
 *  - OR check the Pi's startup log output (it prints "DEVICE ID=..." on boot)
 *
 * After pairing, the device ID is saved to SharedPreferences so the app
 * can poll /api/devices/:deviceId/status to show the Pi status card.
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var etDeviceId: EditText
    private lateinit var btnPair: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var btnUnpair: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)

        etDeviceId = findViewById(R.id.etDeviceId)
        btnPair = findViewById(R.id.btnPair)
        progressBar = findViewById(R.id.pairingProgress)
        tvStatus = findViewById(R.id.tvPairingStatus)
        btnUnpair = findViewById(R.id.btnUnpair)

        // Show currently paired device if already set
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val existingId = prefs.getString("paired_device_id", null)
        if (!existingId.isNullOrEmpty()) {
            tvStatus.text = "Currently paired to: $existingId"
            tvStatus.visibility = View.VISIBLE
            btnUnpair.visibility = View.VISIBLE
        }

        etDeviceId.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                btnPair.isEnabled = s?.toString()?.trim()?.length ?: 0 >= 6
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnPair.setOnClickListener { attemptPairing() }
        btnUnpair.setOnClickListener { attemptUnpairing(existingId) }
        findViewById<View>(R.id.btnCancelPairing)?.setOnClickListener { finish() }
    }

    private fun attemptPairing() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null)
        if (userId == null) {
            Toast.makeText(this, "Please log in first", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceId = etDeviceId.text.toString().trim().uppercase()
        if (deviceId.isEmpty()) return

        btnPair.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.pairDevice(PairingRequest(userId, deviceId))
                if (response.isSuccessful) {
                    prefs.edit().putString("paired_device_id", deviceId).apply()
                    tvStatus.text = "✅ Successfully paired to: $deviceId"
                    tvStatus.visibility = View.VISIBLE
                    Toast.makeText(this@PairingActivity, "Pi paired successfully!", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    tvStatus.text = "❌ Pairing failed — serial not recognised. Check the label on your Pi."
                    tvStatus.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                tvStatus.text = "⚠️ Network error: ${e.message}"
                tvStatus.visibility = View.VISIBLE
            } finally {
                progressBar.visibility = View.GONE
                btnPair.isEnabled = true
            }
        }
    }

    private fun attemptUnpairing(deviceId: String?) {
        if (deviceId.isNullOrEmpty()) return
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null) ?: return

        progressBar.visibility = View.VISIBLE
        btnUnpair.isEnabled = false

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.unpairDevice(UnpairRequest(userId, deviceId))
                if (response.isSuccessful) {
                    prefs.edit().remove("paired_device_id").apply()
                    tvStatus.text = "Device unpaired successfully."
                    btnUnpair.visibility = View.GONE
                    Toast.makeText(this@PairingActivity, "Unpaired!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@PairingActivity, "Failed to unpair device", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PairingActivity, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                progressBar.visibility = View.GONE
                btnUnpair.isEnabled = true
            }
        }
    }
}
