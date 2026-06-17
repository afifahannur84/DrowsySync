package com.example.drowsysyncapp.network

import com.google.gson.annotations.SerializedName

data class UserRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("vehicleId") val vehicleId: String
)

data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("vehicleId") val vehicleId: String
)

data class AuthResponse(
    @SerializedName("message") val message: String,
    @SerializedName("verificationCode") val verificationCode: String? = null,
    @SerializedName("user") val user: UserDto? = null
)

data class UserDto(
    @SerializedName("_id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("email") val email: String,
    @SerializedName("isEmailVerified") val isEmailVerified: Boolean,
    @SerializedName("deviceOwnerName") val deviceOwnerName: String
)

data class VerifyRequest(
    @SerializedName("email") val email: String,
    @SerializedName("code") val code: String
)

data class VehicleUpdateRequest(
    @SerializedName("carModel") val carModel: String,
    @SerializedName("carPlate") val carPlate: String
)

data class FatigueLogResponse(
    @SerializedName("_id") val id: String,
    @SerializedName("userId") val userId: String,
    @SerializedName("stage") val stage: Int,
    @SerializedName("status") val status: String,
    @SerializedName("perclos") val perclos: Double,
    @SerializedName("ear") val ear: Double,
    @SerializedName("mar") val mar: Double,
    @SerializedName("recent_yawn_count") val recentYawnCount: Int,
    @SerializedName("microsleep_active") val microsleepActive: Boolean,
    @SerializedName("stage3_latched") val stage3Latched: Boolean,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("createdAt") val createdAt: String
)

data class ReportResponse(
    @SerializedName("message") val message: String
)

data class GuestModeRequest(
    @SerializedName("isGuestModeActive") val isGuestModeActive: Boolean
)
