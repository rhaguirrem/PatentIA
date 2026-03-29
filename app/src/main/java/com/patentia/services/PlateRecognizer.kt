package com.patentia.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max

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

private data class RecognitionPass(
    val rawText: String,
    val lines: List<RecognizedTextLine>,
    val sourceBoost: Int,
)

private data class FocusCrop(
    val bitmap: Bitmap,
    val sourceBoost: Int,
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
        val originalImage = InputImage.fromFilePath(context, imageUri)
        val originalResult = recognizer.process(originalImage).await()
        val recognitionPasses = mutableListOf(
            RecognitionPass(
                rawText = originalResult.text.orEmpty(),
                lines = originalResult.toRecognizedLines(originalImage.width, originalImage.height),
                sourceBoost = 0,
            )
        )

        decodeBitmap(context, imageUri)?.let { decodedBitmap ->
            val normalizedBitmap = scaleBitmap(decodedBitmap)
            val enhancedBitmap = enhanceBitmap(normalizedBitmap)

            recognitionPasses += runAdditionalPass(enhancedBitmap, sourceBoost = 10)
            buildFocusCrops(enhancedBitmap).forEach { focusCrop ->
                recognitionPasses += runAdditionalPass(focusCrop.bitmap, sourceBoost = focusCrop.sourceBoost)
            }
        }

        val rawText = recognitionPasses.joinToString(separator = "\n") { it.rawText }
            .trim()
        return PlateRecognition(
            plates = rankPlateCandidates(recognitionPasses),
            rawText = rawText,
        )
    }

    internal fun rankPlateCandidates(
        rawText: String,
        lines: List<RecognizedTextLine> = emptyList(),
    ): List<String> {
        return rankPlateCandidates(
            listOf(
                RecognitionPass(
                    rawText = rawText,
                    lines = lines,
                    sourceBoost = 0,
                )
            )
        )
    }

    private fun rankPlateCandidates(
        passes: List<RecognitionPass>,
    ): List<String> {
        val scoredCandidates = linkedMapOf<String, Int>()

        passes.forEach { pass ->
            val passScores = scoreCandidates(
                rawText = pass.rawText,
                lines = pass.lines,
                sourceBoost = pass.sourceBoost,
            )
            passScores.forEach { (candidate, score) ->
                val mergedScore = score + (if (scoredCandidates.containsKey(candidate)) 24 else 0)
                scoredCandidates[candidate] = max(scoredCandidates[candidate] ?: Int.MIN_VALUE, mergedScore)
            }
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

    private fun scoreCandidates(
        rawText: String,
        lines: List<RecognizedTextLine>,
        sourceBoost: Int,
    ): Map<String, Int> {
        val scoredCandidates = linkedMapOf<String, Int>()

        lines.forEach { line ->
            val lineBoost = scoreLinePlacement(line)
            extractCandidatesFromSnippet(line.text).forEach { candidate ->
                registerCandidate(scoredCandidates, candidate, 30 + lineBoost + sourceBoost)
            }

            line.fragments.forEach { fragment ->
                extractCandidatesFromSnippet(fragment).forEach { candidate ->
                    registerCandidate(scoredCandidates, candidate, 18 + lineBoost + sourceBoost)
                }
            }

            line.fragments.windowed(size = 2, step = 1, partialWindows = false)
                .map { it.joinToString(separator = "") }
                .forEach { merged ->
                    extractCandidatesFromSnippet(merged).forEach { candidate ->
                        registerCandidate(scoredCandidates, candidate, 16 + lineBoost + sourceBoost)
                    }
                }
        }

        extractCandidatesFromSnippet(rawText).forEach { candidate ->
            registerCandidate(scoredCandidates, candidate, 10 + sourceBoost)
        }

        return scoredCandidates
    }

    private fun extractCandidatesFromSnippet(text: String): List<String> {
        val cleaned = normalizeText(text)
        if (cleaned.isBlank()) {
            return emptyList()
        }

        val normalizedSegments = cleaned.split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val directTokens = tokenPattern.findAll(cleaned).map { it.value }.toList()
        val splitTokens = normalizedSegments
            .flatMap { tokenPattern.findAll(it).map { match -> match.value }.toList() }
        val segmentedTokens = buildSegmentCandidates(normalizedSegments)

        return (directTokens + splitTokens + segmentedTokens)
            .map(::normalizePlateLikeToken)
            .filterNotNull()
            .distinct()
    }

    private fun buildSegmentCandidates(segments: List<String>): List<String> {
        if (segments.isEmpty()) {
            return emptyList()
        }

        val candidates = mutableListOf<String>()
        segments.indices.forEach { startIndex ->
            val builder = StringBuilder()
            for (endIndex in startIndex until segments.size) {
                builder.append(segments[endIndex])
                val merged = builder.toString()
                if (merged.length > 8) {
                    break
                }
                if (merged.length >= 5 && merged.any(Char::isLetter) && merged.any(Char::isDigit)) {
                    candidates += merged
                }
            }
        }
        return candidates
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

    private suspend fun runAdditionalPass(
        bitmap: Bitmap,
        sourceBoost: Int,
    ): RecognitionPass {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(inputImage).await()
        return RecognitionPass(
            rawText = result.text.orEmpty(),
            lines = result.toRecognizedLines(bitmap.width, bitmap.height),
            sourceBoost = sourceBoost,
        )
    }

    private fun decodeBitmap(context: Context, imageUri: Uri): Bitmap? {
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, imageUri)) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        }.getOrNull()
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int = 1600): Bitmap {
        val largestDimension = max(bitmap.width, bitmap.height)
        if (largestDimension <= maxDimension) {
            return bitmap
        }

        val scale = maxDimension.toFloat() / largestDimension.toFloat()
        val scaledWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    }

    private fun enhanceBitmap(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val saturationMatrix = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.45f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        saturationMatrix.postConcat(contrastMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(saturationMatrix)
            isFilterBitmap = true
        }

        Canvas(output).drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    private fun buildFocusCrops(bitmap: Bitmap): List<FocusCrop> {
        if (bitmap.width < 80 || bitmap.height < 80) {
            return emptyList()
        }

        return listOfNotNull(
            cropFocusArea(
                bitmap = bitmap,
                widthFraction = 0.82f,
                heightFraction = 0.38f,
                topFraction = 0.34f,
                sourceBoost = 18,
            ),
            cropFocusArea(
                bitmap = bitmap,
                widthFraction = 0.68f,
                heightFraction = 0.24f,
                topFraction = 0.56f,
                sourceBoost = 26,
            ),
            cropFocusArea(
                bitmap = bitmap,
                widthFraction = 0.54f,
                heightFraction = 0.22f,
                topFraction = 0.56f,
                horizontalBias = -0.12f,
                sourceBoost = 24,
            ),
            cropFocusArea(
                bitmap = bitmap,
                widthFraction = 0.54f,
                heightFraction = 0.22f,
                topFraction = 0.56f,
                horizontalBias = 0.12f,
                sourceBoost = 24,
            ),
        )
    }

    private fun cropFocusArea(
        bitmap: Bitmap,
        widthFraction: Float,
        heightFraction: Float,
        topFraction: Float,
        horizontalBias: Float = 0f,
        sourceBoost: Int,
    ): FocusCrop? {
        val cropWidth = (bitmap.width * widthFraction).toInt().coerceAtLeast(1)
        val cropHeight = (bitmap.height * heightFraction).toInt().coerceAtLeast(1)
        if (cropWidth >= bitmap.width || cropHeight >= bitmap.height) {
            return null
        }

        val centeredLeft = ((bitmap.width - cropWidth) / 2f) + (bitmap.width * horizontalBias)
        val left = centeredLeft.toInt().coerceIn(0, bitmap.width - cropWidth)
        val top = (bitmap.height * topFraction).toInt().coerceIn(0, bitmap.height - cropHeight)
        val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        return FocusCrop(
            bitmap = upscaleBitmap(croppedBitmap),
            sourceBoost = sourceBoost,
        )
    }

    private fun upscaleBitmap(bitmap: Bitmap, minWidth: Int = 1400): Bitmap {
        if (bitmap.width >= minWidth) {
            return bitmap
        }

        val scale = minWidth.toFloat() / bitmap.width.toFloat().coerceAtLeast(1f)
        val scaledWidth = (bitmap.width * scale).toInt().coerceAtLeast(bitmap.width)
        val scaledHeight = (bitmap.height * scale).toInt().coerceAtLeast(bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
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