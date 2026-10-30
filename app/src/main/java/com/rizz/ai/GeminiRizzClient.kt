package com.rizz.ai

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.rizz.ai.model.GeminiContent
import com.rizz.ai.model.GeminiGenerationConfig
import com.rizz.ai.model.GeminiModelListResponse
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
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Modern 2026 model candidates with fallback priority
        private val FALLBACK_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-3.8-flash",
            "gemini-2.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-2.0-flash",
            "gemini-2.5-pro"
        )

        @Volatile
        var activeModel: String? = null
            private set
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Queries Google's Generative Language API dynamically to find which models
     * are active and permitted for this specific API key.
     */
    suspend fun discoverActiveModel(): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key is blank."))
        }

        try {
            val listUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val request = Request.Builder()
                .url(listUrl)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (!response.isSuccessful || body.isNullOrBlank()) {
                val code = response.code
                Log.e(TAG, "Failed to list models (HTTP $code): $body")
                return@withContext Result.failure(
                    Exception(parseGoogleErrorMessage(code, body))
                )
            }

            val listResponse = gson.fromJson(body, GeminiModelListResponse::class.java)
            val models = listResponse.models?.filter { model ->
                model.supportedGenerationMethods?.contains("generateContent") == true
            }?.map { it.name.removePrefix("models/") } ?: emptyList()

            if (models.isEmpty()) {
                Log.w(TAG, "No models with generateContent found for key. Using fallback.")
                return@withContext Result.failure(Exception("No models with generateContent permission found for this key."))
            }

            // Prioritize Flash models for speed and vision multimodal capability
            val chosen = models.firstOrNull { it.contains("flash", ignoreCase = true) }
                ?: models.firstOrNull { it.contains("gemini", ignoreCase = true) }
                ?: models.first()

            activeModel = chosen
            Log.i(TAG, "Discovered active model for key: $chosen (available: $models)")
            Result.success(chosen)
        } catch (e: Exception) {
            Log.w(TAG, "Exception during model discovery", e)
            Result.failure(e)
        }
    }

    suspend fun analyzeChatScreen(bitmap: Bitmap): Result<RizzResult> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Gemini API key is not set. Please configure it in the app."))
            }

            // 1. If we don't have an active model yet, attempt dynamic discovery
            if (activeModel == null) {
                val discovery = discoverActiveModel()
                if (discovery.isSuccess) {
                    activeModel = discovery.getOrNull()
                }
            }

            val base64Image = encodeBitmapToBase64(bitmap)
            val prompt = buildAutonomousPsychologyPrompt()

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

            // Prepare list of models to try
            val modelsToTry = mutableListOf<String>()
            if (activeModel != null) {
                modelsToTry.add(activeModel!!)
            }
            for (fallback in FALLBACK_MODELS) {
                if (!modelsToTry.contains(fallback)) {
                    modelsToTry.add(fallback)
                }
            }

            var lastError: Exception? = null

            for (model in modelsToTry) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val httpRequest = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Content-Type", "application/json")
                    .build()

                try {
                    val response = httpClient.newCall(httpRequest).execute()
                    val responseBody = response.body?.string()

                    if (response.isSuccessful && !responseBody.isNullOrBlank()) {
                        val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
                        val rawText = geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

                        if (!rawText.isNullOrBlank()) {
                            val cleanJson = cleanJsonResponse(rawText)
                            val rizzResult = gson.fromJson(cleanJson, RizzResult::class.java)
                            activeModel = model
                            Log.d(TAG, "Successfully analyzed with model: $model")
                            return@withContext Result.success(rizzResult)
                        }
                    } else if (response.code == 404) {
                        Log.w(TAG, "Model '$model' returned 404, attempting next model...")
                        lastError = Exception("Model '$model' not found (404)")
                        continue
                    } else {
                        val errorDetail = parseGoogleErrorMessage(response.code, responseBody)
                        Log.e(TAG, "Model '$model' failed (${response.code}): $errorDetail")
                        lastError = Exception(errorDetail)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error connecting to model '$model'", e)
                    lastError = e
                }
            }

            Result.failure(lastError ?: Exception("Unable to get response from Gemini API."))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze chat screen", e)
            Result.failure(e)
        }
    }

    private fun parseGoogleErrorMessage(code: Int, body: String?): String {
        if (body.isNullOrBlank()) return "Google API error (HTTP $code)"
        return try {
            val json = gson.fromJson(body, com.google.gson.JsonObject::class.java)
            val errorObj = json.getAsJsonObject("error")
            val message = errorObj?.get("message")?.asString
            if (!message.isNullOrBlank()) {
                "Google API ($code): $message"
            } else {
                "Google API ($code): $body"
            }
        } catch (e: Exception) {
            "Google API ($code): $body"
        }
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
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

    private fun buildAutonomousPsychologyPrompt(): String {
        return """
You are the world's most elite dating coach, behavioral psychologist, and real-time texting wingman.
Autonomously diagnose the girl's psychology, personality archetype, mood, and hidden subtext from the provided chat screenshot, and craft replies that give the user maximum charisma and frame control.

STEP 1: AUTONOMOUS MOOD & PSYCHOLOGICAL DIAGNOSIS
Examine the incoming messages (text, emojis, punctuation, latency/style, conversational investment):
1. Detect Her Personality Archetype:
   - e.g. "Feisty Challenger / Banterer", "High-Value / Guarded", "Dry Sarcastic / Skeptical", "Warm & Curious / Receptive", "Low Investment / Distant", "Bored by Small Talk"
2. Detect Her Immediate Mood & Hidden Subtext:
   - What is she ACTUALLY doing? (e.g. Shit-testing his confidence, waiting for him to lead, teasing, fishing for validation, being aloof to protect herself)
3. Formulate the Counter-Tactic:
   - The exact psychological strategy needed right now (e.g. "Stay unbothered, mock-disapprove playfully, reframe yourself as the prize").

STEP 2: GENERATE 3 CALIBRATED HUMAN TEXTS
Generate 3 distinct conversational weapons, customized to HER detected mood:
- Line 1: 🔥 SMOOTH & FLIRTY — Calibrated attraction and sexual tension matched to her receptivity without being needy.
- Line 2: 😏 WITTY PLAYFUL COUNTER — Teasing banter, mock pushback, or calling her bluff with playful confidence.
- Line 3: 🎯 HIGH-EQ LEADER / CLOSER — Confident forward movement that transitions the topic effortlessly or sets up an easy low-pressure date.

TEXTING RULES:
- Sound 100% human. Match real texting habits (casual lowercase where natural, NO robotic punctuation, NO cheesy pickup artist lines).
- Match or slightly outframe her energy level.

Return STRICT JSON matching this schema:
{
  "psychologyInsight": "1-2 sentence breakdown of her hidden subtext and the tactical move.",
  "detectedVibe": "Her Personality Archetype & Mood (e.g. 'Playful Challenger • Testing your confidence')",
  "conversationalFrame": "Current dynamic (e.g. 'Tension Banter • High Interest')",
  "suggestions": [
    {
      "category": "SMOOTH_FLIRTY",
      "tag": "🔥 SMOOTH & FLIRTY",
      "text": "Charismatic flirty reply."
    },
    {
      "category": "PLAYFUL_TEASE",
      "tag": "😏 PLAYFUL COUNTER",
      "text": "Witty bantering reply."
    },
    {
      "category": "HIGH_EQ_CLOSER",
      "tag": "🎯 SMOOTH CLOSER",
      "text": "High-EQ conversational lead or date close."
    }
  ]
}
""".trimIndent()
    }
}
