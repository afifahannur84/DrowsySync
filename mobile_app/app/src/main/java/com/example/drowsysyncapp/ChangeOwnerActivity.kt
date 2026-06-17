package com.example.drowsysyncapp

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.drowsysyncapp.databinding.ActivityChangeOwnerBinding
import com.example.drowsysyncapp.network.RetrofitClient
import com.example.drowsysyncapp.network.VehicleUpdateRequest
import kotlinx.coroutines.launch

class ChangeOwnerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChangeOwnerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangeOwnerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // Confirmation checkbox gates the submit button
        binding.cbConfirm.setOnCheckedChangeListener { _, isChecked ->
            binding.btnConfirmTransfer.isEnabled = isChecked
        }

        binding.btnConfirmTransfer.setOnClickListener {
            val carModel = binding.etCarModel.text?.toString()?.trim() ?: ""
            val carPlate = binding.etCarPlate.text?.toString()?.trim() ?: ""

            if (carModel.isEmpty() || carPlate.isEmpty()) {
                Toast.makeText(this, "Please fill in both Car Model and Car Plate", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val userId = prefs.getString("user_id", null)

            if (userId == null) {
                Toast.makeText(this, "User ID not found. Please log in again.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // UI loading state
            binding.btnConfirmTransfer.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE

            lifecycleScope.launch {
                try {
                    val request = VehicleUpdateRequest(carModel, carPlate)
                    val response = RetrofitClient.instance.updateVehicleDetails(userId, request)

                    if (response.isSuccessful) {
                        prefs.edit()
                            .putString("car_model", carModel)
                            .putString("car_plate", carPlate)
                            .apply()

                        Toast.makeText(this@ChangeOwnerActivity, "Vehicle details updated successfully!", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        Toast.makeText(this@ChangeOwnerActivity, "Failed to update: ${response.message()}", Toast.LENGTH_LONG).show()
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
