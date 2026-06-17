package com.example.drowsysyncapp.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton Retrofit client.
 *
 * ⚠️ IMPORTANT: Replace BASE_URL with your PC's actual LAN IP address.
 *     - Run `ipconfig` on your Windows PC and look for "IPv4 Address" under your Wi-Fi adapter.
 *     - Example: "http://192.168.1.100:3000/"
 *     - Do NOT use "localhost" or "127.0.0.1" — those resolve to the Android device itself.
 */
object RetrofitClient {

    // TODO: Change to your actual laptop IPv4 address (e.g. http://192.168.x.x:3000/)
    private const val BASE_URL = "https://drowsysync.onrender.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val instance: DrowsySyncApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DrowsySyncApiService::class.java)
    }
}
