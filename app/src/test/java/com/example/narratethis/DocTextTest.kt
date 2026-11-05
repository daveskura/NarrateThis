package com.example.narratethis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocTextTest {
    private val sample = "﻿Chapter One\r\n\r\nIt was a dark night. The wind howled!  Was anyone there?\r\n\r\n* * *\r\nThe end."

    @Test
    fun splitsIntoParagraphsAndSentences() {
        val doc = DocText(sample.removePrefix("﻿"))
        assertEquals(3, doc.paragraphs.size) // "* * *" has no words and is skipped
        assertEquals(
            listOf("Chapter One", "It was a dark night.", "The wind howled!", "Was anyone there?", "The end."),
            doc.chunks.indices.map(doc::chunkText),
        )
        assertEquals(listOf(0, 1, 1, 1, 2), doc.chunks.map { it.paragraph })
        assertEquals(1, doc.paragraphs[1].firstChunk)
        assertEquals(3, doc.paragraphs[1].lastChunk)
    }

    @Test
    fun findsChunkFromSavedOffset() {
        val doc = DocText(sample)
        assertEquals(0, doc.chunkAtOffset(0))
        assertEquals(2, doc.chunkAtOffset(doc.chunks[2].start))
        assertEquals(2, doc.chunkAtOffset(doc.chunks[2].start + 3))
        assertEquals(doc.chunks.lastIndex, doc.chunkAtOffset(100_000))
    }

    @Test
    fun splitsVeryLongSentencesAtWords() {
        val doc = DocText("word ".repeat(1000).trim() + ".", maxChunkLength = 600)
        assertTrue(doc.chunks.size > 1)
        doc.chunks.indices.forEach { i ->
            val t = doc.chunkText(i)
            assertTrue(t.length <= 600)
            assertTrue(t.startsWith("word"))
        }
    }

    @Test
    fun emptyDocumentHasNoChunks() {
        val doc = DocText("  \n\n ")
        assertEquals(0, doc.chunks.size)
        assertEquals(0, doc.chunkAtOffset(5))
    }
}
