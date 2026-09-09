package com.example.drowsysyncapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.drowsysyncapp.databinding.ActivityLoginBinding
import com.example.drowsysyncapp.network.LoginRequest
import com.example.drowsysyncapp.network.RetrofitClient
import kotlinx.coroutines.launch
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Lock UI
            binding.btnLogin.isEnabled = false
            binding.btnLogin.text = "Logging in..."
            binding.btnNavToRegister.isEnabled = false
            binding.etEmail.isEnabled = false
            binding.etPassword.isEnabled = false

            lifecycleScope.launch {
                try {
                    val request = LoginRequest(email, password)
                    val response = RetrofitClient.instance.loginUser(request)

                    if (response.isSuccessful && response.body() != null) {
                        val authResponse = response.body()!!
                        val user = authResponse.user

                        if (user != null) {
                            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                            val nameToSave = user.name.trim().ifEmpty { "Driver" }
                            prefs.edit()
                                .putBoolean(MainActivity.KEY_LOGGED_IN, true)
                                .putString("user_id", user.id)
                                .putString("user_name", nameToSave)
                                .putString("user_email", user.email)
                                .putString("user_phone", user.phone ?: "")
                                .putString("license_serial", user.licenseSerial ?: "")
                                .putString("emergency_name", user.emergencyContact?.name ?: "")
                                .putString("emergency_phone", user.emergencyContact?.phone ?: "")
                                .apply()

                            android.util.Log.d("LoginActivity", "Saved user_name='$nameToSave' to SharedPreferences")
                            Toast.makeText(this@LoginActivity, "Login Successful!", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        } else {
                            showError("User object missing in response")
                        }
                    } else {
                        val errorMsg = try {
                            val errorJson = JSONObject(response.errorBody()?.string() ?: "")
                            errorJson.optString("error", response.message())
                        } catch (e: Exception) {
                            response.message()
                        }

                        // Show user-friendly messages for specific server errors
                        val displayMsg = when {
                            errorMsg.contains("verify your email", ignoreCase = true) ->
                                "Please verify your email before logging in. Check your inbox for the verification code."
                            else -> errorMsg
                        }
                        showError(displayMsg)
                    }
                } catch (e: Exception) {
                    showError("Network Error: ${e.message}")
                }
            }
        }

        binding.btnNavToRegister.setOnClickListener {
            startActivity(Intent(this, RegistrationActivity::class.java))
            finish()
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, "Login Failed: $message", Toast.LENGTH_LONG).show()
        binding.btnLogin.isEnabled = true
        binding.btnLogin.text = "Login"
        binding.btnNavToRegister.isEnabled = true
        binding.etEmail.isEnabled = true
        binding.etPassword.isEnabled = true
    }
}
