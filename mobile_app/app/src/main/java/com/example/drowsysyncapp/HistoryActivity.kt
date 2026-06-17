package com.example.drowsysyncapp

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.drowsysyncapp.databinding.ActivityHistoryBinding
import com.example.drowsysyncapp.network.RetrofitClient
import kotlinx.coroutines.launch

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
            
            // Loading State UI
            binding.btnDownloadReport.isEnabled = false
            binding.btnDownloadReport.text = "Generating Report..."
            
            lifecycleScope.launch {
                try {
                    val response = RetrofitClient.instance.generateReport(userId, 7) // Last 7 days
                    if (response.isSuccessful) {
                        Toast.makeText(this@HistoryActivity, "Report emailed successfully!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@HistoryActivity, "Failed to send report.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@HistoryActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    binding.btnDownloadReport.isEnabled = true
                    binding.btnDownloadReport.text = getString(R.string.btn_download_report)
                }
            }
        }

        fetchHistory()
    }

    private fun fetchHistory() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", null)

        if (userId == null) {
            Toast.makeText(this, "User ID not found. Please log in again.", Toast.LENGTH_SHORT).show()
            return
        }

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
}
