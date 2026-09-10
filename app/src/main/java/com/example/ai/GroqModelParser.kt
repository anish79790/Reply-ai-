package com.example.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the Groq `GET /openai/v1/models` catalogue.
 *
 * Groq does not publish an `access_tier` field, so [ModelCandidate.accessTier] stays null and we
 * must NOT assume every listed model is free for every account. Actual organisation/project
 * permissions and rate limits are only discoverable by making the request, so the router treats a
 * 403/404/429 from Groq as authoritative and moves on.
 */
object GroqModelParser {

    fun parse(body: String): List<ModelCandidate> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        val array = when {
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            }
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> return emptyList()
        }

        val out = ArrayList<ModelCandidate>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val candidate = parseOne(obj) ?: continue
            out.add(candidate)
        }
        return out
    }

    fun parseOne(obj: JSONObject): ModelCandidate? {
        val id = obj.optString("id").takeIf { it.isNotBlank() }
            ?: obj.optString("model").takeIf { it.isNotBlank() }
            ?: return null

        // Groq marks retired models with active=false. Never treat them as candidates.
        if (obj.has("active") && !obj.optBoolean("active", true)) return null

        val ownedBy = obj.optString("owned_by").takeIf { it.isNotBlank() }
        // Groq ids are already vendor-prefixed (e.g. "openai/gpt-oss-120b"). Keep them verbatim.
        val capabilities = LinkedHashSet<String>().apply {
            add("chat")
            if (obj.optBoolean("supports_tools", false) ||
                obj.optJSONArray("supported_tools")?.length()?.let { it > 0 } == true
            ) add("tools")
            if (obj.optBoolean("supports_vision", false) ||
                obj.optBoolean("multimodal", false)
            ) add("vision")
            if (obj.optBoolean("supports_reasoning", false)) add("reasoning")
        }

        return ModelCandidate(
            provider = ProviderId.GROQ,
            modelId = id.trim(),
            displayName = obj.optString("display_name").takeIf { it.isNotBlank() }
                ?: obj.optString("displayName").takeIf { it.isNotBlank() },
            ownedBy = ownedBy,
            accessTier = null,
            contextLength = obj.readInt("context_window", "contextWindow", "context_length"),
            maxOutputTokens = obj.readInt(
                "max_completion_tokens", "maxCompletionTokens",
                "max_output_tokens", "max_tokens"
            ),
            capabilities = capabilities,
            reasoningEfforts = emptyList()
        )
    }

    private fun JSONObject.readInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val raw = opt(key)
            when (raw) {
                is Number -> raw.toInt().takeIf { it > 0 }?.let { return it }
                is String -> raw.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            }
        }
        return null
    }
}
