package com.example.privacy

data class PrivacyCommitment(
    val title: String,
    val description: String,
    val iconEmoji: String
)

object PrivacyDisclosure {
    val COMMITMENTS = listOf(
        PrivacyCommitment(
            title = "100% On-Device AI Execution",
            description = "All AI models execute strictly within your phone's memory and CPU/NPU. No remote server or cloud AI is ever contacted.",
            iconEmoji = "🔒"
        ),
        PrivacyCommitment(
            title = "Zero Cloud Fallback & Zero API Keys",
            description = "ReplyAI has no server backend, requires no accounts, and requires no API keys. Your messages never leave the device.",
            iconEmoji = "🛡️"
        ),
        PrivacyCommitment(
            title = "Ephemeral Screen Processing",
            description = "When OCR fallback is triggered with your consent, screenshots are processed purely in volatile RAM and immediately recycled. No image is ever written to disk.",
            iconEmoji = "⚡"
        ),
        PrivacyCommitment(
            title = "No Analytics or Telemetry on Chat Text",
            description = "ReplyAI collects zero message telemetry, zero keystrokes, and zero contact details. All processing is stateless.",
            iconEmoji = "🚫"
        ),
        PrivacyCommitment(
            title = "User in Complete Control",
            description = "ReplyAI never automatically sends messages. It only copies or inserts suggested text so you can review, edit, and send manually.",
            iconEmoji = "👤"
        )
    )
}
