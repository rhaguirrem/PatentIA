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

    @Test
    fun `rankPlateCandidates rebuilds fragmented plate segments from OCR noise`() {
        val ranked = recognizer.rankPlateCandidates(
            rawText = "TOYOTA YARIS CX DL 88",
            lines = listOf(
                RecognizedTextLine(
                    text = "TOYOTA YARIS CX DL 88",
                    fragments = listOf("TOYOTA", "YARIS", "CX", "DL", "88"),
                )
            ),
        )

        assertEquals("CXDL88", ranked.first())
    }

    @Test
    fun `rankPlateCandidates rebuilds plates split into multiple short OCR tokens`() {
        val ranked = recognizer.rankPlateCandidates(
            rawText = "CX D L 88",
            lines = listOf(
                RecognizedTextLine(
                    text = "CX D L 88",
                    fragments = listOf("CX", "D", "L", "88"),
                )
            ),
        )

        assertEquals("CXDL88", ranked.first())
    }
}