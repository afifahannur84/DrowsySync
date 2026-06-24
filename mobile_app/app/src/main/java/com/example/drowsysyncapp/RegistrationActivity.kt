package com.example.drowsysyncapp

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import com.example.drowsysyncapp.databinding.ActivityRegistrationBinding
import com.google.android.material.textfield.TextInputLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.drowsysyncapp.network.RetrofitClient
import com.example.drowsysyncapp.network.UserRequest
import kotlinx.coroutines.launch
import org.json.JSONObject

class RegistrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegistrationBinding

    // Tracks validity per field name
    private val validity = mutableMapOf(
        "fullName"       to false,
        "email"          to false,
        "phone"          to false,
        "vehiclePlate"   to false,
        "licenseSerial"  to false,
        "password"       to false,
        "confirmPassword" to false,
        "emergencyName"  to false,
        "emergencyPhone" to false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupValidation()

        binding.btnNavToLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.btnSubmit.setOnClickListener {
            if (validity.values.all { it }) {
                val name = binding.etFullName.text.toString()
                val email = binding.etEmail.text.toString()
                val password = binding.etPassword.text.toString()
                val vehiclePlate = binding.etVehiclePlate.text.toString().replace("\\s+".toRegex(), "").uppercase()
                val phone = binding.etPhone.text.toString()
                val licenseSerial = binding.etLicenseSerial.text.toString()
                val emergencyName = binding.etEmergencyName.text.toString()
                val emergencyPhone = binding.etEmergencyPhone.text.toString()

                binding.btnSubmit.isEnabled = false
                binding.btnSubmit.text = "Registering..."

                lifecycleScope.launch {
                    try {
                        val request = UserRequest(
                            name = name,
                            email = email,
                            password = password,
                            vehicleId = vehiclePlate,
                            phone = phone,
                            licenseSerial = licenseSerial,
                            emergencyName = emergencyName,
                            emergencyPhone = emergencyPhone
                        )
                        val response = RetrofitClient.instance.registerUser(request)
                        
                        if (response.isSuccessful && response.body() != null) {
                            val code = response.body()!!.verificationCode
                            val intent = Intent(this@RegistrationActivity, EmailVerificationActivity::class.java)
                            intent.putExtra("email", email)
                            intent.putExtra("verificationCode", code)
                            startActivity(intent)
                        } else {
                            val errorMsg = try {
                                val errorJson = JSONObject(response.errorBody()?.string() ?: "")
                                errorJson.optString("error", response.message())
                            } catch (e: Exception) {
                                response.message()
                            }
                            
                            if (errorMsg.contains("already registered", ignoreCase = true) || 
                                errorMsg.contains("already exists", ignoreCase = true)) {
                                binding.tilEmail.error = getString(R.string.err_email_already_registered)
                                Toast.makeText(this@RegistrationActivity, getString(R.string.err_email_already_registered), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@RegistrationActivity, "Registration failed: $errorMsg", Toast.LENGTH_LONG).show()
                            }
                            binding.btnSubmit.isEnabled = true
                            binding.btnSubmit.text = getString(R.string.btn_verify_email)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@RegistrationActivity, "Network Error: ${e.message}", Toast.LENGTH_LONG).show()
                        binding.btnSubmit.isEnabled = true
                        binding.btnSubmit.text = getString(R.string.btn_verify_email)
                    }
                }
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

    private fun validatePlate(til: TextInputLayout, key: String, value: String) {
        val regex = Regex("^[A-Z]{1,3}\\s?\\d{1,4}\\s?[A-Z]?\$", RegexOption.IGNORE_CASE)
        when {
            value.isBlank()       -> setError(til, key, getString(R.string.err_plate_required))
            !regex.matches(value) -> setError(til, key, getString(R.string.err_plate_invalid))
            else                  -> clearError(til, key)
        }
    }

    private fun validateLicense(til: TextInputLayout, key: String, value: String) {
        when {
            value.isBlank()   -> setError(til, key, getString(R.string.err_license_required))
            value.length < 6  -> setError(til, key, getString(R.string.err_license_min))
            else              -> clearError(til, key)
        }
    }

    private fun validatePassword(til: TextInputLayout, key: String, value: String) {
        val complex = Regex("(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)")
        when {
            value.isBlank()        -> setError(til, key, getString(R.string.err_password_required))
            value.length < 8       -> setError(til, key, getString(R.string.err_password_min))
            !complex.containsMatchIn(value) -> setError(til, key, getString(R.string.err_password_complexity))
            else                   -> clearError(til, key)
        }
        // Re-validate confirm whenever password changes
        val confirm = binding.etConfirmPassword.text?.toString() ?: ""
        if (confirm.isNotBlank()) validateConfirm(binding.tilConfirmPassword, "confirmPassword", value, confirm)
    }

    private fun validateConfirm(til: TextInputLayout, key: String, pass: String, confirm: String) {
        when {
            confirm.isBlank()    -> setError(til, key, getString(R.string.err_confirm_required))
            pass != confirm      -> setError(til, key, getString(R.string.err_confirm_mismatch))
            else                 -> clearError(til, key)
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
        binding.btnSubmit.isEnabled = allValid
        binding.btnSubmit.text =
            if (allValid) getString(R.string.btn_verify_email)
            else getString(R.string.btn_complete_fields)
    }

    // ── Wire up all watchers ───────────────────────────────────────────────────

    private fun setupValidation() {
        binding.etFullName.addTextChangedListener(watcher {
            validateName(binding.tilFullName, "fullName", it)
        })
        binding.etEmail.addTextChangedListener(watcher {
            validateEmail(binding.tilEmail, "email", it)
        })
        binding.etPhone.addTextChangedListener(phoneWatcher(binding.tilPhone, "phone"))
        binding.etVehiclePlate.addTextChangedListener(watcher {
            // Force uppercase
            val upper = it.uppercase()
            if (it != upper) {
                binding.etVehiclePlate.removeTextChangedListener(this as? TextWatcher)
                binding.etVehiclePlate.setText(upper)
                binding.etVehiclePlate.setSelection(upper.length)
            }
            validatePlate(binding.tilVehiclePlate, "vehiclePlate", upper)
        })
        binding.etLicenseSerial.addTextChangedListener(watcher {
            validateLicense(binding.tilLicenseSerial, "licenseSerial", it)
        })
        binding.etPassword.addTextChangedListener(watcher {
            validatePassword(binding.tilPassword, "password", it)
        })
        binding.etConfirmPassword.addTextChangedListener(watcher {
            validateConfirm(
                binding.tilConfirmPassword, "confirmPassword",
                binding.etPassword.text?.toString() ?: "", it
            )
        })
        binding.etEmergencyName.addTextChangedListener(watcher {
            validateName(binding.tilEmergencyName, "emergencyName", it)
        })
        binding.etEmergencyPhone.addTextChangedListener(
            phoneWatcher(binding.tilEmergencyPhone, "emergencyPhone")
        )
    }
}
