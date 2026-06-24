package com.example.drowsysyncapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.drowsysyncapp.databinding.ActivityChangeOwnerBinding
import com.example.drowsysyncapp.network.ReleaseVehicleRequest
import com.example.drowsysyncapp.network.RetrofitClient
import kotlinx.coroutines.launch
import org.json.JSONObject

class ChangeOwnerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChangeOwnerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangeOwnerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null)
        val currentPlate = prefs.getString("vehicle_id", null)

        // Pre-fill the read-only car plate field
        if (!currentPlate.isNullOrEmpty()) {
            binding.etCarPlate.setText(currentPlate)
        } else {
            // No vehicle assigned — disable the form
            binding.etCarPlate.setText(getString(R.string.release_no_vehicle))
            binding.etPassword.isEnabled = false
            binding.cbConfirm.isEnabled = false
            binding.btnConfirmTransfer.isEnabled = false
        }

        binding.btnBack.setOnClickListener { finish() }

        // Confirmation checkbox gates the submit button
        binding.cbConfirm.setOnCheckedChangeListener { _, isChecked ->
            binding.btnConfirmTransfer.isEnabled = isChecked && !currentPlate.isNullOrEmpty()
        }

        binding.btnConfirmTransfer.setOnClickListener {
            val password = binding.etPassword.text?.toString()?.trim() ?: ""

            if (password.isEmpty()) {
                binding.tilPassword.error = "Password is required"
                return@setOnClickListener
            }
            binding.tilPassword.error = null

            if (userId == null) {
                Toast.makeText(this, "User ID not found. Please log in again.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // UI loading state
            binding.btnConfirmTransfer.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE

            lifecycleScope.launch {
                try {
                    val request = ReleaseVehicleRequest(userId, password)
                    val response = RetrofitClient.instance.releaseVehicle(request)

                    if (response.isSuccessful) {
                        // Clear ALL SharedPreferences to log out the user
                        prefs.edit().clear().apply()

                        // Stop background service if it is running
                        val serviceIntent = Intent(this@ChangeOwnerActivity, DrowsySyncBackgroundService::class.java)
                        stopService(serviceIntent)

                        Toast.makeText(
                            this@ChangeOwnerActivity,
                            getString(R.string.release_success),
                            Toast.LENGTH_LONG
                        ).show()

                        // Navigate to LoginActivity and clear task stack
                        val loginIntent = Intent(this@ChangeOwnerActivity, LoginActivity::class.java)
                        loginIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(loginIntent)
                        finish()
                    } else {
                        val errorMsg = try {
                            val errorJson = JSONObject(response.errorBody()?.string() ?: "")
                            errorJson.optString("error", response.message())
                        } catch (e: Exception) {
                            response.message()
                        }
                        Toast.makeText(this@ChangeOwnerActivity, "Release failed: $errorMsg", Toast.LENGTH_LONG).show()
                        binding.btnConfirmTransfer.isEnabled = true
                        binding.progressBar.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@ChangeOwnerActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                    binding.btnConfirmTransfer.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }
}
