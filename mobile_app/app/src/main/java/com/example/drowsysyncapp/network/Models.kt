package com.example.drowsysyncapp.network

import com.google.gson.annotations.SerializedName

data class UserRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("vehicleId") val vehicleId: String,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("licenseSerial") val licenseSerial: String? = null,
    @SerializedName("emergencyName") val emergencyName: String? = null,
    @SerializedName("emergencyPhone") val emergencyPhone: String? = null
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
    @SerializedName("phone") val phone: String?,
    @SerializedName("vehicleId") val vehicleId: String?,
    @SerializedName("isEmailVerified") val isEmailVerified: Boolean,
    @SerializedName("isCurrentlyDriving") val isCurrentlyDriving: Boolean?,
    @SerializedName("licenseSerial") val licenseSerial: String?,
    @SerializedName("emergencyName") val emergencyName: String?,
    @SerializedName("emergencyPhone") val emergencyPhone: String?
)

data class ProfileUpdateRequest(
    @SerializedName("email") val email: String,
    @SerializedName("phone") val phone: String
)

data class ProfileUpdateResponse(
    @SerializedName("message") val message: String,
    @SerializedName("user") val user: UserDto
)

data class VerifyRequest(
    @SerializedName("email") val email: String,
    @SerializedName("code") val code: String
)

data class ReleaseVehicleRequest(
    @SerializedName("userId") val userId: String,
    @SerializedName("password") val password: String
)

data class FatigueLogResponse(
    @SerializedName("_id") val id: String,
    @SerializedName("userId") val userId: String?,
    @SerializedName("stage") val stage: Int,
    @SerializedName("status") val status: String,
    @SerializedName("perclos") val perclos: Double,
    @SerializedName("ear") val ear: Double,
    @SerializedName("mar") val mar: Double,
    @SerializedName("recent_yawn_count") val recentYawnCount: Int,
    @SerializedName("microsleep_active") val microsleepActive: Boolean,
    @SerializedName("stage3_latched") val stage3Latched: Boolean,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("createdAt") val createdAt: String?
)

data class ReportResponse(
    @SerializedName("message") val message: String
)

data class GuestModeRequest(
    @SerializedName("isGuestModeActive") val isGuestModeActive: Boolean
)

data class SummaryResponse(
    @SerializedName("today") val today: PeriodSummary,
    @SerializedName("weekly") val weekly: PeriodSummary
)

data class PeriodSummary(
    @SerializedName("warning") val warning: Int,
    @SerializedName("critical") val critical: Int,
    @SerializedName("total") val total: Int
)
