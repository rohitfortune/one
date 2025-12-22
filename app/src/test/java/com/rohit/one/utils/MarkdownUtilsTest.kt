package com.rohit.one.utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownUtilsTest {

    @Test
    fun testToMarkdown_bold() {
        val input = buildAnnotatedString {
            append("Hello ")
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append("World")
            pop()
        }
        val expected = "Hello **World**"
        assertEquals(expected, MarkdownUtils.toMarkdown(input))
    }

    @Test
    fun testParseMarkdown_bold() {
        val input = "Hello **World**"
        val result = MarkdownUtils.parseMarkdown(input)
        
        assertEquals("Hello World", result.text)
        val boldSpans = result.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, boldSpans.size)
        assertEquals(6, boldSpans[0].start)
        assertEquals(11, boldSpans[0].end)
    }

    @Test
    fun testRoundTrip() {
        // Use simple non-nested for basic parser
        val inputStr = "Bold **and** *Italic*"
        val parsed = MarkdownUtils.parseMarkdown(inputStr)
        val outputStr = MarkdownUtils.toMarkdown(parsed)
        assertEquals(inputStr, outputStr)
    }
}
