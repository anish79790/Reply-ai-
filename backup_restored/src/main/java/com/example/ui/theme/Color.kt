package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val InstaPurple = Color(0xFF833AB4)
val InstaMagenta = Color(0xFFC13584)
val InstaPink = Color(0xFFE1306C)
val InstaRed = Color(0xFFFD1D1D)
val InstaOrange = Color(0xFFF77737)
val InstaYellow = Color(0xFFFCAF45)

val DarkBackground = Color(0xFF0F0F12)
val DarkSurface = Color(0xFF18181E)
val DarkSurfaceVariant = Color(0xFF24242E)
val DarkCard = Color(0xFF1E1E26)
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFA0A0AB)
val TextTertiary = Color(0xFF71717A)

val NeonCyan = Color(0xFF00E5FF)
val NeonGreen = Color(0xFF00E676)
val SuccessGreen = Color(0xFF10B981)
val WarningAmber = Color(0xFFF59E0B)

val ReplyAIPurple = Color(0xFF8B5CF6)
val ReplyAIViolet = Color(0xFF6D28D9)
val ReplyAIAccent = Color(0xFFA78BFA)
val ReplyAIBlue = Color(0xFF3B82F6)
val ReplyAIPink = Color(0xFFEC4899)

val ReplyAIGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899))
)

val InstagramGradient = Brush.linearGradient(
    colors = listOf(InstaPurple, InstaMagenta, InstaPink, InstaOrange, InstaYellow)
)

val InstagramPillGradient = Brush.horizontalGradient(
    colors = listOf(InstaPurple, InstaPink, InstaOrange)
)

val CardGlowGradient = Brush.linearGradient(
    colors = listOf(Color(0x33833AB4), Color(0x33E1306C), Color(0x110F0F12))
)

