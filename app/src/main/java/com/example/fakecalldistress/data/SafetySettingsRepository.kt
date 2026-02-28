package com.example.fakecalldistress.data

import android.content.Context
import android.content.SharedPreferences

class SafetySettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("safety_settings", Context.MODE_PRIVATE)

    fun isNightModeEnabled(): Boolean = prefs.getBoolean("night_mode", false)
    fun setNightModeEnabled(enabled: Boolean) = prefs.edit().putBoolean("night_mode", enabled).apply()

    fun isSilentSosEnabled(): Boolean = prefs.getBoolean("silent_sos", false)
    fun setSilentSosEnabled(enabled: Boolean) = prefs.edit().putBoolean("silent_sos", enabled).apply()

    fun isInactivityCheckEnabled(): Boolean = prefs.getBoolean("inactivity_check", false)
    fun setInactivityCheckEnabled(enabled: Boolean) = prefs.edit().putBoolean("inactivity_check", enabled).apply()

    fun isSafeArrivalEnabled(): Boolean = prefs.getBoolean("safe_arrival", false)
    fun setSafeArrivalEnabled(enabled: Boolean) = prefs.edit().putBoolean("safe_arrival", enabled).apply()

    // Advanced Safety Flags
    fun isMultiChannelSosEnabled(): Boolean = prefs.getBoolean("multi_channel_sos", false)
    fun setMultiChannelSosEnabled(enabled: Boolean) = prefs.edit().putBoolean("multi_channel_sos", enabled).apply()

    fun isAutoCallbackEnabled(): Boolean = prefs.getBoolean("auto_callback", false)
    fun setAutoCallbackEnabled(enabled: Boolean) = prefs.edit().putBoolean("auto_callback", enabled).apply()

    fun isFallDetectionEnabled(): Boolean = prefs.getBoolean("fall_detection", false)
    fun setFallDetectionEnabled(enabled: Boolean) = prefs.edit().putBoolean("fall_detection", enabled).apply()

    fun isAutoNightModeEnabled(): Boolean = prefs.getBoolean("auto_night_mode", false)
    fun setAutoNightModeEnabled(enabled: Boolean) = prefs.edit().putBoolean("auto_night_mode", enabled).apply()

    fun isVoiceAuthEnabled(): Boolean = prefs.getBoolean("voice_auth_enabled", false)
    fun setVoiceAuthEnabled(enabled: Boolean) = prefs.edit().putBoolean("voice_auth_enabled", enabled).apply()

    fun getSecretSafeWord(): String = prefs.getString("secret_safe_word", "STOP") ?: "STOP"
    fun setSecretSafeWord(word: String) = prefs.edit().putString("secret_safe_word", word).apply()

    fun getSosTriggerWord(): String = prefs.getString("sos_trigger_word", "") ?: ""
    fun setSosTriggerWord(word: String) = prefs.edit().putString("sos_trigger_word", word).apply()

    // Geofencing
    fun saveUnsafeZone(lat: Double, lon: Double) {
        prefs.edit().putFloat("unsafe_lat", lat.toFloat()).putFloat("unsafe_lon", lon.toFloat()).apply()
    }
    fun getUnsafeZoneLat(): Double = prefs.getFloat("unsafe_lat", 0.0f).toDouble()
    fun getUnsafeZoneLon(): Double = prefs.getFloat("unsafe_lon", 0.0f).toDouble()
    
    fun clearUnsafeZone() {
        prefs.edit().remove("unsafe_lat").remove("unsafe_lon").apply()
    }

    fun getSafeRoutes(): Set<String> = prefs.getStringSet("safe_routes", emptySet()) ?: emptySet()
    fun saveSafeRoute(routeJson: String) {
        val existing = prefs.getStringSet("safe_routes", mutableSetOf()) ?: mutableSetOf()
        existing.add(routeJson)
        prefs.edit().putStringSet("safe_routes", existing).apply()
    }
}
