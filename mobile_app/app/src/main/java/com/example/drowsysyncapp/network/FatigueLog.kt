package com.example.drowsysyncapp.network

import com.google.gson.annotations.SerializedName

/**
 * Mirrors the FatigueLog MongoDB schema in cloud_backend/models/FatigueLog.js.
 * Gson uses @SerializedName to map JSON snake_case fields to Kotlin camelCase.
 */
data class FatigueLog(
    @SerializedName("stage")           val stage: Int,
    @SerializedName("status")          val status: String,
    @SerializedName("perclos")         val perclos: Double,
    @SerializedName("ear")             val ear: Double,
    @SerializedName("mar")             val mar: Double,
    @SerializedName("recent_yawn_count") val recentYawnCount: Int,
    @SerializedName("microsleep_active") val microsleepActive: Boolean,
    @SerializedName("stage3_latched")  val stage3Latched: Boolean,
    @SerializedName("timestamp")       val timestamp: Double,
    @SerializedName("createdAt")       val createdAt: String? = null,
    @SerializedName("_id")             val id: String? = null
)
