package com.example.drowsysyncapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.drowsysyncapp.databinding.ActivityEditProfileBinding
import com.example.drowsysyncapp.network.ProfileUpdateRequest
import com.example.drowsysyncapp.network.RetrofitClient
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.json.JSONObject

class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private var validity = mutableMapOf<String, Boolean>()
    private var userId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", "")
        val email = prefs.getString("user_email", "")
        val phone = prefs.getString("user_phone", "")
        val licenseSerial = prefs.getString("license_serial", "")
        val emergencyName = prefs.getString("emergency_name", "")
        val emergencyPhone = prefs.getString("emergency_phone", "")
        userId = prefs.getString("user_id", null)

        // Assume prefilled fields are valid initially
        validity["name"] = true
        validity["email"] = true
        validity["phone"] = true
        validity["licenseSerial"] = true
        validity["emergencyName"] = true
        validity["emergencyPhone"] = true

        binding.etName.setText(name)
        binding.etEmail.setText(email)
        binding.etPhone.setText(phone)
        binding.etLicenseSerial.setText(licenseSerial)
        binding.etEmergencyName.setText(emergencyName)
        binding.etEmergencyPhone.setText(emergencyPhone)

        setupValidation()
        updateSubmitButton()

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            if (userId != null) {
                updateProfile(
                    userId!!,
                    binding.etName.text.toString().trim(),
                    binding.etEmail.text.toString().trim(),
                    binding.etPhone.text.toString().trim(),
                    binding.etLicenseSerial.text.toString().trim(),
                    binding.etEmergencyName.text.toString().trim(),
                    binding.etEmergencyPhone.text.toString().trim(),
                    email ?: ""
                )
            } else {
                Toast.makeText(this, "User ID not found. Please log in again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateProfile(
        userId: String,
        name: String,
        email: String,
        phone: String,
        licenseSerial: String,
        emergencyName: String,
        emergencyPhone: String,
        oldEmail: String
    ) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            try {
                val request = ProfileUpdateRequest(
                    name = name,
                    email = email,
                    phone = phone,
                    licenseSerial = licenseSerial,
                    emergencyName = emergencyName,
                    emergencyPhone = emergencyPhone
                )
                val response = RetrofitClient.instance.updateProfile(userId, request)

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val updatedUser = body.user
                    val emailChanged = body.emailChanged ?: false

                    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                        .putString("user_name", updatedUser.name)
                        .putString("user_phone", updatedUser.phone ?: "")
                        .putString("license_serial", updatedUser.licenseSerial ?: "")
                        .putString("emergency_name", updatedUser.emergencyName ?: "")
                        .putString("emergency_phone", updatedUser.emergencyPhone ?: "")

                    if (!emailChanged) {
                        editor.putString("user_email", updatedUser.email)
                    }
                    editor.apply()

                    if (emailChanged) {
                        Toast.makeText(this@EditProfileActivity, "Verification code sent to $email", Toast.LENGTH_LONG).show()
                        val intent = Intent(this@EditProfileActivity, EmailVerificationActivity::class.java).apply {
                            putExtra("email", email)
                            putExtra("is_change_email", true)
                        }
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this@EditProfileActivity, "Profile updated successfully!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    val errorMsg = try {
                        val errorJson = JSONObject(response.errorBody()?.string() ?: "")
                        errorJson.optString("error", "Failed to update profile")
                    } catch (e: Exception) {
                        "Failed to update profile"
                    }
                    if (errorMsg.contains("registered", ignoreCase = true) || errorMsg.contains("email", ignoreCase = true)) {
                        setError(binding.tilEmail, "email", errorMsg)
                    } else {
                        Toast.makeText(this@EditProfileActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@EditProfileActivity, "Network Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                updateSubmitButton() // re-enable based on validity
            }
        }
    }

    // ── Watcher helpers ────────────────────────────────────────────────────────

    private fun watcher(block: (String) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = block(s?.toString() ?: "")
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    private fun phoneWatcher(til: TextInputLayout, key: String) = object : TextWatcher {
        var ignore = false
        override fun afterTextChanged(s: Editable?) {
            if (ignore) return
            val et = til.editText ?: return
            val text = s?.toString() ?: ""
            if (!text.startsWith("+60")) {
                ignore = true
                et.setText("+60")
                et.setSelection(et.text!!.length)
                ignore = false
            }
            validatePhone(til, key, et.text.toString())
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    // ── Field validators ───────────────────────────────────────────────────────

    private fun validateName(til: TextInputLayout, key: String, value: String) {
        when {
            value.isBlank()   -> setError(til, key, getString(R.string.err_name_required))
            value.length < 3  -> setError(til, key, getString(R.string.err_name_min))
            else              -> clearError(til, key)
        }
    }

    private fun validateEmail(til: TextInputLayout, key: String, value: String) {
        val regex = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+\$")
        when {
            value.isBlank()          -> setError(til, key, getString(R.string.err_email_required))
            !regex.matches(value)    -> setError(til, key, getString(R.string.err_email_invalid))
            else                     -> clearError(til, key)
        }
    }

    private fun validatePhone(til: TextInputLayout, key: String, value: String) {
        val clean = value.replace(Regex("[\\s-]"), "")
        val regex = Regex("^\\+60\\d{9,10}\$")
        when {
            value.isBlank() || value == "+60" -> setError(til, key, getString(R.string.err_phone_required))
            !regex.matches(clean)             -> setError(til, key, getString(R.string.err_phone_invalid))
            else                              -> clearError(til, key)
        }
    }

    private fun validateLicense(til: TextInputLayout, key: String, value: String) {
        when {
            value.isBlank()   -> setError(til, key, getString(R.string.err_license_required))
            value.length < 6  -> setError(til, key, getString(R.string.err_license_min))
            else              -> clearError(til, key)
        }
    }

    // ── Error helpers ──────────────────────────────────────────────────────────

    private fun setError(til: TextInputLayout, key: String, msg: String) {
        til.error = msg
        validity[key] = false
        updateSubmitButton()
    }

    private fun clearError(til: TextInputLayout, key: String) {
        til.error = null
        til.isErrorEnabled = false
        validity[key] = true
        updateSubmitButton()
    }

    private fun updateSubmitButton() {
        val allValid = validity.values.all { it }
        binding.btnSave.isEnabled = allValid
        binding.btnSave.text =
            if (allValid) "Save Changes"
            else getString(R.string.btn_complete_fields)
    }

    // ── Wire up all watchers ───────────────────────────────────────────────────

    private fun setupValidation() {
        binding.etName.addTextChangedListener(watcher {
            validateName(binding.tilName, "name", it)
        })
        binding.etEmail.addTextChangedListener(watcher {
            validateEmail(binding.tilEmail, "email", it)
        })
        binding.etPhone.addTextChangedListener(phoneWatcher(binding.tilPhone, "phone"))
        binding.etLicenseSerial.addTextChangedListener(watcher {
            validateLicense(binding.tilLicenseSerial, "licenseSerial", it)
        })
        binding.etEmergencyName.addTextChangedListener(watcher {
            validateName(binding.tilEmergencyName, "emergencyName", it)
        })
        binding.etEmergencyPhone.addTextChangedListener(
            phoneWatcher(binding.tilEmergencyPhone, "emergencyPhone")
        )
    }
}
