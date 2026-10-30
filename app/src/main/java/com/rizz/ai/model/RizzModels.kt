package com.rizz.ai.model

import com.google.gson.annotations.SerializedName

/**
 * Psychological analysis and reply suggestions returned by the AI Wingman.
 */
data class RizzResult(
    @SerializedName("psychologyInsight")
    val psychologyInsight: String = "",

    @SerializedName("detectedVibe")
    val detectedVibe: String = "",

    @SerializedName("conversationalFrame")
    val conversationalFrame: String = "",

    @SerializedName("suggestions")
    val suggestions: List<RizzSuggestion> = emptyList()
)

data class RizzSuggestion(
    @SerializedName("category")
    val category: String, // "SMOOTH_FLIRTY", "PLAYFUL_TEASE", "HIGH_EQ_CLOSER"

    @SerializedName("tag")
    val tag: String, // Display label e.g. "🔥 SMOOTH / FLIRTY"

    @SerializedName("text")
    val text: String
)

// Gemini Direct REST API Models
data class GeminiRequest(
    @SerializedName("contents")
    val contents: List<GeminiContent>,
    @SerializedName("generationConfig")
    val generationConfig: GeminiGenerationConfig? = null
)

data class GeminiGenerationConfig(
    @SerializedName("temperature")
    val temperature: Float = 0.7f,
    @SerializedName("topP")
    val topP: Float = 0.95f,
    @SerializedName("responseMimeType")
    val responseMimeType: String? = "application/json"
)

data class GeminiContent(
    @SerializedName("parts")
    val parts: List<GeminiPart>,
    @SerializedName("role")
    val role: String = "user"
)

data class GeminiPart(
    @SerializedName("text")
    val text: String? = null,
    @SerializedName("inline_data")
    val inlineData: InlineData? = null
)

data class InlineData(
    @SerializedName("mime_type")
    val mimeType: String = "image/jpeg",
    @SerializedName("data")
    val data: String // Base64 encoded
)

data class GeminiResponse(
    @SerializedName("candidates")
    val candidates: List<GeminiCandidate>?
)

data class GeminiCandidate(
    @SerializedName("content")
    val content: GeminiContentResponse?
)

data class GeminiContentResponse(
    @SerializedName("parts")
    val parts: List<GeminiTextResponse>?
)

data class GeminiTextResponse(
    @SerializedName("text")
    val text: String?
)
