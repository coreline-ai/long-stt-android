package com.stt.benchmark.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveThemeContrastTest {
    @Test
    fun lightActionColorsMeetAaOnPaper() {
        assertAtLeastAa(LightArchiveCopper, LightArchivePaper)
        assertAtLeastAa(LightArchiveMoss, LightArchivePaper)
    }

    @Test
    fun darkActionColorsMeetAaWithInkText() {
        assertAtLeastAa(ArchiveInk, ArchiveCopper)
        assertAtLeastAa(ArchiveInk, ArchiveMoss)
    }

    private fun assertAtLeastAa(foreground: Color, background: Color) {
        assertTrue(
            "contrast ${contrastRatio(foreground, background)} must be at least 4.5",
            contrastRatio(foreground, background) >= 4.5,
        )
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun channel(value: Float): Double {
        val channel = value.toDouble()
        return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    }
}
