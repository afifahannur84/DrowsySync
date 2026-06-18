package com.example.drowsysyncapp

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.drowsysyncapp.databinding.ActivityEditProfileBinding
import com.example.drowsysyncapp.network.ProfileUpdateRequest
import com.example.drowsysyncapp.network.RetrofitClient
import kotlinx.coroutines.launch

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load existing profile data
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val email = prefs.getString("user_email", "")
        val phone = prefs.getString("user_phone", "")
        val userId = prefs.getString("user_id", null)

        binding.etEmail.setText(email)
        binding.etPhone.setText(phone)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            val newEmail = binding.etEmail.text.toString().trim()
            val newPhone = binding.etPhone.text.toString().trim()

            if (newEmail.isEmpty()) {
                binding.etEmail.error = "Email is required"
                return@setOnClickListener
            }

            if (userId != null) {
                updateProfile(userId, newEmail, newPhone)
            } else {
                Toast.makeText(this, "User ID not found. Please log in again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateProfile(userId: String, email: String, phone: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            try {
                val request = ProfileUpdateRequest(email, phone)
                val response = RetrofitClient.instance.updateProfile(userId, request)

                if (response.isSuccessful && response.body() != null) {
                    val updatedUser = response.body()!!.user

                    // Save new values to SharedPreferences
                    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("user_email", updatedUser.email)
                        .putString("user_phone", updatedUser.phone ?: "")
                        .apply()

                    Toast.makeText(this@EditProfileActivity, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@EditProfileActivity, "Failed to update profile", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@EditProfileActivity, "Network Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.btnSave.isEnabled = true
            }
        }
    }
}
