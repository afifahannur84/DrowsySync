package com.example.drowsysyncapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.drowsysyncapp.databinding.ActivityHistoryBinding

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val adapter = FatigueEventAdapter()

    // Same mock data as the React design
    private val mockEvents = listOf(
        FatigueEvent("1", FatigueEvent.EventType.MICROSLEEP, "2026-05-17", "14:23", "Highway 1"),
        FatigueEvent("2", FatigueEvent.EventType.SYMPTOM,    "2026-05-17", "09:15", "City Center"),
        FatigueEvent("3", FatigueEvent.EventType.SYMPTOM,    "2026-05-16", "15:42", "Industrial Area"),
        FatigueEvent("4", FatigueEvent.EventType.MICROSLEEP, "2026-05-15", "16:08", "Highway 2"),
        FatigueEvent("5", FatigueEvent.EventType.SYMPTOM,    "2026-05-14", "14:55", "Residential Zone"),
        FatigueEvent("6", FatigueEvent.EventType.SYMPTOM,    "2026-05-13", "15:20", "Downtown"),
        FatigueEvent("7", FatigueEvent.EventType.MICROSLEEP, "2026-05-10", "14:47", "Highway 1"),
    )

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
        adapter.submitList(mockEvents)

        binding.btnDownloadReport.setOnClickListener {
            android.widget.Toast.makeText(
                this,
                getString(R.string.downloading_report),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}
