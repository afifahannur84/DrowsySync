package com.example.drowsysyncapp

import android.content.Context
import android.content.Intent
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
            addItemDecoration(
                DividerItemDecoration(
                    this@HistoryActivity,
                    DividerItemDecoration.VERTICAL
                )
            )
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
            val years = arrayOf(
                currentYear.toString(),
                (currentYear - 1).toString(),
                (currentYear - 2).toString()
            )

            spinnerMonth.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, months)
            spinnerMonth.setSelection(currentMonth)

            spinnerYear.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, years)
            spinnerYear.setSelection(0)

            MaterialAlertDialogBuilder(this)
                .setTitle("Select Report Period")
                .setView(dialogView)
                .setPositiveButton("Local Download") { dialog, _ ->
                    val selectedMonth = spinnerMonth.selectedItemPosition + 1 // 1-indexed
                    val selectedYear = years[spinnerYear.selectedItemPosition].toInt()

                    dialog.dismiss()
                    triggerReportDownload(userId, selectedYear, selectedMonth)
                }
                .setNeutralButton("Email PDF") { dialog, _ ->
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

    private fun triggerReportDownload(userId: String, year: Int, month: Int) {
        binding.btnDownloadReport.isEnabled = false
        binding.btnDownloadReport.text = "Downloading..."

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.downloadReport(userId, year, month, true)
                if (response.isSuccessful && response.body() != null) {
                    val contentType = response.headers().get("Content-Type")
                    if (contentType == null || !contentType.contains("application/pdf")) {
                        Toast.makeText(
                            this@HistoryActivity,
                            "Failed: Invalid report format. Please make sure the latest backend code is deployed to Render.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    val months = arrayOf(
                        "January", "February", "March", "April", "May", "June",
                        "July", "August", "September", "October", "November", "December"
                    )
                    val monthName = months.getOrNull(month - 1) ?: "Month"
                    val fileName = "DrowsySync_Report_${monthName}_$year.pdf"
                    
                    val uri = savePdfToStorage(this@HistoryActivity, response.body()!!, fileName)
                    if (uri != null) {
                        Toast.makeText(this@HistoryActivity, "Report downloaded successfully!", Toast.LENGTH_SHORT).show()
                        openPdfFile(uri)
                    } else {
                        Toast.makeText(this@HistoryActivity, "Failed to save PDF locally.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(
                        this@HistoryActivity,
                        "Failed to generate report. Check backend settings.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnDownloadReport.isEnabled = true
                binding.btnDownloadReport.text = getString(R.string.btn_download_report)
            }
        }
    }

    private fun savePdfToStorage(context: Context, body: okhttp3.ResponseBody, fileName: String): android.net.Uri? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        body.byteStream().use { inputStream ->
                            val buffer = ByteArray(4096)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                outputStream.write(buffer, 0, read)
                            }
                        }
                    }
                    uri
                } else {
                    null
                }
            } else {
                val downloadDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (downloadDir != null && !downloadDir.exists()) {
                    downloadDir.mkdirs()
                }
                val file = java.io.File(downloadDir, fileName)
                var inputStream: java.io.InputStream? = null
                var outputStream: java.io.OutputStream? = null
                try {
                    val fileReader = ByteArray(4096)
                    inputStream = body.byteStream()
                    outputStream = java.io.FileOutputStream(file)
                    while (true) {
                        val read = inputStream.read(fileReader)
                        if (read == -1) {
                            break
                        }
                        outputStream.write(fileReader, 0, read)
                    }
                    outputStream.flush()
                    
                    val authority = "${context.packageName}.fileprovider"
                    androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                } finally {
                    inputStream?.close()
                    outputStream?.close()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HistoryActivity", "Error saving PDF: ${e.message}")
            null
        }
    }

    private fun openPdfFile(uri: android.net.Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            
            val chooser = Intent.createChooser(intent, "Open PDF with...")
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "No PDF viewer found. File saved in downloads.", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerReportGeneration(userId: String, year: Int, month: Int) {
        binding.btnDownloadReport.isEnabled = false
        binding.btnDownloadReport.text = "Generating Report..."

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.generateReport(userId, year, month)
                if (response.isSuccessful) {
                    Toast.makeText(
                        this@HistoryActivity,
                        "Monthly report requested! Check your email.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val errorDetail = try {
                        val errorJson = org.json.JSONObject(response.errorBody()?.string() ?: "{}")
                        errorJson.optString("error", "Failed to send report. Check backend settings.")
                    } catch (e: Exception) {
                        "Failed to send report. Check backend settings."
                    }
                    Toast.makeText(this@HistoryActivity, errorDetail, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "Error: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
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

                    if (!summary.peakMicrosleepHour.isNullOrEmpty()) {
                        binding.tvPeakMicrosleepHour.text = summary.peakMicrosleepHour
                    }
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
            Toast.makeText(this, "User ID not found. Please log in again.", Toast.LENGTH_SHORT)
                .show()
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

                    // Calculate Peak Microsleep Hour
                    val microsleepLogs = logs.filter { it.microsleepActive || it.stage == 3 }
                    if (microsleepLogs.isNotEmpty()) {
                        val hourCounts = IntArray(24)
                        val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
                        parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        
                        for (log in microsleepLogs) {
                            val dateStr = log.createdAt
                            if (dateStr != null) {
                                try {
                                    val date = parser.parse(dateStr)
                                    if (date != null) {
                                        val cal = java.util.Calendar.getInstance()
                                        cal.time = date
                                        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                                        hourCounts[hour]++
                                    }
                                } catch (e: Exception) {
                                    // Ignore
                                }
                            }
                        }
                        
                        val maxHour = hourCounts.indices.maxByOrNull { hourCounts[it] } ?: -1
                        if (maxHour != -1 && hourCounts[maxHour] > 0) {
                            val startHour = maxHour
                            val endHour = (maxHour + 1) % 24
                            val ampmStart = if (startHour >= 12) "PM" else "AM"
                            val ampmEnd = if (endHour >= 12) "PM" else "AM"
                            val displayStart = when {
                                startHour == 0 -> 12
                                startHour > 12 -> startHour - 12
                                else -> startHour
                            }
                            val displayEnd = when {
                                endHour == 0 -> 12
                                endHour > 12 -> endHour - 12
                                else -> endHour
                            }
                            binding.tvPeakMicrosleepHour.text = "$displayStart $ampmStart - $displayEnd $ampmEnd"
                        } else {
                            binding.tvPeakMicrosleepHour.text = "None detected"
                        }
                    } else {
                        binding.tvPeakMicrosleepHour.text = "None detected"
                    }

                    // 24 Hours Hourly Breakdown Calculation
                    val hourlyCounts = IntArray(24)
                    val hourlyCriticalCounts = IntArray(24)
                    val parserBreakdown = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
                    parserBreakdown.timeZone = java.util.TimeZone.getTimeZone("UTC")

                    for (log in logs) {
                        val dateStr = log.createdAt
                        if (dateStr != null) {
                            try {
                                val date = parserBreakdown.parse(dateStr)
                                if (date != null) {
                                    val cal = java.util.Calendar.getInstance()
                                    cal.time = date
                                    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                                    if (log.stage >= 1) {
                                        hourlyCounts[hour]++
                                        if (log.stage == 3 || log.microsleepActive) {
                                            hourlyCriticalCounts[hour]++
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    }

                    val breakdownSb = StringBuilder()
                    var hasBreakdownData = false
                    for (hour in 0 until 24) {
                        val total = hourlyCounts[hour]
                        if (total > 0) {
                            hasBreakdownData = true
                            val startHour = hour
                            val endHour = (hour + 1) % 24
                            val ampmStart = if (startHour >= 12) "PM" else "AM"
                            val ampmEnd = if (endHour >= 12) "PM" else "AM"
                            val displayStart = when {
                                startHour == 0 -> 12
                                startHour > 12 -> startHour - 12
                                else -> startHour
                            }
                            val displayEnd = when {
                                endHour == 0 -> 12
                                endHour > 12 -> endHour - 12
                                else -> endHour
                            }
                            val critical = hourlyCriticalCounts[hour]
                            val warning = total - critical
                            breakdownSb.append(String.format("• %02d:00 %s - %02d:00 %s : %d alerts (%d critical, %d warnings)\n",
                                displayStart, ampmStart, displayEnd, ampmEnd, total, critical, warning))
                        }
                    }

                    if (hasBreakdownData) {
                        binding.tvHourlyBreakdownText.text = breakdownSb.toString().trimEnd()
                    } else {
                        binding.tvHourlyBreakdownText.text = "No fatigue alerts recorded."
                    }

                    // Dynamic AI Insight Logic
                    val severeEvents = logs.count { it.stage >= 2 }
                    val avgPerclos =
                        if (logs.isNotEmpty()) logs.map { it.perclos }.average() else 0.0

                    if (severeEvents > 0) {
                        binding.tvAiInsightText.text =
                            "You had $severeEvents severe fatigue events. Average eyes closed duration: ${
                                String.format(
                                    "%.1f%%",
                                    avgPerclos
                                )
                            }."
                    } else {
                        binding.tvAiInsightText.text =
                            "Great job! No severe fatigue detected recently. Average eyes closed duration: ${
                                String.format(
                                    "%.1f%%",
                                    avgPerclos
                                )
                            }."
                    }

                    // Hide progress bar, show list
                    binding.progressBar.visibility = View.GONE
                    binding.rvFatigueEvents.visibility = View.VISIBLE

                    if (logs.isEmpty()) {
                        Toast.makeText(
                            this@HistoryActivity,
                            "No history found.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@HistoryActivity,
                        "Failed to load history: ${response.message()}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(
                    this@HistoryActivity,
                    "Network error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
