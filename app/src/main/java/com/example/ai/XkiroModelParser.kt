package com.example.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the xKiro `GET /v1/models` catalogue.
 *
 * The endpoint is PUBLIC and returns the whole active catalogue, so it is NOT account-specific:
 * a model appearing here does not guarantee the user's key may call it. [ModelCandidate.accessTier]
 * is only a first-pass eligibility hint; a 403 from a real completion request is the final truth.
 *
 * Model ids keep their vendor prefix ("openai/gpt-5.6-sol"). The prefix is never stripped,
 * because xKiro resolves models by their full vendor-prefixed id.
 */
object XkiroModelParser {

    private const val TAG = "XkiroModelParser"

    fun parse(body: String): List<ModelCandidate> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        val array = when {
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                when {
                    root.has("data") -> root.optJSONArray("data") ?: JSONArray()
                    root.has("models") -> root.optJSONArray("models") ?: JSONArray()
                    else -> JSONArray()
                }
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
            ?: obj.optString("name").takeIf { it.isNotBlank() }
            ?: obj.optString("model").takeIf { it.isNotBlank() }
            ?: return null

        val capabilities = parseCapabilities(obj)
        val pricing = obj.optJSONObject("pricing")

        return ModelCandidate(
            provider = ProviderId.XKIRO,
            // Vendor prefix preserved verbatim.
            modelId = id.trim(),
            displayName = obj.optString("display_name").takeIf { it.isNotBlank() }
                ?: obj.optString("displayName").takeIf { it.isNotBlank() },
            ownedBy = obj.optString("owned_by").takeIf { it.isNotBlank() }
                ?: obj.optString("ownedBy").takeIf { it.isNotBlank() },
            accessTier = obj.optString("access_tier").takeIf { it.isNotBlank() }
                ?: obj.optString("accessTier").takeIf { it.isNotBlank() }
                ?: obj.optString("tier").takeIf { it.isNotBlank() },
            contextLength = obj.readInt("context_length", "contextLength", "context_window"),
            maxOutputTokens = obj.readInt("max_output_tokens", "maxOutputTokens", "max_completion_tokens", "max_tokens"),
            capabilities = capabilities,
            reasoningEfforts = parseStringList(obj, "reasoning_efforts", "reasoningEfforts"),
            inputPricePerToken = pricing.readPrice("input", "prompt", "input_per_token"),
            outputPricePerToken = pricing.readPrice("output", "completion", "output_per_token")
        )
    }

    /**
     * `capabilities` is tolerated in either shape:
     *  - array: ["chat", "reasoning", "tools"]
     *  - object: {"chat": true, "reasoning": true, "vision": false}
     */
    internal fun parseCapabilities(obj: JSONObject): Set<String> {
        val result = LinkedHashSet<String>()

        obj.optJSONArray("capabilities")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { result.add(it.lowercase()) }
            }
        }
        obj.optJSONObject("capabilities")?.let { caps ->
            caps.keys().forEach { key ->
                val value = caps.opt(key)
                val enabled = when (value) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    else -> true
                }
                if (enabled) result.add(key.lowercase())
            }
        }
        return result
    }

    private fun parseStringList(obj: JSONObject, vararg keys: String): List<String> {
        for (key in keys) {
            obj.optJSONArray(key)?.let { arr ->
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { list.add(it) }
                }
                if (list.isNotEmpty()) return list
            }
        }
        return emptyList()
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

    private fun JSONObject?.readPrice(vararg keys: String): Double? {
        val obj = this ?: return null
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val raw = obj.opt(key)
            when (raw) {
                is Number -> return raw.toDouble()
                is String -> raw.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }
}
