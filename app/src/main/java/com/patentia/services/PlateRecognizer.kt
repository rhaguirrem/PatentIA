package com.patentia.services

import android.graphics.Rect
import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

data class PlateRecognition(
    val plates: List<String>,
    val rawText: String,
)

internal data class RecognizedTextLine(
    val text: String,
    val fragments: List<String> = emptyList(),
    val bounds: Rect? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
)

class PlateRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val tokenPattern = Regex("[A-Z0-9]{5,8}")
    private val strongPlatePatterns = listOf(
        Regex("^[A-Z]{4}\\d{2}$"),
        Regex("^[A-Z]{3}\\d{3}$"),
        Regex("^[A-Z]{2}\\d{4}$"),
        Regex("^[A-Z]{2}\\d{3}[A-Z]{1,2}$"),
    )
    private val knownVehicleTerms = setOf(
        "TOYOTA", "MAZDA", "CHEVROLET", "CHEVY", "HYUNDAI", "NISSAN", "KIA", "SUZUKI",
        "FORD", "BMW", "AUDI", "JEEP", "PEUGEOT", "RENAULT", "CITROEN", "HONDA",
        "MITSUBISHI", "MERCEDES", "BENZ", "VOLKSWAGEN", "SUBARU", "FIAT", "DODGE",
        "CHERY", "HAVAL", "TESLA", "BYD", "MG", "JAC", "CX5", "CX3", "CX30",
        "BT50", "HILUX", "RANGER", "SWIFT", "SPARK", "GOLF", "JETTA", "YARIS",
        "COROLLA", "ELANTRA", "ACCENT", "CERATO", "SPORTAGE", "TUCSON", "NAVARA",
        "FORTUNER", "FRONTIER", "PATROL",
    )

    suspend fun recognize(context: Context, imageUri: Uri): PlateRecognition {
        val inputImage = InputImage.fromFilePath(context, imageUri)
        val result = recognizer.process(inputImage).await()
        val rawText = result.text.orEmpty()
        return PlateRecognition(
            plates = rankPlateCandidates(
                rawText = rawText,
                lines = result.toRecognizedLines(inputImage.width, inputImage.height),
            ),
            rawText = rawText,
        )
    }

    internal fun rankPlateCandidates(
        rawText: String,
        lines: List<RecognizedTextLine> = emptyList(),
    ): List<String> {
        val scoredCandidates = linkedMapOf<String, Int>()

        lines.forEach { line ->
            val lineBoost = scoreLinePlacement(line)
            extractCandidatesFromSnippet(line.text).forEach { candidate ->
                registerCandidate(scoredCandidates, candidate, 30 + lineBoost)
            }

            line.fragments.forEach { fragment ->
                extractCandidatesFromSnippet(fragment).forEach { candidate ->
                    registerCandidate(scoredCandidates, candidate, 18 + lineBoost)
                }
            }

            line.fragments.windowed(size = 2, step = 1, partialWindows = false)
                .map { it.joinToString(separator = "") }
                .forEach { merged ->
                    extractCandidatesFromSnippet(merged).forEach { candidate ->
                        registerCandidate(scoredCandidates, candidate, 16 + lineBoost)
                    }
                }
        }

        extractCandidatesFromSnippet(rawText).forEach { candidate ->
            registerCandidate(scoredCandidates, candidate, 10)
        }

        val ranked = scoredCandidates.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

        val topScore = ranked.firstOrNull()?.second ?: return emptyList()
        return ranked
            .filter { (_, score) -> score >= maxOf(120, topScore - 18) }
            .map { it.first }
            .take(3)
    }

    private fun extractCandidatesFromSnippet(text: String): List<String> {
        val cleaned = normalizeText(text)
        if (cleaned.isBlank()) {
            return emptyList()
        }

        val directTokens = tokenPattern.findAll(cleaned).map { it.value }.toList()
        val splitTokens = cleaned.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .flatMap { tokenPattern.findAll(it).map { match -> match.value }.toList() }

        return (directTokens + splitTokens)
            .map(::normalizePlateLikeToken)
            .filterNotNull()
            .distinct()
    }

    private fun registerCandidate(
        scores: MutableMap<String, Int>,
        candidate: String,
        sourceScore: Int,
    ) {
        val normalizedCandidates = buildList {
            add(candidate)
            addAll(generatePatternVariants(candidate))
        }.distinct()

        normalizedCandidates.forEachIndexed { index, normalized ->
            val candidateScore = scoreCandidate(normalized, sourceScore - (index * 4)) ?: return@forEachIndexed
            val current = scores[normalized]
            if (current == null || candidateScore > current) {
                scores[normalized] = candidateScore
            }
        }
    }

    private fun scoreCandidate(candidate: String, sourceScore: Int): Int? {
        if (candidate.length !in 5..8) {
            return null
        }
        if (candidate in knownVehicleTerms) {
            return null
        }
        if (!candidate.any(Char::isLetter) || !candidate.any(Char::isDigit)) {
            return null
        }

        val shapeScore = when {
            strongPlatePatterns[0].matches(candidate) -> 220
            strongPlatePatterns[1].matches(candidate) -> 205
            strongPlatePatterns[2].matches(candidate) -> 195
            strongPlatePatterns[3].matches(candidate) -> 180
            candidate.matches(Regex("^[A-Z]{2,5}\\d{2,4}$")) -> 145
            candidate.matches(Regex("^[A-Z]{1,3}\\d{2,4}[A-Z]{1,2}$")) -> 130
            else -> return null
        }

        val repetitionPenalty = if (candidate.windowed(3).any { window -> window.toSet().size == 1 }) 20 else 0
        val vowelPenalty = if (candidate.count { it in "AEIOU" } >= 3) 10 else 0
        val lengthBonus = when (candidate.length) {
            6 -> 18
            5, 7 -> 10
            else -> 0
        }

        return sourceScore + shapeScore + lengthBonus - repetitionPenalty - vowelPenalty
    }

    private fun generatePatternVariants(candidate: String): List<String> {
        val patterns = listOf(
            listOf('L', 'L', 'L', 'L', 'D', 'D'),
            listOf('L', 'L', 'L', 'D', 'D', 'D'),
            listOf('L', 'L', 'D', 'D', 'D', 'D'),
        )

        val matchingPatterns = patterns.filter { it.size == candidate.length }
        if (matchingPatterns.isEmpty()) {
            return emptyList()
        }

        return matchingPatterns.flatMap { pattern ->
            val normalized = candidate.mapIndexed { index, character ->
                when (pattern[index]) {
                    'L' -> letterNormalization(character)
                    else -> digitNormalization(character)
                }
            }.joinToString(separator = "")

            val letterAlternatives = pattern.indices.mapNotNull { index ->
                if (pattern[index] != 'L') return@mapNotNull null
                alternateLetter(candidate[index])?.let { replacement ->
                    candidate.replaceRange(index, index + 1, replacement.toString())
                }
            }
            val digitAlternatives = pattern.indices.mapNotNull { index ->
                if (pattern[index] != 'D') return@mapNotNull null
                alternateDigit(candidate[index])?.let { replacement ->
                    candidate.replaceRange(index, index + 1, replacement.toString())
                }
            }

            listOf(normalized) + letterAlternatives + digitAlternatives
        }.filter { it != candidate }
    }

    private fun normalizeText(text: String): String {
        return text.uppercase().replace(Regex("[^A-Z0-9\\s]"), " ")
    }

    private fun normalizePlateLikeToken(token: String): String? {
        val cleaned = token.replace(" ", "")
        return cleaned.takeIf {
            it.length in 5..8 && it.all(Char::isLetterOrDigit) && it.any(Char::isLetter) && it.any(Char::isDigit)
        }
    }

    private fun scoreLinePlacement(line: RecognizedTextLine): Int {
        val bounds = line.bounds ?: return 0
        val imageWidth = line.imageWidth ?: return 0
        val imageHeight = line.imageHeight ?: return 0
        if (imageWidth <= 0 || imageHeight <= 0) {
            return 0
        }

        val centerX = bounds.exactCenterX() / imageWidth
        val centerY = bounds.exactCenterY() / imageHeight
        val aspectRatio = bounds.width().toFloat() / bounds.height().coerceAtLeast(1)

        val horizontalScore = ((1f - abs(0.5f - centerX) * 2f).coerceAtLeast(0f) * 12f).toInt()
        val verticalScore = ((1f - abs(0.62f - centerY) * 1.6f).coerceAtLeast(0f) * 14f).toInt()
        val aspectScore = when {
            aspectRatio >= 3.2f -> 12
            aspectRatio >= 2.2f -> 8
            aspectRatio >= 1.5f -> 4
            else -> 0
        }

        return horizontalScore + verticalScore + aspectScore
    }

    private fun letterNormalization(character: Char): Char = when (character) {
        '0' -> 'O'
        '1' -> 'I'
        '2' -> 'Z'
        '3' -> 'B'
        '4' -> 'A'
        '5' -> 'S'
        '6' -> 'G'
        '7' -> 'Z'
        '8' -> 'B'
        else -> character
    }

    private fun digitNormalization(character: Char): Char = when (character) {
        'O', 'Q', 'D' -> '0'
        'I', 'L' -> '1'
        'Z' -> '2'
        'S' -> '5'
        'G' -> '6'
        'B' -> '8'
        else -> character
    }

    private fun alternateLetter(character: Char): Char? = when (character) {
        'K' -> 'X'
        'X' -> 'K'
        'O' -> 'Q'
        'Q' -> 'O'
        else -> null
    }

    private fun alternateDigit(character: Char): Char? = when (character) {
        '3' -> '8'
        '8' -> '3'
        '6' -> '8'
        else -> null
    }

    private fun Text.toRecognizedLines(imageWidth: Int, imageHeight: Int): List<RecognizedTextLine> {
        return textBlocks.flatMap { block ->
            block.lines.map { line ->
                RecognizedTextLine(
                    text = line.text,
                    fragments = line.elements.map { it.text },
                    bounds = line.boundingBox,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                )
            }
        }
    }
}