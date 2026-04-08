package com.patentia.ui

import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

internal data class PatenteChileLookup(
    val plateNumber: String,
    val ownerName: String? = null,
    val ownerRut: String? = null,
    val vehicleMake: String? = null,
    val vehicleModel: String? = null,
    val vehicleYear: String? = null,
    val vehicleColor: String? = null,
) {
    val ownerChipLabel: String?
        get() = listOfNotNull(ownerName, ownerRut).takeIf { it.isNotEmpty() }?.joinToString(" • ")

    val vehicleChipLabel: String?
        get() = listOfNotNull(vehicleMake, vehicleModel, vehicleYear).takeIf { it.isNotEmpty() }?.joinToString(" ")

    val colorChipLabel: String?
        get() = vehicleColor

    fun hasMeaningfulData(): Boolean {
        return !ownerName.isNullOrBlank() ||
            !ownerRut.isNullOrBlank() ||
            !vehicleMake.isNullOrBlank() ||
            !vehicleModel.isNullOrBlank() ||
            !vehicleYear.isNullOrBlank() ||
            !vehicleColor.isNullOrBlank()
    }
}

private data class PatenteChileLookupPayload(
    val plateNumber: String?,
    val ownerName: String?,
    val ownerRut: String?,
    val vehicleMake: String?,
    val vehicleModel: String?,
    val vehicleYear: String?,
    val vehicleColor: String?,
    val noResult: Boolean,
    val rawText: String?,
    val rawHtml: String?,
    val scriptPayloads: List<String>,
    val labeledPairs: List<Pair<String, String>>,
)

internal fun parsePatenteChileLookupResult(rawResult: String?): PatenteChileLookup? {
    if (rawResult.isNullOrBlank() || rawResult == "null") {
        return null
    }

    val decodedResult = runCatching {
        JSONArray("[$rawResult]").getString(0)
    }.getOrNull() ?: return null

    return parsePatenteChileLookupPayload(decodedResult)
}

internal fun parsePatenteChileLookupPayload(decodedResult: String): PatenteChileLookup? {
    if (decodedResult.isBlank()) {
        return null
    }

    val jsonObject = runCatching { JSONObject(decodedResult) }.getOrNull() ?: return null
    val payload = jsonObject.toLookupPayload()
    if (payload.noResult) {
        return null
    }
    val rawText = payload.rawText.orEmpty()
    val rawHtml = payload.rawHtml.orEmpty()
    val scriptPayload = payload.scriptPayloads.joinToString("\n")
    val searchCorpus = listOf(rawText, rawHtml, scriptPayload)
        .filter { it.isNotBlank() }
        .joinToString("\n")
    val lines = rawText
        .split(Regex("\\n+"))
        .map(::normalizeLookupValue)
        .filter { it.isNotBlank() }

    val lookup = PatenteChileLookup(
        plateNumber = normalizeLookupValue(payload.plateNumber).ifBlank { return null },
        ownerName = resolveLookupField(
            directValue = payload.ownerName,
            pairValue = payload.findPairValue(
                "nombre del propietario",
                "nombre del titular",
                "propietario",
                "dueño",
                "dueno",
                "titular",
            ),
            lines = lines,
            corpus = searchCorpus,
            lineLabels = listOf(
                "nombre del propietario",
                "nombre del titular",
                "propietario",
                "dueño",
                "dueno",
                "titular",
            ),
            regexes = listOf(
                Regex("(?:nombre(?: completo)?(?: del)?(?: propietario| titular| dueño| dueno)?|propietario|dueño|dueno|titular)\\s*[:\\-]?\\s*([^\\n<]{3,})", RegexOption.IGNORE_CASE),
                Regex("\"(?:nombre(?:_completo)?|propietario|titular)\"\\s*:\\s*\"([^\"]{3,})\"", RegexOption.IGNORE_CASE),
            ),
        ),
        ownerRut = resolveLookupField(
            directValue = payload.ownerRut,
            pairValue = payload.findPairValue(
                "rut propietario",
                "rut dueño",
                "rut dueno",
                "rut titular",
                "rut",
            ),
            lines = lines,
            corpus = searchCorpus,
            lineLabels = listOf(
                "rut propietario",
                "rut dueño",
                "rut dueno",
                "rut titular",
                "rut",
            ),
            regexes = listOf(
                Regex("(?:rut(?: del)?(?: propietario| dueño| dueno| titular)?)\\s*[:\\-]?\\s*([^\\n<]{7,})", RegexOption.IGNORE_CASE),
                Regex("\"rut(?:_propietario|_titular)?\"\\s*:\\s*\"([^\"]{7,})\"", RegexOption.IGNORE_CASE),
                Regex("\\b\\d{1,2}\\.?\\d{3}\\.?\\d{3}-[\\dkK]\\b"),
            ),
            transform = { value -> normalizeRut(value) },
        ),
        vehicleMake = resolveLookupField(
            directValue = payload.vehicleMake,
            pairValue = payload.findPairValue("marca"),
            lines = lines,
            corpus = searchCorpus,
            lineLabels = listOf("marca"),
            regexes = listOf(
                Regex("\"marca\"\\s*:\\s*\"([^\",}{]{2,})\"", RegexOption.IGNORE_CASE),
                Regex("(?:marca)\\s*[:\\-]?\\s*([^\\n<\",}{]{2,})", RegexOption.IGNORE_CASE),
            ),
        ),
        vehicleModel = resolveLookupField(
            directValue = payload.vehicleModel,
            pairValue = payload.findPairValue("modelo"),
            lines = lines,
            corpus = searchCorpus,
            lineLabels = listOf("modelo"),
            regexes = listOf(
                Regex("\"modelo\"\\s*:\\s*\"([^\",}{]{2,})\"", RegexOption.IGNORE_CASE),
                Regex("(?:modelo)\\s*[:\\-]?\\s*([^\\n<\",}{]{2,})", RegexOption.IGNORE_CASE),
            ),
        ),
        vehicleYear = resolveLookupField(
            directValue = payload.vehicleYear,
            pairValue = payload.findPairValue("año", "ano"),
            lines = lines,
            corpus = searchCorpus,
            lineLabels = listOf("año", "ano"),
            regexes = listOf(
                Regex("\"(?:año|ano|year)\"\\s*:\\s*\"?(19\\d{2}|20\\d{2})\"?", RegexOption.IGNORE_CASE),
                Regex("(?:año|ano)\\s*[:\\-]?\\s*([^\\n<\",}{]{4,})", RegexOption.IGNORE_CASE),
                Regex("\\b(19\\d{2}|20\\d{2})\\b"),
            ),
        ),
        vehicleColor = resolveLookupField(
            directValue = payload.vehicleColor,
            pairValue = payload.findPairValue("color"),
            lines = lines,
            corpus = searchCorpus,
            lineLabels = listOf("color"),
            regexes = listOf(
                Regex("\"color\"\\s*:\\s*\"([^\",}{]{2,})\"", RegexOption.IGNORE_CASE),
                Regex("(?:color)\\s*[:\\-]?\\s*([^\\n<\",}{]{2,})", RegexOption.IGNORE_CASE),
            ),
            accept = ::isValidVehicleColorCandidate,
        ),
    )

    return lookup.takeIf { it.hasMeaningfulData() }
}

private fun JSONObject.toLookupPayload(): PatenteChileLookupPayload {
    return PatenteChileLookupPayload(
        plateNumber = optString("plateNumber").trim().ifBlank { null },
        ownerName = optString("ownerName").trim().ifBlank { null },
        ownerRut = optString("ownerRut").trim().ifBlank { null },
        vehicleMake = optString("vehicleMake").trim().ifBlank { null },
        vehicleModel = optString("vehicleModel").trim().ifBlank { null },
        vehicleYear = optString("vehicleYear").trim().ifBlank { null },
        vehicleColor = optString("vehicleColor").trim().ifBlank { null },
        noResult = optBoolean("noResult", false),
        rawText = optString("rawText").trim().ifBlank { null },
        rawHtml = optString("rawHtml").trim().ifBlank { null },
        scriptPayloads = optJSONArray("scriptPayloads").toStringList(),
        labeledPairs = optJSONArray("labeledPairs").toPairs(),
    )
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) {
        return emptyList()
    }
    return buildList {
        for (index in 0 until length()) {
            val value = optString(index).trim()
            if (value.isNotBlank()) {
                add(value)
            }
        }
    }
}

private fun JSONArray?.toPairs(): List<Pair<String, String>> {
    if (this == null) {
        return emptyList()
    }
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONArray(index) ?: continue
            val label = normalizeLookupValue(item.optString(0))
            val value = normalizeLookupValue(item.optString(1))
            if (label.isNotBlank() && value.isNotBlank()) {
                add(label to value)
            }
        }
    }
}

private fun PatenteChileLookupPayload.findPairValue(vararg labels: String): String? {
    val normalizedLabels = labels.map(::normalizeLookupKey)
    return labeledPairs.firstNotNullOfOrNull { (label, value) ->
        val normalizedLabel = normalizeLookupKey(label)
        if (normalizedLabels.any { normalizedLabel.contains(it) }) {
            value
        } else {
            null
        }
    }
}

private fun resolveLookupField(
    directValue: String?,
    pairValue: String?,
    lines: List<String>,
    corpus: String,
    lineLabels: List<String>,
    regexes: List<Regex>,
    transform: (String) -> String = ::normalizeLookupValue,
    accept: (String) -> Boolean = { true },
): String? {
    val candidates = listOfNotNull(
        directValue,
        pairValue,
        extractFromLines(lines, lineLabels),
        extractWithRegex(corpus, regexes),
    )

    return candidates
        .map(transform)
        .map(::cleanupLookupCandidate)
        .firstOrNull { it.isNotBlank() && accept(it) }
        ?.ifBlank { null }
}

private fun isValidVehicleColorCandidate(value: String): Boolean {
    val normalizedValue = normalizeLookupKey(value)
    if (normalizedValue.isBlank()) {
        return false
    }

    val cssIndicators = listOf(
        "display",
        "inline-block",
        "overflow",
        "visibility",
        "background",
        "font",
        "padding",
        "margin",
        "transparent",
    )

    return !value.contains(';') &&
        !value.contains('{') &&
        !value.contains('}') &&
        cssIndicators.none { normalizedValue.contains(it) }
}

private fun extractFromLines(lines: List<String>, labels: List<String>): String? {
    val normalizedLabels = labels.map(::normalizeLookupKey)
    lines.forEachIndexed { index, line ->
        val normalizedLine = normalizeLookupKey(line)
        normalizedLabels.forEach { label ->
            val inlineMatch = Regex("(?:^|\\b)" + Regex.escape(label) + "(?:\\b|$)\\s*[:\\-]?\\s*(.+)", RegexOption.IGNORE_CASE)
                .find(normalizedLine)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
            if (!inlineMatch.isNullOrBlank() && inlineMatch != label) {
                return line.substringAfterLast(':', line).substringAfterLast('-', line).trim().ifBlank { inlineMatch }
            }

            val matchesStandaloneLabel = normalizedLine == label || normalizedLine == "$label:" || normalizedLine == "$label -"
            if (matchesStandaloneLabel) {
                val nextValue = lines.drop(index + 1).firstOrNull { it.isNotBlank() } ?: return@forEach
                return nextValue
            }
        }
    }
    return null
}

private fun extractWithRegex(corpus: String, regexes: List<Regex>): String? {
    regexes.forEach { regex ->
        val match = regex.find(corpus) ?: return@forEach
        val value = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: match.value
        val cleaned = normalizeLookupValue(value)
        if (cleaned.isNotBlank()) {
            return cleaned
        }
    }
    return null
}

private fun cleanupLookupCandidate(value: String): String {
    val normalized = normalizeLookupValue(value)
        .removePrefix("=")
        .removePrefix(":")
        .removePrefix("-")
        .trim()

    return normalized
        .replace(Regex("^(propietario|dueño|dueno|titular|marca|modelo|año|ano|color|rut)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE), "")
        .trim()
}

private fun normalizeRut(value: String): String {
    val match = Regex("\\b\\d{1,2}\\.?\\d{3}\\.?\\d{3}-[\\dkK]\\b").find(value)
    return normalizeLookupValue(match?.value ?: value).uppercase()
}

private fun normalizeLookupValue(value: String?): String {
    return value
        .orEmpty()
        .replace("\u00A0", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun normalizeLookupKey(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return normalizeLookupValue(normalized).lowercase()
}