package com.example.drowsysyncapp

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.drowsysyncapp.databinding.ItemFatigueEventBinding
import com.example.drowsysyncapp.network.FatigueLogResponse
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class FatigueLogAdapter : RecyclerView.Adapter<FatigueLogAdapter.ViewHolder>() {

    private val events = mutableListOf<FatigueLogResponse>()

    fun submitList(list: List<FatigueLogResponse>) {
        events.clear()
        events.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFatigueEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(events[position])
    }

    override fun getItemCount(): Int = events.size

    inner class ViewHolder(private val binding: ItemFatigueEventBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(log: FatigueLogResponse) {
            // Set the title
            binding.tvEventTitle.text = log.status

            // Format date: 2026-06-15T14:31:46.000Z to "dd/MM/yyyy HH:mm"
            try {
                // MongoDB createdAt ISO format
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                parser.timeZone = TimeZone.getTimeZone("UTC")
                val date = parser.parse(log.createdAt)
                if (date != null) {
                    val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    formatter.timeZone = TimeZone.getDefault() // Convert to local Malaysian time
                    binding.tvEventMeta.text = formatter.format(date)
                } else {
                    binding.tvEventMeta.text = log.createdAt
                }
            } catch (e: Exception) {
                // Fallback to iso string if parsing fails
                binding.tvEventMeta.text = log.createdAt
            }

            // Sub-details
            val microsleepBadge = if (log.microsleepActive) " | MICROSLEEP" else ""
            binding.tvEventBadge.text = "PERCLOS: ${log.perclos}% | EAR: ${log.ear} | YAWNS: ${log.recentYawnCount}$microsleepBadge"

            // UI styling based on stage severity
            when (log.stage) {
                0 -> { // Normal
                    binding.ivEventIcon.setImageResource(R.drawable.ic_trending_up_icon)
                    binding.ivEventIcon.setColorFilter(Color.parseColor("#4CAF50")) // Green
                    binding.tvEventTitle.setTextColor(Color.parseColor("#4CAF50"))
                }
                1 -> { // Early Fatigue
                    binding.ivEventIcon.setImageResource(R.drawable.ic_warning_notification)
                    binding.ivEventIcon.setColorFilter(Color.parseColor("#FF9800")) // Orange
                    binding.tvEventTitle.setTextColor(Color.parseColor("#FF9800"))
                }
                2 -> { // Active Drowsiness
                    binding.ivEventIcon.setImageResource(R.drawable.ic_warning_notification)
                    binding.ivEventIcon.setColorFilter(Color.parseColor("#FF5722")) // Deep Orange
                    binding.tvEventTitle.setTextColor(Color.parseColor("#FF5722"))
                }
                3 -> { // Critical Alarm
                    binding.ivEventIcon.setImageResource(R.drawable.ic_warning_notification)
                    binding.ivEventIcon.setColorFilter(Color.parseColor("#F44336")) // Red
                    binding.tvEventTitle.setTextColor(Color.parseColor("#F44336"))
                }
                else -> {
                    binding.ivEventIcon.setImageResource(R.drawable.ic_warning_notification)
                    binding.ivEventIcon.setColorFilter(Color.GRAY)
                    binding.tvEventTitle.setTextColor(Color.GRAY)
                }
            }
        }
    }
}
