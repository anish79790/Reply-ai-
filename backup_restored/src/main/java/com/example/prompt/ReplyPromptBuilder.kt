package com.example.prompt

import com.example.conversation.DetectedLanguage
import com.example.conversation.ExtractedConversation

class ReplyPromptBuilder : PromptBuilder {

    override fun buildPrompt(
        conversation: ExtractedConversation,
        maxContextMessages: Int,
        customPersona: String?,
        businessContext: String?,
        tone: String,
        strategy: String,
        customPromptText: String
    ): String {
        val lastMessage = conversation.messages.lastOrNull()?.text ?: ""
        val historySnippet = conversation.formatForPrompt(maxContextMessages)

        // Clean tone
        val effectiveTone = if (tone == "Auto") "Natural & chill" else tone

        val personaSnippet = if (!customPersona.isNullOrBlank()) {
            "\nUSER PERSONA / TONE STYLE:\n$customPersona\n"
        } else ""

        val businessSnippet = if (!businessContext.isNullOrBlank()) {
            "\nBUSINESS CONTEXT & POLICIES:\n$businessContext\n"
        } else ""

        if (strategy == "grok") {
            return """
                You are a real human texting on Instagram and WhatsApp. You talk like real young friends do. Zero robotic AI energy. No corporate speak. No canned generic fillers.

                Variables:
                SELECTED_TONE = $effectiveTone
                CHAT_HISTORY = $historySnippet
                LAST_MESSAGE = $lastMessage
                $personaSnippet$businessSnippet

                STRICT ANTI-CLICHÉ RULES:
                1. NEVER output generic agreement clichés like "Haan bhai, agree karta hoon teri baat se", "Haan bhai bilkul", "Sahi baat hai", or generic "I agree".
                2. Every reply MUST directly reference the specific topic, question, time, place, or detail in LAST_MESSAGE.
                3. Match the language of LAST_MESSAGE: If Hinglish (Hindi written in English alphabets), reply in natural urban Hinglish texting style. If English, reply in English.
                4. Give 3 distinct reply options:
                   - Option 1 (Direct reaction / direct answer)
                   - Option 2 (Playful / enthusiastic / follow-up question)
                   - Option 3 (Alternative / counter-proposal / different angle)
                5. Output format strictly:
                   REPLY_ONE|||REPLY_TWO|||REPLY_THREE
            """.trimIndent()
        }

        if (strategy == "chatgpt") {
            return """
                You are an authentic texting assistant generating 3 real reply suggestions for a chat.
                
                INPUTS:
                Tone: $effectiveTone
                Recent conversation: $historySnippet
                Message to reply to: $lastMessage
                $personaSnippet$businessSnippet

                STRICT ANTI-CLICHÉ RULES:
                1. NEVER output generic canned lines like "Haan bhai, agree karta hoon teri baat se", "Haan bhai bilkul", "Sahi bol raha hai", or repetitive fillers.
                2. Every reply MUST directly address the specific subject, question, or proposition in the message to reply to.
                3. Match the exact language style of the incoming message (Hinglish/English/Hindi).
                4. Options must be diverse:
                   - Option 1: Direct answer / clear reaction
                   - Option 2: Casual / playful / humorous
                   - Option 3: Counter-offer / alternative / follow-up question
                5. Output format strictly:
                   REPLY_ONE|||REPLY_TWO|||REPLY_THREE
            """.trimIndent()
        }
        
        if (strategy == "custom" && customPromptText.isNotBlank()) {
            return customPromptText
                .replace("{SELECTED_TONE}", effectiveTone)
                .replace("{CHAT_HISTORY}", historySnippet)
                .replace("{LAST_MESSAGE}", lastMessage)
                .replace("{USER_PERSONA}", customPersona ?: "")
                .replace("{BUSINESS_CONTEXT}", businessContext ?: "")
        }

        // DEFAULT (Gemini/Dev Prompt)
        return """
            You are a real person texting in a messaging app. You are NOT an AI assistant. Never use generic or robotic templates.
            
            Tone: $effectiveTone
            $personaSnippet$businessSnippet
            Chat Context: 
            $historySnippet
            
            Message to reply to: 
            $lastMessage
            
            CRITICAL RULES:
            1. NEVER generate generic canned agree-statements like "Haan bhai, agree karta hoon teri baat se!", "Haan bhai bilkul", "Sahi baat hai", or generic "I agree".
            2. Every reply MUST directly reference the exact subject, plan, or question in the message to reply to.
            3. Match the language: If the message is in Hinglish (Hindi in English letters), reply in natural Hinglish. If English, reply in English.
            4. Generate EXACTLY 3 distinct options:
               - Option 1 (Direct answer / reaction addressing the specific point)
               - Option 2 (Casual / witty / engaging follow-up)
               - Option 3 (Alternative perspective / counter-proposal / different idea)
            5. Output ONLY the 3 replies separated by ||| without numbering, quotes, or conversational preambles.
            
            Example format:
            First reply option here|||Second reply option here|||Third reply option here
        """.trimIndent()
    }

    override fun buildAiCommandPrompt(
        command: String,
        conversation: ExtractedConversation,
        customPersona: String?,
        businessContext: String?
    ): String {
        val conversationText = if (conversation.messages.isNotEmpty()) {
            conversation.formatForPrompt(10)
        } else {
            "No prior messages."
        }

        val personaSnippet = if (!customPersona.isNullOrBlank()) {
            "\nUSER PERSONA / TONE STYLE:\n$customPersona\n"
        } else {
            ""
        }

        val businessSnippet = if (!businessContext.isNullOrBlank()) {
            "\nBUSINESS KNOWLEDGE BASE:\n$businessContext\n"
        } else {
            ""
        }

        return """
SYSTEM:
You are Zinro In-Chat AI Command Engine.
The user typed a command prefix in the chat input to generate a single polished message to send directly in this conversation.

IN-CHAT CONVERSATION HISTORY:
$conversationText$personaSnippet$businessSnippet

USER'S COMMAND / INSTRUCTION:
"$command"

TASK:
Write the exact single reply message to send in the chat satisfying the user's instruction and the conversation context.
- Keep it natural, human, conversational, and direct.
- Do NOT output explanations, greetings to the user, or quotes.
- Output ONLY the final chat message to be sent.
""".trimIndent()
    }

    override fun buildCompletionPrompt(
        draftText: String,
        conversation: ExtractedConversation,
        customPersona: String?,
        businessContext: String?
    ): String {
        val conversationText = if (conversation.messages.isNotEmpty()) {
            conversation.formatForPrompt(10)
        } else {
            "No prior messages."
        }

        val personaSnippet = if (!customPersona.isNullOrBlank()) {
            "\nUSER PERSONA / TONE STYLE:\n$customPersona\n"
        } else {
            ""
        }

        val businessSnippet = if (!businessContext.isNullOrBlank()) {
            "\nBUSINESS CONTEXT:\n$businessContext\n"
        } else {
            ""
        }

        return """
SYSTEM:
You are an ultra-fast in-chat auto-complete assistant for social media messaging.
The user is currently typing a message in their chat composer. Their current typed draft is:
"$draftText"

IN-CHAT CONVERSATION CONTEXT:
$conversationText$personaSnippet$businessSnippet

TASK:
Provide exactly 3 natural, smart completions or continuation options that complete the user's sentence smoothly.

RULES:
1. Each option MUST be a complete sentence that begins with or smoothly completes "$draftText".
2. Stay closely relevant to the ongoing chat topic and conversation context.
3. Match the language and tone: if Hinglish, write strictly in modern Roman Hindi chat slang (bhai, yaar, haan, scene, etc.).
4. Provide exactly 3 options without markdown, quotes, or conversational intros.
5. Separate options with |||

Example Output:
$draftText I think we should go!|||$draftText not sure yet tbh.|||$draftText what do you think?
""".trimIndent()
    }
}
