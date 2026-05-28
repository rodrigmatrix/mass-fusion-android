package com.limelight.utils

import android.content.Context
import androidx.preference.PreferenceManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PerformanceDataTracker {

    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    fun savePerformanceStatistics(
        context: Context,
        device: String,
        osVersion: String,
        appVersion: String,
        codec: String,
        decodingTimeMs: String,
        stats: String,
        bitrateMbps: String,
        resolution: String,
        frameRateFps: String,
        average: String,
        framePacing: String,
        dateTime: String
    ) {
        executorService.execute {
            saveToPreferences(
                context, device, osVersion, appVersion, codec,
                decodingTimeMs, stats, bitrateMbps, resolution, frameRateFps, average, framePacing, dateTime
            )
        }
    }

    private fun saveToPreferences(
        context: Context, device: String, osVersion: String, appVersion: String, codec: String,
        decodingTimeMs: String, stats: String, bitrateMbps: String, resolution: String,
        frameRateFps: String, average: String, framePacing: String, dateTime: String
    ) {
        try {
            val newEntry = JSONObject()
            newEntry.put(FIELD_DEVICE, device)
            newEntry.put(FIELD_OS_VERSION, osVersion)
            newEntry.put(FIELD_APP_VERSION, appVersion)
            newEntry.put(FIELD_CODEC, codec)
            newEntry.put(FIELD_DECODING_TIME, decodingTimeMs)
            newEntry.put(FIELD_STATS_LOG, stats)
            newEntry.put(FIELD_BITRATE, bitrateMbps)
            newEntry.put(FIELD_RESOLUTION, resolution)
            newEntry.put(FIELD_FRAME_RATE, frameRateFps)
            newEntry.put(FIELD_AVERAGE, average)
            newEntry.put(FIELD_FRAME_PACING, framePacing)
            newEntry.put(FIELD_DATETIME, dateTime)

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val existingLogsRaw = prefs.getString(PREF_KEY_LOG, "") ?: ""

            var logsArray: JSONArray
            try {
                logsArray = if (existingLogsRaw.isNotEmpty()) JSONArray(existingLogsRaw) else JSONArray()
            } catch (e: Exception) {
                logsArray = JSONArray()
                Log.w("PerformanceDataTracker", "Invalid old logs cleared.")
            }

            val newDecodingTime = parseDecodingTime(decodingTimeMs)
            var duplicateIndex = -1
            var worstDecodingTime = Float.MAX_VALUE

            for (i in 0 until logsArray.length()) {
                val entry = logsArray.getJSONObject(i)

                val isSameConfig = device == entry.optString(FIELD_DEVICE) &&
                        osVersion == entry.optString(FIELD_OS_VERSION) &&
                        appVersion == entry.optString(FIELD_APP_VERSION) &&
                        codec == entry.optString(FIELD_CODEC) &&
                        bitrateMbps == entry.optString(FIELD_BITRATE) &&
                        resolution == entry.optString(FIELD_RESOLUTION) &&
                        frameRateFps == entry.optString(FIELD_FRAME_RATE) &&
                        framePacing == entry.optString(FIELD_FRAME_PACING)

                if (isSameConfig) {
                    val existingDecodingTime = parseDecodingTime(entry.optString(FIELD_DECODING_TIME))
                    if (existingDecodingTime <= newDecodingTime) {
                        Log.d("PerformanceDataTracker", "Duplicate with equal or better decoding time. Skipping.")
                        return
                    } else {
                        duplicateIndex = i
                        worstDecodingTime = existingDecodingTime
                    }
                }
            }

            if (duplicateIndex != -1) {
                logsArray.remove(duplicateIndex)
                Log.d("PerformanceDataTracker", "Replaced older entry with decoding time: $worstDecodingTime")
            }

            logsArray.put(newEntry)
            prefs.edit().putString(PREF_KEY_LOG, logsArray.toString()).apply()
            Log.d("PerformanceDataTracker", "New performance data saved.")

        } catch (e: Exception) {
            Log.e("PerformanceDataTracker", "Failed to save to preferences: " + e.message)
        }
    }

    private fun parseDecodingTime(decodingTimeString: String?): Float {
        if (decodingTimeString == null) return Float.MAX_VALUE
        return try {
            val numericPart = decodingTimeString.replace("[^0-9.]".toRegex(), "")
            numericPart.toFloat()
        } catch (e: Exception) {
            Float.MAX_VALUE
        }
    }

    fun getLog(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(PREF_KEY_LOG, "") ?: ""
    }

    fun clearLogs(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().remove(PREF_KEY_LOG).apply()
        Log.d("PerformanceDataTracker", "All logs cleared.")
    }

    companion object {
        private const val PREF_KEY_LOG = "performance_log"
        private const val FIELD_DEVICE = "Device"
        private const val FIELD_OS_VERSION = "OS Version"
        private const val FIELD_APP_VERSION = "App Version"
        private const val FIELD_CODEC = "Codec"
        private const val FIELD_STATS_LOG = "Performance Stats Log"
        private const val FIELD_DECODING_TIME = "Decoding Time (ms)"
        private const val FIELD_BITRATE = "Bitrate (Mbps)"
        private const val FIELD_RESOLUTION = "Resolution"
        private const val FIELD_FRAME_RATE = "Frame Rate (FPS)"
        private const val FIELD_AVERAGE = "Average Latency"
        private const val FIELD_FRAME_PACING = "Frame Pacing"
        private const val FIELD_DATETIME = "Date/Time"
    }
}
