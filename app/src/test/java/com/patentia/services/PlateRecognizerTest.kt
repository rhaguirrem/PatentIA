package com.patentia.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateRecognizerTest {

    private val recognizer = PlateRecognizer()

    @Test
    fun `rankPlateCandidates prefers plate over make and model noise`() {
        val ranked = recognizer.rankPlateCandidates(
            rawText = "MAZDA CX5 CXDL88",
            lines = listOf(
                RecognizedTextLine(text = "MAZDA"),
                RecognizedTextLine(text = "CX5"),
                RecognizedTextLine(text = "CXDL88"),
            ),
        )

        assertEquals("CXDL88", ranked.first())
        assertTrue("MAZDA should not be ranked as a plate", "MAZDA" !in ranked)
        assertTrue("CX5 should not be ranked as a plate", "CX5" !in ranked)
    }

    @Test
    fun `rankPlateCandidates normalizes common OCR digit letter swaps`() {
        val ranked = recognizer.rankPlateCandidates(
            rawText = "ABCDS8",
            lines = listOf(RecognizedTextLine(text = "ABCDS8")),
        )

        assertEquals("ABCD58", ranked.first())
    }
}