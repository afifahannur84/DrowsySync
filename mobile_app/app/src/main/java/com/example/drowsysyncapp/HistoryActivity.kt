package com.example.drowsysyncapp

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.drowsysyncapp.databinding.ActivityHistoryBinding
import com.example.drowsysyncapp.network.RetrofitClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Calendar

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val adapter = FatigueLogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // RecyclerView setup
        binding.rvFatigueEvents.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            this.adapter = this@HistoryActivity.adapter
            addItemDecoration(DividerItemDecoration(this@HistoryActivity, DividerItemDecoration.VERTICAL))
        }

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

        binding.btnDownloadReport.setOnClickListener {
            val userId = prefs.getString("user_id", null) ?: return@setOnClickListener

            // Inflate custom picker view
            val dialogView = layoutInflater.inflate(R.layout.dialog_month_year_picker, null)
            val spinnerMonth = dialogView.findViewById<Spinner>(R.id.spinnerMonth)
            val spinnerYear = dialogView.findViewById<Spinner>(R.id.spinnerYear)

            val months = arrayOf(
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"
            )
            val calendar = Calendar.getInstance()
            val currentYear = calendar.get(Calendar.YEAR)
            val currentMonth = calendar.get(Calendar.MONTH) // 0..11
            val years = arrayOf(currentYear.toString(), (currentYear - 1).toString(), (currentYear - 2).toString())

            spinnerMonth.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, months)
            spinnerMonth.setSelection(currentMonth)

            spinnerYear.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, years)
            spinnerYear.setSelection(0)

            MaterialAlertDialogBuilder(this)
                .setTitle("Select Report Period")
                .setView(dialogView)
                .setPositiveButton("Email PDF Report") { dialog, _ ->
                    val selectedMonth = spinnerMonth.selectedItemPosition + 1 // 1-indexed
                    val selectedYear = years[spinnerYear.selectedItemPosition].toInt()

                    dialog.dismiss()
                    triggerReportGeneration(userId, selectedYear, selectedMonth)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fetchHistory()
    }

    private fun triggerReportGeneration(userId: String, year: Int, month: Int) {
        binding.btnDownloadReport.isEnabled = false
        binding.btnDownloadReport.text = "Generating Report..."

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.generateReport(userId, year, month)
                if (response.isSuccessful) {
                    Toast.makeText(this@HistoryActivity, "Monthly report requested! Check your email.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@HistoryActivity, "Failed to send report. Check backend settings.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnDownloadReport.isEnabled = true
                binding.btnDownloadReport.text = getString(R.string.btn_download_report)
            }
        }
    }

    private fun fetchSummary(userId: String) {
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.getDriverSummary(userId)
                if (response.isSuccessful && response.body() != null) {
                    val summary = response.body()!!
                    binding.tvTodayWarning.text = "Warnings: ${summary.today.warning}"
                    binding.tvTodayCritical.text = "Critical: ${summary.today.critical}"
                    binding.tvWeeklyWarning.text = "Warnings: ${summary.weekly.warning}"
                    binding.tvWeeklyCritical.text = "Critical: ${summary.weekly.critical}"
                }
            } catch (e: Exception) {
                android.util.Log.e("HistoryActivity", "Failed to fetch summary: ${e.message}")
            }
        }
    }

    private fun fetchHistory() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null)

        if (userId == null) {
            Toast.makeText(this, "User ID not found. Please log in again.", Toast.LENGTH_SHORT).show()
            return
        }

        fetchSummary(userId)

        // Show progress bar, hide recycler view
        binding.progressBar.visibility = View.VISIBLE
        binding.rvFatigueEvents.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                // Non-blocking Coroutine network call
                val response = RetrofitClient.instance.getDriverHistory(userId)
                
                if (response.isSuccessful && response.body() != null) {
                    val logs = response.body()!!
                    adapter.submitList(logs)
                    
                    // Dynamic AI Insight Logic
                    val severeEvents = logs.count { it.stage >= 2 }
                    val avgPerclos = if (logs.isNotEmpty()) logs.map { it.perclos }.average() else 0.0
                    
                    if (severeEvents > 0) {
                        binding.tvAiInsightText.text = "You had $severeEvents severe fatigue events. Average PERCLOS: ${String.format("%.1f%%", avgPerclos)}."
                    } else {
                        binding.tvAiInsightText.text = "Great job! No severe fatigue detected recently. Average PERCLOS: ${String.format("%.1f%%", avgPerclos)}."
                    }
                    
                    // Hide progress bar, show list
                    binding.progressBar.visibility = View.GONE
                    binding.rvFatigueEvents.visibility = View.VISIBLE
                    
                    if (logs.isEmpty()) {
                        Toast.makeText(this@HistoryActivity, "No history found.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@HistoryActivity, "Failed to load history: ${response.message()}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this@HistoryActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
