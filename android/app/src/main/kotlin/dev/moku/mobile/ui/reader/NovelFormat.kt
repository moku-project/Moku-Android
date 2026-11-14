package dev.moku.mobile.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal sealed interface NovelBlock {
    data class Para(val text: AnnotatedString) : NovelBlock
    data class Heading(val text: AnnotatedString) : NovelBlock
    data class Quote(val text: AnnotatedString) : NovelBlock
    data class ListItem(val text: AnnotatedString, val marker: String) : NovelBlock
    data object Rule : NovelBlock
}

private val BLOCKS = setOf(
    "p", "div", "section", "article", "main", "header", "footer", "blockquote", "ul", "ol", "li",
    "h1", "h2", "h3", "h4", "h5", "h6", "hr", "pre", "table", "tbody", "tr", "td", "figure", "center",
)
private val SKIP = setOf("script", "style", "noscript", "img", "svg", "iframe", "button", "nav")
private val SPACES = Regex("""[ \t\r\n\f]+""")
private val BLANK_LINES = Regex("""\n\s*\n+""")

internal fun parseNovel(html: String): List<NovelBlock> {
    if (!html.contains('<')) {
        return html.split(Regex("""\n{2,}""")).map { it.trim() }.filter { it.isNotEmpty() }.map { NovelBlock.Para(AnnotatedString(it)) }
    }
    val out = mutableListOf<NovelBlock>()
    walkBlock(Jsoup.parseBodyFragment(html).body(), out)
    return out
}

private fun Element.isBlockTag() = tagName().lowercase() in BLOCKS

private fun walkBlock(el: Element, out: MutableList<NovelBlock>) {
    val loose = mutableListOf<Node>()
    fun flush() {
        if (loose.isEmpty()) return
        val text = inline(loose)
        loose.clear()
        splitParagraphs(text).forEach { out.add(NovelBlock.Para(it)) }
    }
    for (node in el.childNodes()) {
        if (node is Element && node.tagName().lowercase() in SKIP) continue
        if (node is Element && node.isBlockTag()) {
            flush()
            when (val tag = node.tagName().lowercase()) {
                "hr" -> out.add(NovelBlock.Rule)
                "h1", "h2", "h3", "h4", "h5", "h6" -> inline(node.childNodes()).trimmed()?.let { out.add(NovelBlock.Heading(it)) }
                "blockquote" -> inline(node.childNodes()).trimmed()?.let { out.add(NovelBlock.Quote(it)) }
                "ul", "ol" -> node.children().filter { it.tagName().equals("li", true) }.forEachIndexed { i, li ->
                    inline(li.childNodes()).trimmed()?.let { out.add(NovelBlock.ListItem(it, if (tag == "ol") "${i + 1}." else "•")) }
                }
                else -> if (node.children().any { it.isBlockTag() }) walkBlock(node, out) else {
                    splitParagraphs(inline(node.childNodes())).forEach { out.add(NovelBlock.Para(it)) }
                }
            }
        } else {
            loose.add(node)
        }
    }
    flush()
}

private fun splitParagraphs(text: AnnotatedString): List<AnnotatedString> {
    val result = mutableListOf<AnnotatedString>()
    var start = 0
    BLANK_LINES.findAll(text.text).forEach { m ->
        text.subSequence(start, m.range.first).trimmed()?.let(result::add)
        start = m.range.last + 1
    }
    text.subSequence(start, text.length).trimmed()?.let(result::add)
    return result
}

private fun AnnotatedString.trimmed(): AnnotatedString? {
    val s = text
    var a = 0
    var b = s.length
    while (a < b && s[a].isWhitespace()) a++
    while (b > a && s[b - 1].isWhitespace()) b--
    return if (a >= b) null else subSequence(a, b)
}

private fun inline(nodes: List<Node>): AnnotatedString = buildAnnotatedString {
    fun visit(node: Node) {
        when (node) {
            is TextNode -> {
                val t = node.wholeText.replace(SPACES, " ")
                if (t.isNotEmpty()) append(t)
            }
            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag in SKIP) return
                if (tag == "br") {
                    append('\n')
                    return
                }
                val style = when (tag) {
                    "em", "i", "cite" -> SpanStyle(fontStyle = FontStyle.Italic)
                    "strong", "b" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                    "s", "del", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    else -> null
                }
                if (style != null) pushStyle(style)
                node.childNodes().forEach(::visit)
                if (style != null) pop()
                if (node.isBlockTag()) append("\n\n")
            }
        }
    }
    nodes.forEach(::visit)
}.let(::collapseBreakSpaces)

// Spaces next to a <br> would otherwise indent the wrapped line.
private fun collapseBreakSpaces(s: AnnotatedString): AnnotatedString {
    val text = s.text
    val keep = BooleanArray(text.length) { true }
    for (i in text.indices) {
        if (text[i] == ' ' && ((i > 0 && text[i - 1] == '\n') || (i + 1 < text.length && text[i + 1] == '\n'))) keep[i] = false
    }
    if (keep.all { it }) return s
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            if (!keep[i]) {
                i++
                continue
            }
            var j = i
            while (j < text.length && keep[j]) j++
            append(s.subSequence(i, j))
            i = j
        }
    }
}
