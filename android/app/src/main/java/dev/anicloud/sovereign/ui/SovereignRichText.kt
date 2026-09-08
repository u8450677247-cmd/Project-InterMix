package dev.anicloud.sovereign.prototype.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Whole-document regex styling is deliberately bounded so the editor never
// trades keyboard latency for decoration on unusually large source files.
private const val MaxHighlightedCharacters = 128 * 1024

/**
 * Static smoked glass: translucent enough to reveal LivingVoid, but deliberately
 * avoids a live blur shader while E4B owns the GPU.
 */
@Composable
internal fun Modifier.sovereignGlass(
    accent: Color = HorizonCyan,
    radius: Dp = 18.dp,
    depth: Float = 0.82f,
    elevation: Dp = 10.dp,
): Modifier {
    val shape = RoundedCornerShape(radius)
    val scheme = MaterialTheme.colorScheme
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = accent.copy(alpha = 0.08f),
            spotColor = accent.copy(alpha = 0.14f),
        )
        .clip(shape)
        .background(
            brush = Brush.linearGradient(
                listOf(
                    scheme.surface.copy(alpha = depth),
                    scheme.surfaceVariant.copy(alpha = (depth - 0.12f).coerceAtLeast(0.24f)),
                    CognitionViolet.copy(alpha = 0.055f),
                ),
            ),
        )
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(
                    accent.copy(alpha = 0.58f),
                    SoftViolet.copy(alpha = 0.20f),
                    Color.White.copy(alpha = 0.055f),
                ),
            ),
            shape = shape,
        )
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListItem(val marker: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String, val source: String) : MarkdownBlock
}

@Composable
internal fun SovereignMarkdown(
    source: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(source) { parseMarkdown(source) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = styledInline(block.text),
                    color = when (block.level) {
                        1 -> HorizonCyan
                        2 -> SoftViolet
                        else -> ResonanceMint
                    },
                    fontSize = when (block.level) {
                        1 -> 22.sp
                        2 -> 19.sp
                        else -> 16.sp
                    },
                    lineHeight = when (block.level) {
                        1 -> 28.sp
                        2 -> 25.sp
                        else -> 22.sp
                    },
                    fontWeight = FontWeight.Bold,
                )

                is MarkdownBlock.Paragraph -> Text(
                    text = styledInline(block.text),
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 21.sp,
                )

                is MarkdownBlock.ListItem -> Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(block.marker, color = CognitionViolet, fontWeight = FontWeight.Bold)
                    Text(
                        text = styledInline(block.text),
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 21.sp,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MarkdownBlock.Quote -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SoftViolet.copy(alpha = 0.075f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(34.dp)
                            .background(SoftViolet, RoundedCornerShape(50)),
                    )
                    Text(
                        text = styledInline(block.text),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MarkdownBlock.Code -> SovereignCodeBlock(block.source, block.language)
            }
        }
    }
}

@Composable
private fun SovereignCodeBlock(source: String, language: String) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .sovereignGlass(
                CognitionViolet,
                radius = 12.dp,
                depth = 0.88f,
                elevation = 3.dp,
            ),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CognitionViolet.copy(alpha = 0.10f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text("CODE", color = HorizonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    language.ifBlank { "TEXT" }.uppercase(),
                    color = SoftViolet,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
            SelectionContainer {
                Text(
                    text = highlightCode(source, language),
                    color = PrimaryText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

internal class SovereignCodeTransformation(
    private val language: String,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        text = highlightCode(text.text, language),
        offsetMapping = OffsetMapping.Identity,
    )
}

internal fun languageForFile(displayName: String?): String = when (
    displayName?.substringAfterLast('.', "")?.lowercase()
) {
    "c", "h" -> "c"
    "cc", "cpp", "hpp" -> "cpp"
    "css" -> "css"
    "go" -> "go"
    "gradle", "kts" -> "kotlin"
    "html" -> "html"
    "java" -> "java"
    "js" -> "javascript"
    "json" -> "json"
    "kt" -> "kotlin"
    "lua" -> "lua"
    "md" -> "markdown"
    "py" -> "python"
    "rs" -> "rust"
    "sh" -> "shell"
    "sql" -> "sql"
    "toml" -> "toml"
    "ts", "tsx" -> "typescript"
    "xml" -> "xml"
    "yaml", "yml" -> "yaml"
    else -> "text"
}

internal fun highlightCode(source: String, language: String?): AnnotatedString {
    val builder = AnnotatedString.Builder(source)
    if (source.isEmpty()) return builder.toAnnotatedString()
    builder.addStyle(SpanStyle(color = PrimaryText), 0, source.length)
    if (source.length > MaxHighlightedCharacters) return builder.toAnnotatedString()

    fun style(pattern: Regex, span: SpanStyle) {
        pattern.findAll(source).forEach { match ->
            builder.addStyle(span, match.range.first, match.range.last + 1)
        }
    }

    val normalized = language.orEmpty().lowercase()
    if (normalized == "markdown" || normalized == "md") {
        style(
            Regex("(?m)^#{1,6}\\s+.*$"),
            SpanStyle(color = HorizonCyan, fontWeight = FontWeight.Bold),
        )
        style(
            Regex("(?m)^(?:[-*+]\\s+|\\d+[.)]\\s+).*$"),
            SpanStyle(color = SoftViolet),
        )
        style(Regex("`[^`\\n]+`"), SpanStyle(color = ResonanceMint))
        style(Regex("\\*\\*[^*\\n]+\\*\\*"), SpanStyle(fontWeight = FontWeight.Bold))
        return builder.toAnnotatedString()
    }

    val keywords = Regex(
        "\\b(?:abstract|and|as|async|await|break|by|case|catch|class|const|continue|" +
            "data|def|do|else|enum|except|export|extends|false|finally|for|from|fun|" +
            "function|if|import|in|interface|internal|is|let|match|new|null|object|or|" +
            "override|package|private|protected|public|raise|return|sealed|static|struct|" +
            "super|suspend|this|throw|true|try|typealias|typeof|val|var|when|while|with)\\b",
        RegexOption.IGNORE_CASE,
    )
    style(keywords, SpanStyle(color = SoftViolet, fontWeight = FontWeight.SemiBold))
    style(
        Regex("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()"),
        SpanStyle(color = HorizonCyan),
    )
    style(
        Regex("\\b(?:0x[0-9A-Fa-f]+|\\d+(?:\\.\\d+)?)\\b"),
        SpanStyle(color = WaitingAmber),
    )
    style(
        Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"),
        SpanStyle(color = ResonanceMint),
    )
    if (normalized in setOf("python", "shell", "yaml", "toml")) {
        style(
            Regex("(?m)#.*$"),
            SpanStyle(color = MutedText, fontStyle = FontStyle.Italic),
        )
    } else {
        style(
            Regex("(?m)//.*$|/\\*[\\s\\S]*?\\*/"),
            SpanStyle(color = MutedText, fontStyle = FontStyle.Italic),
        )
    }
    return builder.toAnnotatedString()
}

private fun parseMarkdown(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var codeLanguage = ""
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trimEnd())
            paragraph.clear()
        }
    }

    source.replace("\r\n", "\n").lines().forEach { line ->
        val trimmed = line.trimStart()
        if (inCode) {
            if (trimmed.startsWith("```")) {
                blocks += MarkdownBlock.Code(codeLanguage, code.joinToString("\n"))
                code.clear()
                codeLanguage = ""
                inCode = false
            } else {
                code += line
            }
            return@forEach
        }
        if (trimmed.startsWith("```")) {
            flushParagraph()
            codeLanguage = trimmed.removePrefix("```").trim().substringBefore(' ')
            inCode = true
            return@forEach
        }
        if (line.isBlank()) {
            flushParagraph()
            return@forEach
        }

        val heading = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
        val unordered = Regex("^[-*+]\\s+(.+)$").find(trimmed)
        val ordered = Regex("^(\\d+[.)])\\s+(.+)$").find(trimmed)
        when {
            heading != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(
                    heading.groupValues[1].length,
                    heading.groupValues[2],
                )
            }
            unordered != null -> {
                flushParagraph()
                blocks += MarkdownBlock.ListItem("◆", unordered.groupValues[1])
            }
            ordered != null -> {
                flushParagraph()
                blocks += MarkdownBlock.ListItem(ordered.groupValues[1], ordered.groupValues[2])
            }
            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(trimmed.removePrefix(">").trimStart())
            }
            else -> paragraph += line
        }
    }
    flushParagraph()
    if (inCode) blocks += MarkdownBlock.Code(codeLanguage, code.joinToString("\n"))
    return blocks.ifEmpty { listOf(MarkdownBlock.Paragraph(source)) }
}

private fun styledInline(source: String): AnnotatedString = buildAnnotatedString {
    val semantic = mapOf(
        "[SUCCESS]" to ResonanceMint,
        "[INFO]" to HorizonCyan,
        "[ACTION]" to CognitionViolet,
        "[WARNING]" to WaitingAmber,
        "[BLOCKED]" to InterventionCoral,
    )
    var index = 0
    while (index < source.length) {
        val badge = semantic.entries.firstOrNull { source.startsWith(it.key, index) }
        if (badge != null) {
            pushStyle(SpanStyle(color = badge.value, fontWeight = FontWeight.Bold))
            append(badge.key)
            pop()
            index += badge.key.length
            continue
        }
        if (source.startsWith("**", index)) {
            val end = source.indexOf("**", index + 2)
            if (end > index + 2) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = PrimaryText))
                append(source.substring(index + 2, end))
                pop()
                index = end + 2
                continue
            }
        }
        if (source[index] == '`') {
            val end = source.indexOf('`', index + 1)
            if (end > index + 1) {
                pushStyle(
                    SpanStyle(
                        color = ResonanceMint,
                        background = SmokedDeep.copy(alpha = 0.85f),
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                append(source.substring(index + 1, end))
                pop()
                index = end + 1
                continue
            }
        }
        if (source[index] == '[') {
            val labelEnd = source.indexOf("](", index + 1)
            val urlEnd = if (labelEnd > index) source.indexOf(')', labelEnd + 2) else -1
            if (labelEnd > index && urlEnd > labelEnd) {
                pushStyle(SpanStyle(color = HorizonCyan, textDecoration = TextDecoration.Underline))
                append(source.substring(index + 1, labelEnd))
                pop()
                index = urlEnd + 1
                continue
            }
        }
        append(source[index])
        index++
    }
}
