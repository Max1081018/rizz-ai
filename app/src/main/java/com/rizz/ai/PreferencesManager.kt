package com.rizz.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class PreferencesManager(context: Context) {

    companion object {
        private const val TAG = "PreferencesManager"
        private const val PREFS_NAME = "rizz_ai_secure_prefs"
        private const val FALLBACK_PREFS_NAME = "rizz_ai_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_VIBE_MODE = "vibe_mode"

        const val VIBE_SMOOTH = "smooth"
        const val VIBE_TEASE = "tease"
        const val VIBE_CHILL = "chill"
        const val VIBE_BOLD = "bold"
    }

    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to standard SharedPreferences", e)
        context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()

    var vibeMode: String
        get() = prefs.getString(KEY_VIBE_MODE, VIBE_SMOOTH) ?: VIBE_SMOOTH
        set(value) = prefs.edit().putString(KEY_VIBE_MODE, value).apply()

    fun isConfigured(): Boolean {
        return geminiApiKey.isNotBlank()
    }
}
