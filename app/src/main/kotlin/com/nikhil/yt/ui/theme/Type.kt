/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.nikhil.yt.R

// Material 3 sizes, tuned for Poppins: real weights instead of synthesized bold, and tighter tracking on
// large text because Poppins is wide and M3's default spacing makes it look loose.
private fun style(family: FontFamily, weight: FontWeight, size: Int, line: Int, tracking: TextUnit) =
    TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking)

private fun buildTypography(f: FontFamily) =
    Typography(
        displayLarge = style(f, FontWeight.Bold, 57, 62, (-1.4).sp),
        displayMedium = style(f, FontWeight.Bold, 45, 50, (-1.0).sp),
        displaySmall = style(f, FontWeight.SemiBold, 36, 42, (-0.7).sp),
        headlineLarge = style(f, FontWeight.SemiBold, 32, 38, (-0.6).sp),
        headlineMedium = style(f, FontWeight.SemiBold, 28, 34, (-0.5).sp),
        headlineSmall = style(f, FontWeight.SemiBold, 24, 30, (-0.4).sp),
        titleLarge = style(f, FontWeight.SemiBold, 22, 28, (-0.3).sp),
        titleMedium = style(f, FontWeight.Medium, 16, 22, (-0.1).sp),
        titleSmall = style(f, FontWeight.Medium, 14, 20, 0.sp),
        bodyLarge = style(f, FontWeight.Normal, 16, 24, 0.1.sp),
        bodyMedium = style(f, FontWeight.Normal, 14, 20, 0.1.sp),
        bodySmall = style(f, FontWeight.Normal, 12, 16, 0.2.sp),
        labelLarge = style(f, FontWeight.Medium, 14, 20, 0.1.sp),
        labelMedium = style(f, FontWeight.Medium, 12, 16, 0.3.sp),
        labelSmall = style(f, FontWeight.Medium, 11, 16, 0.4.sp),
    )

private val AppFontFamily = FontFamily(
    Font(R.font.poppins, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)
val AppTypography = buildTypography(AppFontFamily)
val SystemTypography = buildTypography(FontFamily.Default)
