package com.rohit.one.utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

object MarkdownUtils {

    fun toMarkdown(annotatedString: AnnotatedString): String {
        val text = annotatedString.text
        val transitions = mutableListOf<Transition>()

        annotatedString.spanStyles.forEach { range ->
            val style = range.item
            val start = range.start
            val end = range.end

            if (style.fontWeight == FontWeight.Bold) {
                transitions.add(Transition(start, "**", true))
                transitions.add(Transition(end, "**", false))
            }
            if (style.fontStyle == FontStyle.Italic) {
                transitions.add(Transition(start, "*", true))
                transitions.add(Transition(end, "*", false))
            }
            if (style.textDecoration == TextDecoration.Underline) {
                transitions.add(Transition(start, "__", true))
                transitions.add(Transition(end, "__", false))
            }
        }

        transitions.sortWith(compareBy({ it.index }, { !it.isStart }))

        val sb = StringBuilder()
        var lastIndex = 0

        for (t in transitions) {
            if (t.index > lastIndex) {
                sb.append(text.substring(lastIndex, t.index))
            }
            sb.append(t.token)
            lastIndex = t.index
        }
        if (lastIndex < text.length) {
            sb.append(text.substring(lastIndex))
        }

        return sb.toString()
    }

    private data class Transition(val index: Int, val token: String, val isStart: Boolean)

    fun parseMarkdown(markdown: String): AnnotatedString {
        if (markdown.isEmpty()) return AnnotatedString("")
        val basicParsed = parseMarkdownState(markdown)
        return applyChecklistStrikethrough(basicParsed)
    }

    fun applyChecklistStrikethrough(annotatedString: AnnotatedString): AnnotatedString {
        val text = annotatedString.text
        val builder = AnnotatedString.Builder(annotatedString)
        var lineStart = 0
        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
            // Check for "[x] " at start of the line
            if (text.startsWith("[x] ", lineStart)) {
                // Apply strikethrough to the content of the line (excluding the checkbox)
                // "[x] " is 4 chars. from lineStart + 4 to lineEnd
                if (lineStart + 4 < lineEnd) {
                    builder.addStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough),
                        lineStart + 4,
                        lineEnd
                    )
                }
            }
            lineStart = lineEnd + 1
        }
        return builder.toAnnotatedString()
    }

    private fun parseMarkdownState(text: String): AnnotatedString {
        return buildAnnotatedString {
            var i = 0
            val activeStyles = mutableListOf<ActiveStyle>()
            
            while (i < text.length) {
                if (text.startsWith("**", i)) {
                    // Check if closing or opening
                    val closingIndex = activeStyles.indexOfLast { it.token == "**" }
                    if (closingIndex != -1) {
                         // Close
                         addStyle(SpanStyle(fontWeight = FontWeight.Bold), activeStyles[closingIndex].startIndex, length)
                         activeStyles.removeAt(closingIndex)
                    } else {
                        // Open
                        activeStyles.add(ActiveStyle("**", length))
                    }
                    i += 2
                } else if (text.startsWith("__", i)) {
                     val closingIndex = activeStyles.indexOfLast { it.token == "__" }
                    if (closingIndex != -1) {
                         // Close
                         addStyle(SpanStyle(textDecoration = TextDecoration.Underline), activeStyles[closingIndex].startIndex, length)
                         activeStyles.removeAt(closingIndex)
                    } else {
                        // Open
                        activeStyles.add(ActiveStyle("__", length))
                    }
                    i += 2
                } else if (text.startsWith("*", i)) {
                    // Ambiguity check: if it was **, we would have hit the first if.
                     val closingIndex = activeStyles.indexOfLast { it.token == "*" }
                    if (closingIndex != -1) {
                         // Close
                         addStyle(SpanStyle(fontStyle = FontStyle.Italic), activeStyles[closingIndex].startIndex, length)
                         activeStyles.removeAt(closingIndex)
                    } else {
                        // Open
                        activeStyles.add(ActiveStyle("*", length))
                    }
                    i += 1
                } else {
                    append(text[i])
                    i++
                }
            }
        }
    }
    
    private data class ActiveStyle(val token: String, val startIndex: Int)
}
