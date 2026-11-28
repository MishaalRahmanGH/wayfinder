// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertiesLayoutTest {

    @Test
    fun shouldStackPropertiesRowsRespondsToNarrowWidthsAndLargeFontScales() {
        // Desktop / tablet / wide dialog content widths stay two-column up to 1.2x font scale.
        assertFalse(shouldStackPropertiesRows(availableWidthDp = 500f, fontScale = 1.0f))
        assertFalse(shouldStackPropertiesRows(availableWidthDp = 380f, fontScale = 1.0f))
        assertFalse(shouldStackPropertiesRows(availableWidthDp = 380f, fontScale = 1.2f))

        // Narrow viewports (< 380dp) switch to stacked label-above-value rows.
        assertTrue(shouldStackPropertiesRows(availableWidthDp = 379f, fontScale = 1.0f))
        assertTrue(shouldStackPropertiesRows(availableWidthDp = 340f, fontScale = 1.0f))
        assertTrue(shouldStackPropertiesRows(availableWidthDp = 280f, fontScale = 1.0f))

        // Font scales above 1.2x switch to stacked rows to prevent mid-word breaks in localized labels.
        assertTrue(shouldStackPropertiesRows(availableWidthDp = 480f, fontScale = 1.25f))
        assertTrue(shouldStackPropertiesRows(availableWidthDp = 520f, fontScale = 1.3f))
        assertTrue(shouldStackPropertiesRows(availableWidthDp = 520f, fontScale = 1.5f))
    }

    @Test
    fun propertiesValueTextStyleEnforcesLtrAndMonospaceOnlyWhenMonoEnabled() {
        val base = TextStyle.Default
        val regular = propertiesValueTextStyle(base, mono = false)
        assertNull(regular.fontFamily)
        assertEquals(TextDirection.Unspecified, regular.textDirection)

        val monoStyle = propertiesValueTextStyle(base, mono = true)
        assertEquals(FontFamily.Monospace, monoStyle.fontFamily)
        assertEquals(TextDirection.Ltr, monoStyle.textDirection)
    }
}
