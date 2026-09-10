package com.example.ai

import com.example.reply.ReplyStyle
import com.example.reply.ReplySuggestion

/**
 * Turns raw provider text into the three reply suggestions the UI expects.
 *
 * Shared by every provider so reply parsing is implemented exactly once.
 *
 * Tone is deliberately preserved: this parser strips mechanical artefacts (preambles, list
 * markers, surrounding quotes) and never rewrites, sanitises or "flattens" the wording, so a
 * romantic, flirty, playful or Hinglish reply comes back exactly as the model wrote it.
 */
object ReplySuggestionParser {

    private const val DELIMITER = "|||"

    private val PREAMBLE_PATTERNS = listOf(
        Regex("^(here are|here're|here is).*?:", RegexOption.IGNORE_CASE),
        Regex("^sure,?\\s*.*?:", RegexOption.IGNORE_CASE),
        Regex("^(certainly|of course|absolutely),?\\s*.*?:", RegexOption.IGNORE_CASE)
    )

    private val LIST_MARKER = Regex("^(\\d+[.)\\]]|[-*•])\\s*")
    private val JSON_ARRAY = Regex("^\\s*\\[.*]\\s*$", RegexOption.DOT_MATCHES_ALL)

    /** Styles assigned to the three suggestions, in order. */
    private val STYLES = listOf(
        ReplyStyle.NATURAL_SAFE,
        ReplyStyle.CASUAL_FRIENDLY,
        ReplyStyle.PLAYFUL_INTERESTING
    )

    /**
     * Returns up to three suggestions, or an empty list when nothing usable was found.
     *
     * Strategy:
     *  1. structured JSON array of strings, if the model used one;
     *  2. the `|||` delimiter the prompts ask for;
     *  3. newline-separated lines as a last resort.
     */
    fun parse(rawOutput: String): List<ReplySuggestion> {
        if (rawOutput.isBlank()) return emptyList()

        val trimmed = rawOutput.trim()
        val fromJson = runCatching { parseJsonArray(trimmed) }.getOrNull()
        if (!fromJson.isNullOrEmpty()) return toSuggestions(fromJson)

        val cleaned = stripPreamble(trimmed)
        val delimited = cleaned.split(DELIMITER)
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }

        val lines = if (delimited.size >= 2) {
            delimited
        } else {
            cleaned.lineSequence()
                .map { cleanLine(it) }
                .filter { it.isNotBlank() && it.length > 2 }
                .toList()
        }

        return toSuggestions(lines)
    }

    /** Accepts ["a","b","c"] and, defensively, [{"text":"a"}]. Never surfaces raw JSON to the user. */
    internal fun parseJsonArray(raw: String): List<String>? {
        if (!JSON_ARRAY.matches(raw)) return null
        val array = org.json.JSONArray(raw)
        if (array.length() == 0) return null

        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            when (val element = array.opt(i)) {
                is String -> out.add(element)
                is org.json.JSONObject -> element.optString("text")
                    .takeIf { it.isNotBlank() }
                    ?.let { out.add(it) }
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun stripPreamble(text: String): String {
        var out = text
        // Only the first matching preamble is removed, and only at the very start of the output.
        for (pattern in PREAMBLE_PATTERNS) {
            val match = pattern.find(out) ?: continue
            if (match.range.first == 0) {
                out = out.removeRange(match.range).trim()
                break
            }
        }
        return out
    }

    private fun cleanLine(line: String): String = line
        .replace(LIST_MARKER, "")
        .trim()
        .trim('"', '\'', '`', '“', '”', '‘', '’')
        .trim()

    private fun toSuggestions(lines: List<String>): List<ReplySuggestion> =
        lines.take(STYLES.size)
            .mapIndexedNotNull { index, text ->
                if (text.isBlank()) null
                else ReplySuggestion(
                    index = index + 1,
                    style = STYLES.getOrElse(index) { ReplyStyle.NATURAL_SAFE },
                    text = text
                )
            }
}
