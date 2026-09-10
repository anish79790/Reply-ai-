package com.example

import com.example.ai.ReplySuggestionParser
import com.example.reply.ReplyStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Three-reply parsing and tone preservation (requirements 12, 13, 27.15, 27.16). */
class ReplySuggestionParserTest {

    @Test
    fun parsesDelimitedOutput() {
        val suggestions = ReplySuggestionParser.parse(
            "Haan main free hoon|||Bas thodi der mein milte hain|||Nahi aaj thoda busy hoon"
        )
        assertEquals(3, suggestions.size)
        assertEquals("Haan main free hoon", suggestions[0].text)
        assertEquals("Bas thodi der mein milte hain", suggestions[1].text)
        assertEquals("Nahi aaj thoda busy hoon", suggestions[2].text)
    }

    @Test
    fun assignsStylesInOrder() {
        val suggestions = ReplySuggestionParser.parse("one|||two|||three")
        assertEquals(ReplyStyle.NATURAL_SAFE, suggestions[0].style)
        assertEquals(ReplyStyle.CASUAL_FRIENDLY, suggestions[1].style)
        assertEquals(ReplyStyle.PLAYFUL_INTERESTING, suggestions[2].style)
        assertEquals(listOf(1, 2, 3), suggestions.map { it.index })
    }

    @Test
    fun fallsBackToNewlineParsing() {
        val suggestions = ReplySuggestionParser.parse("First reply\nSecond reply\nThird reply")
        assertEquals(3, suggestions.size)
        assertEquals("First reply", suggestions[0].text)
    }

    @Test
    fun parsesJsonArrayWhenModelUsesStructuredOutput() {
        val suggestions = ReplySuggestionParser.parse("""["Yes, let's go","Maybe tomorrow","Can't today"]""")
        assertEquals(3, suggestions.size)
        assertEquals("Yes, let's go", suggestions[0].text)
        assertFalse("raw JSON must never be surfaced", suggestions[0].text.contains("["))
    }

    @Test
    fun stripsPreambleButKeepsTheReply() {
        val suggestions = ReplySuggestionParser.parse(
            "Here are three possible replies:\nSure thing!|||Sounds good|||Can't today"
        )
        assertEquals(3, suggestions.size)
        assertTrue(suggestions[0].text.startsWith("Sure thing"))
    }

    @Test
    fun stripsListMarkersAndQuotes() {
        val suggestions = ReplySuggestionParser.parse("1. \"Yes, definitely\"\n2) 'Maybe later'\n- Can't sorry")
        assertEquals(3, suggestions.size)
        assertEquals("Yes, definitely", suggestions[0].text)
        assertEquals("Maybe later", suggestions[1].text)
        assertEquals("Can't sorry", suggestions[2].text)
    }

    @Test
    fun emptyOrUnusableOutputYieldsNoSuggestions() {
        assertTrue(ReplySuggestionParser.parse("").isEmpty())
        assertTrue(ReplySuggestionParser.parse("   ").isEmpty())
    }

    @Test
    fun capsAtThreeSuggestions() {
        val suggestions = ReplySuggestionParser.parse("a|||b|||c|||d|||e")
        assertEquals(3, suggestions.size)
    }

    // ------------------------------------------------------------ tone preservation

    @Test
    fun preservesRomanticToneVerbatim() {
        val raw = "I miss you too, can't stop thinking about last night|||Come over, I'll make dinner|||You always know what to say"
        val suggestions = ReplySuggestionParser.parse(raw)
        assertEquals(3, suggestions.size)
        assertEquals("I miss you too, can't stop thinking about last night", suggestions[0].text)
        assertTrue(suggestions[0].text.contains("miss you"))
    }

    @Test
    fun preservesFlirtyToneVerbatim() {
        val raw = "Tumhare bina din adhura hai|||Ruko, main aata hoon|||Bas tum hi chahiye"
        val suggestions = ReplySuggestionParser.parse(raw)
        assertEquals(3, suggestions.size)
        assertEquals("Tumhare bina din adhura hai", suggestions[0].text)
        assertEquals("Bas tum hi chahiye", suggestions[2].text)
    }

    @Test
    fun preservesHinglishWithoutTranslation() {
        val raw = "Bhai kal pakka milte hain|||Scene set hai yaar|||Aaj nahi ho payega"
        val suggestions = ReplySuggestionParser.parse(raw)
        assertEquals(3, suggestions.size)
        assertEquals("Bhai kal pakka milte hain", suggestions[0].text)
        assertTrue(suggestions[1].text.contains("yaar"))
    }

    @Test
    fun preservesHindiScript() {
        val raw = "हाँ कल मिलते हैं|||आज थोड़ा busy हूँ|||जरूर चलते हैं"
        val suggestions = ReplySuggestionParser.parse(raw)
        assertEquals(3, suggestions.size)
        assertEquals("हाँ कल मिलते हैं", suggestions[0].text)
    }

    @Test
    fun preservesProfessionalTone() {
        val raw = "Thank you for the update. I'll review and revert by EOD|||Noted, sharing the revised deck shortly|||Could we move the call to 4 PM?"
        val suggestions = ReplySuggestionParser.parse(raw)
        assertEquals(3, suggestions.size)
        assertTrue(suggestions[0].text.startsWith("Thank you for the update"))
    }

    @Test
    fun doesNotNeutralisePlayfulOrEmojiHeavyReplies() {
        val raw = "ARE YOU KIDDING ME 😂😂|||broooo that's wild|||okay fine you win 🙄"
        val suggestions = ReplySuggestionParser.parse(raw)
        assertEquals(3, suggestions.size)
        assertEquals("ARE YOU KIDDING ME 😂😂", suggestions[0].text)
        assertTrue(suggestions[2].text.contains("🙄"))
    }
}
