package com.example.drowsysyncapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.drowsysyncapp.databinding.ActivityChangeOwnerBinding

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
            val email = binding.etEmail.text?.toString().orEmpty()
            val password = binding.etPassword.text?.toString().orEmpty()

            if (email.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Please fill in your credentials", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Clear login state → back to Registration
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(MainActivity.KEY_LOGGED_IN, false).apply()

            val intent = Intent(this, RegistrationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
        }
    }
}
