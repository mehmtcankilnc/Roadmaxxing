package app.roadmaxxing.ui.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.roadmaxxing.theme.RoadmaxxingTheme

// DRAFT: renders legal texts that must be reviewed by the owner or a lawyer before launch.

/** Block of the minimal Markdown subset used by the legal texts. */
internal sealed interface LegalBlock {
    data class Heading(val level: Int, val text: String) : LegalBlock
    data class Paragraph(val text: String) : LegalBlock
    data class Bullet(val text: String) : LegalBlock
}

/**
 * Parses headings (#, ##, ###), bullet lists (- or *), paragraphs (blank-line separated; consecutive lines
 * are joined) and skips `<!-- ... -->` comment blocks. Inline bold is handled at render time.
 */
internal fun parseLegalMarkdown(source: String): List<LegalBlock> {
    val blocks = mutableListOf<LegalBlock>()
    val paragraph = StringBuilder()
    var inComment = false

    fun flush() {
        if (paragraph.isNotEmpty()) {
            blocks += LegalBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
    }

    for (raw in source.lines()) {
        val line = raw.trim()
        if (inComment) {
            if (line.contains("-->")) inComment = false
            continue
        }
        if (line.startsWith("<!--")) {
            flush()
            if (!line.contains("-->")) inComment = true
            continue
        }
        when {
            line.isEmpty() -> flush()
            line.startsWith("#") -> {
                flush()
                val level = line.takeWhile { it == '#' }.length.coerceAtMost(3)
                blocks += LegalBlock.Heading(level, line.trimStart('#').trim())
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flush()
                blocks += LegalBlock.Bullet(line.substring(2).trim())
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
        }
    }
    flush()
    return blocks
}

/** Turns double-asterisk segments into bold spans; an unmatched marker is kept literally. */
internal fun boldSpans(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    val balanced = parts.size % 2 == 1
    parts.forEachIndexed { index, part ->
        if (balanced && index % 2 == 1) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
        } else {
            if (!balanced && index > 0) append("**")
            append(part)
        }
    }
}

@Composable
internal fun LegalBody(blocks: List<LegalBlock>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is LegalBlock.Heading -> Text(
                    text = boldSpans(block.text),
                    modifier = Modifier.padding(top = if (block.level == 1) 0.dp else 8.dp),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.onBackground,
                )
                is LegalBlock.Paragraph -> Text(
                    text = boldSpans(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                is LegalBlock.Bullet -> Row(
                    modifier = Modifier.padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BulletText(block.text)
                }
            }
        }
    }
}

@Composable
private fun RowScope.BulletText(text: String) {
    Text(
        text = "•",
        style = MaterialTheme.typography.bodyLarge,
        color = RoadmaxxingTheme.extraColors.textSecondary,
    )
    Text(
        text = boldSpans(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.weight(1f),
    )
}
