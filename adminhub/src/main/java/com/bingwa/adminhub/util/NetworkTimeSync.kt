package com.bingwa.adminhub.util

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

object NetworkTimeSync {
    private const val TAG = "NetworkTimeSync"
    private const val TIME_SERVER = "https://worldtimeapi.org/api/timezone/Africa/Nairobi"

    fun getNetworkTime(): Long {
        return try {
            val url = URL(TIME_SERVER)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val unixtimePattern = """"unixtime":\s*(\d+)""".toRegex()
                val matchResult = unixtimePattern.find(response)
                val unixtime = matchResult?.groupValues?.get(1)?.toLongOrNull()
                unixtime?.times(1000) ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get network time, using system time", e)
            System.currentTimeMillis()
        }
    }

    fun getCurrentTimeFormatted(pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(getNetworkTime()))
    }

    fun generateTimeBasedCode(prefix: String = "ACT"): String {
        val time = getNetworkTime()
        val minutes = (time / 60000) % 100000
        return "$prefix$minutes"
    }
}
