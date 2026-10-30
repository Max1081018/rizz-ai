package com.rizz.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.rizz.ai.model.GeminiContent
import com.rizz.ai.model.GeminiGenerationConfig
import com.rizz.ai.model.GeminiPart
import com.rizz.ai.model.GeminiRequest
import com.rizz.ai.model.GeminiResponse
import com.rizz.ai.model.InlineData
import com.rizz.ai.model.RizzResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class GeminiRizzClient(private val apiKey: String) {

    companion object {
        private const val TAG = "GeminiRizzClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun analyzeChatScreen(
        bitmap: Bitmap,
        preferredVibe: String = PreferencesManager.VIBE_SMOOTH
    ): Result<RizzResult> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Gemini API key is not set. Please configure it in the app."))
            }

            val base64Image = encodeBitmapToBase64(bitmap)
            val prompt = buildPsychologyPrompt(preferredVibe)

            val requestPayload = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        parts = listOf(
                            GeminiPart(text = prompt),
                            GeminiPart(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                        )
                    )
                ),
                generationConfig = GeminiGenerationConfig(
                    temperature = 0.75f,
                    topP = 0.95f,
                    responseMimeType = "application/json"
                )
            )

            val jsonBody = gson.toJson(requestPayload)
            val url = "$BASE_URL?key=$apiKey"

            val httpRequest = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                val errorCode = response.code
                Log.e(TAG, "API request failed with code $errorCode: $responseBody")
                return@withContext Result.failure(Exception("Gemini API error ($errorCode): ${responseBody ?: "Unknown error"}"))
            }

            val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
            val rawText = geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

            if (rawText.isNullOrBlank()) {
                return@withContext Result.failure(Exception("Empty response from AI Wingman."))
            }

            // Parse structured JSON
            val cleanJson = cleanJsonResponse(rawText)
            val rizzResult = gson.fromJson(cleanJson, RizzResult::class.java)

            Result.success(rizzResult)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze chat screen", e)
            Result.failure(e)
        }
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        // Scale down if image is too large to maintain fast upload
        val maxDimension = 1280
        val width = bitmap.width
        val height = bitmap.height
        val scaledBitmap = if (width > maxDimension || height > maxDimension) {
            val ratio = width.toFloat() / height.toFloat()
            val newWidth: Int
            val newHeight: Int
            if (width > height) {
                newWidth = maxDimension
                newHeight = (maxDimension / ratio).toInt()
            } else {
                newHeight = maxDimension
                newWidth = (maxDimension * ratio).toInt()
            }
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun cleanJsonResponse(rawText: String): String {
        var clean = rawText.trim()
        if (clean.startsWith("```json")) {
            clean = clean.removePrefix("```json")
        } else if (clean.startsWith("```")) {
            clean = clean.removePrefix("```")
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```")
        }
        return clean.trim()
    }

    private fun buildPsychologyPrompt(preferredVibe: String): String {
        val personaGuidance = when (preferredVibe) {
            PreferencesManager.VIBE_TEASE -> "Prioritize playful teasing, witty banter, dry humor, and keeping them on their toes without being rude."
            PreferencesManager.VIBE_CHILL -> "Prioritize relaxed, effortless charisma, low-pressure curiosity, emotional maturity, and warm intelligence."
            PreferencesManager.VIBE_BOLD -> "Prioritize direct, confident interest, high flirtation, bold charm, and clear intent without coming off aggressive."
            else -> "Prioritize smooth charm, romantic tension, clever wit, and confident intrigue."
        }

        return """
You are the world's highest EQ dating coach and real-time texting wingman.
Analyze the provided screenshot of a messaging or dating chat (e.g., Tinder, Hinge, Bumble, Instagram, WhatsApp, iMessage).

1. DEEP PSYCHOLOGICAL SUBTEXT ANALYSIS:
- Differentiate between the user's messages and the other person's messages (note chat bubble placement and colors).
- Determine what the other person is actually communicating under the surface (interest level, testing/shit-test, sarcasm, hesitation, playing hard to get, genuine engagement, boredom).
- Provide a concise 1-2 sentence read of their mindset and tactical advice.

2. GENERATE 3 CALIBRATED HUMAN REPLIES:
All replies MUST sound like a genuine, high-charm human texting on their phone.
CRITICAL TEXTING RULES:
- Never sound like an AI or generic pickup artist.
- Never use cheesy cliches or unnatural formal punctuation (avoid ending single casual lines with periods).
- Match modern texting capitalization and cadence (casual lowercase where appropriate).
- Preferred Persona: $personaGuidance

Return STRICT JSON matching this schema:
{
  "psychologyInsight": "1-2 sentence punchy read of the other person's intent, subtext, and how to handle it.",
  "detectedVibe": "e.g., Playful Bantering, Shit-test / teasing, Warm & engaged, Dry aloofness",
  "conversationalFrame": "e.g., High tension banter, Getting to know, Date closing",
  "suggestions": [
    {
      "category": "SMOOTH_FLIRTY",
      "tag": "🔥 SMOOTH / FLIRTY",
      "text": "Smooth, charismatic line creating attraction."
    },
    {
      "category": "PLAYFUL_TEASE",
      "tag": "😏 PLAYFUL TEASE",
      "text": "Witty banter, mock disapproval, or playful callback."
    },
    {
      "category": "HIGH_EQ_CLOSER",
      "tag": "🎯 HIGH-EQ CLOSER",
      "text": "Subtle direction toward exchanging numbers or making plans effortlessly."
    }
  ]
}
""".trimIndent()
    }
}
