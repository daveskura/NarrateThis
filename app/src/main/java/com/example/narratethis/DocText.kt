package com.example.narratethis

import java.text.BreakIterator
import java.util.Locale

/** One spoken unit (usually a sentence). [start]/[end] are offsets into [DocText.text]. */
data class Chunk(val start: Int, val end: Int, val paragraph: Int)

/** A paragraph of the document and the range of chunks it contains. */
data class Paragraph(val start: Int, val end: Int, val firstChunk: Int, val lastChunk: Int)

/**
 * A document split into paragraphs and sentence-sized chunks for text-to-speech.
 * Positions are remembered as character offsets so they survive small edits to the doc.
 */
class DocText(val text: String, maxChunkLength: Int = 3000) {
    val chunks: List<Chunk>
    val paragraphs: List<Paragraph>

    init {
        val chunkList = mutableListOf<Chunk>()
        val paragraphList = mutableListOf<Paragraph>()
        val sentences = BreakIterator.getSentenceInstance(Locale.getDefault())

        var lineStart = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val (pStart, pEnd) = trimRange(lineStart, newline)
            if (pStart < pEnd && text.substring(pStart, pEnd).any { it.isLetterOrDigit() }) {
                val first = chunkList.size
                val paragraphIndex = paragraphList.size
                sentences.setText(text.substring(pStart, pEnd))
                var s = sentences.first()
                var e = sentences.next()
                while (e != BreakIterator.DONE) {
                    val (cs, ce) = trimRange(pStart + s, pStart + e)
                    if (cs < ce) splitLong(cs, ce, maxChunkLength) { a, b ->
                        chunkList += Chunk(a, b, paragraphIndex)
                    }
                    s = e
                    e = sentences.next()
                }
                if (chunkList.size > first) {
                    paragraphList += Paragraph(pStart, pEnd, first, chunkList.size - 1)
                }
            }
            lineStart = newline + 1
        }
        chunks = chunkList
        paragraphs = paragraphList
    }

    fun chunkText(index: Int): String = chunks[index].let { text.substring(it.start, it.end) }

    fun paragraphText(index: Int): String = paragraphs[index].let { text.substring(it.start, it.end) }

    /** Index of the chunk containing (or following) the given character offset. */
    fun chunkAtOffset(offset: Int): Int {
        if (chunks.isEmpty()) return 0
        val i = chunks.indexOfFirst { it.end > offset }
        return if (i < 0) chunks.lastIndex else i
    }

    private fun trimRange(start: Int, end: Int): Pair<Int, Int> {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return s to e
    }

    /** Breaks an overly long sentence at word boundaries so the TTS engine accepts it. */
    private inline fun splitLong(start: Int, end: Int, max: Int, emit: (Int, Int) -> Unit) {
        var s = start
        while (end - s > max) {
            var cut = text.lastIndexOf(' ', s + max)
            if (cut <= s) cut = s + max
            emit(s, cut)
            s = cut
            while (s < end && text[s].isWhitespace()) s++
        }
        if (s < end) emit(s, end)
    }
}
