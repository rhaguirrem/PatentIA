package com.patentia.services

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class PlateRecognition(
    val plates: List<String>,
    val rawText: String,
)

class PlateRecognizer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val platePattern = Regex("\\b[A-Z0-9]{5,10}\\b")

    suspend fun recognize(context: Context, imageUri: Uri): PlateRecognition {
        val inputImage = InputImage.fromFilePath(context, imageUri)
        val result = recognizer.process(inputImage).await()
        val rawText = result.text.orEmpty()
        return PlateRecognition(
            plates = extractPlateCandidates(rawText),
            rawText = rawText,
        )
    }

    private fun extractPlateCandidates(rawText: String): List<String> {
        val cleaned = rawText.uppercase().replace(Regex("[^A-Z0-9\\s]"), " ")
        val individualCandidates = platePattern.findAll(cleaned)
            .map { it.value }
            .filter { it.length in 5..10 }

        val tokens = cleaned.split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val mergedPairs = tokens.windowed(size = 2, step = 1, partialWindows = false)
            .map { it.joinToString(separator = "") }
            .filter { it.length in 5..10 && it.all(Char::isLetterOrDigit) }

        return (individualCandidates + mergedPairs)
            .map { it.replace(" ", "") }
            .filter { it.any(Char::isLetter) && it.any(Char::isDigit) }
            .distinct()
            .sortedByDescending { it.length }
            .toList()
    }
}