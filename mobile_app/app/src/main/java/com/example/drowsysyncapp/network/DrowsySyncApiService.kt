package com.example.drowsysyncapp.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface DrowsySyncApiService {

    @POST("api/auth/register")
    suspend fun registerUser(@Body request: UserRequest): Response<AuthResponse>

    @POST("api/auth/verify")
    suspend fun verifyEmail(@Body request: VerifyRequest): Response<AuthResponse>

    @POST("api/auth/login")
    suspend fun loginUser(@Body request: LoginRequest): Response<AuthResponse>

    @PUT("api/users/vehicle/{userId}")
    suspend fun updateVehicleDetails(
        @Path("userId") userId: String,
        @Body request: VehicleUpdateRequest
    ): retrofit2.Response<okhttp3.ResponseBody>

    @GET("api/logs/{userId}")
    suspend fun getDriverHistory(
        @Path("userId") userId: String
    ): Response<List<FatigueLogResponse>>

    @GET("api/logs/report/{userId}")
    suspend fun generateReport(
        @Path("userId") userId: String,
        @retrofit2.http.Query("days") days: Int? = null
    ): Response<ReportResponse>

    @GET("api/logs/latest/vehicle/{vehicleId}")
    suspend fun getLatestVehicleLog(
        @Path("vehicleId") vehicleId: String
    ): Response<FatigueLogResponse>

    @PUT("api/users/guest-mode/{userId}")
    suspend fun toggleGuestMode(
        @Path("userId") userId: String,
        @Body request: GuestModeRequest
    ): Response<okhttp3.ResponseBody>

    @PUT("api/users/claim-vehicle/{userId}")
    suspend fun claimVehicle(
        @Path("userId") userId: String
    ): Response<okhttp3.ResponseBody>

    @PUT("api/users/profile/{userId}")
    suspend fun updateProfile(
        @Path("userId") userId: String,
        @Body request: ProfileUpdateRequest
    ): Response<ProfileUpdateResponse>
    
    // Kept for backward compatibility with the existing background service
    @GET("api/events")
    suspend fun getLatestEvents(): Response<List<FatigueLogResponse>>
}
