package com.example.drowsysyncapp

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.drowsysyncapp.databinding.ItemFatigueEventBinding

data class FatigueEvent(
    val id: String,
    val type: EventType,
    val date: String,
    val time: String,
    val location: String? = null
) {
    enum class EventType { MICROSLEEP, SYMPTOM }
}

class FatigueEventAdapter : RecyclerView.Adapter<FatigueEventAdapter.ViewHolder>() {

    private val events = mutableListOf<FatigueEvent>()

    fun submitList(newEvents: List<FatigueEvent>) {
        events.clear()
        events.addAll(newEvents)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFatigueEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(events[position])
    }

    override fun getItemCount() = events.size

    class ViewHolder(private val b: ItemFatigueEventBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(event: FatigueEvent) {
            val ctx = b.root.context
            val isMicrosleep = event.type == FatigueEvent.EventType.MICROSLEEP

            if (isMicrosleep) {
                b.ivEventIcon.setImageResource(R.drawable.ic_alert_triangle)
                b.ivEventIcon.imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.emergency))
                b.iconBadge.backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.emergency))
                b.iconBadge.alpha = 0.15f

                b.tvEventTitle.text = ctx.getString(R.string.microsleep_alert_label)
                b.tvEventBadge.text = ctx.getString(R.string.badge_critical)
                b.tvEventBadge.setTextColor(ctx.getColor(R.color.emergency))
                b.tvEventBadge.backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.emergency))
                b.tvEventBadge.alpha = 0.1f
            } else {
                b.ivEventIcon.setImageResource(R.drawable.ic_coffee)
                b.ivEventIcon.imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.warning))
                b.iconBadge.backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.warning))
                b.iconBadge.alpha = 0.15f

                b.tvEventTitle.text = ctx.getString(R.string.symptom_warning_label)
                b.tvEventBadge.text = ctx.getString(R.string.badge_warning)
                b.tvEventBadge.setTextColor(ctx.getColor(R.color.warning))
                b.tvEventBadge.backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.warning))
                b.tvEventBadge.alpha = 0.1f
            }

            // Reset icon badge alpha for tinted background
            b.iconBadge.alpha = 1f
            b.tvEventBadge.alpha = 1f

            val locationStr = if (event.location != null) " • ${event.location}" else ""
            b.tvEventMeta.text = "${event.date} at ${event.time}$locationStr"
        }
    }
}
