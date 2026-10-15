package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerName

/**
 * Writes a snapshot back to the canonical JSON payload the parser reads.
 *
 * **Why a writer exists.** The local store keeps the accepted configuration in the same shape it arrived
 * in, so a stored payload is self-describing and is re-parsed by the same [PrayerConfigurationParser] that
 * reads a downloaded one — there is one format and one parser, not a second on-disk encoding. The output is
 * canonical — keys in a fixed order, area overrides and adjustments sorted — so the same snapshot always
 * produces byte-identical text and therefore an identical checksum.
 */
object PrayerConfigurationWriter {

    fun toJson(snapshot: PrayerConfigurationSnapshot): String = buildString {
        append('{')
        appendString(KEY_CONFIGURATION_ID, snapshot.configurationId)
        append(',')
        appendString(KEY_REVISION_ID, snapshot.revisionId)
        append(',')
        appendNumber(KEY_REVISION_NUMBER, snapshot.revisionNumber)
        append(',')
        appendNumber(KEY_SCHEMA_VERSION, snapshot.schemaVersion.toLong())
        append(',')
        append("\"$KEY_GLOBAL\":")
        appendProfile(snapshot.globalProfile)
        append(',')
        append("\"$KEY_AREAS\":[")
        snapshot.areaOverrides.values
            .sortedBy { it.areaId }
            .forEachIndexed { index, override ->
                if (index > 0) append(',')
                append('{')
                appendString(KEY_AREA_ID, override.areaId)
                append(',')
                appendString(KEY_SCOPE, override.scope.name)
                append(',')
                appendProfileFields(override.profile)
                append('}')
            }
        append("]}")
    }

    private fun StringBuilder.appendProfile(profile: PrayerCalculationProfile) {
        append('{')
        appendProfileFields(profile)
        append('}')
    }

    private fun StringBuilder.appendProfileFields(profile: PrayerCalculationProfile) {
        appendString(KEY_METHOD, profile.method.name)
        append(',')
        appendString(KEY_ASR_METHOD, profile.asrMethod.name)
        append(',')
        appendString(KEY_HIGH_LATITUDE_RULE, profile.highLatitudeRule.name)
        append(',')
        append("\"$KEY_ADJUSTMENTS\":{")
        PrayerName.entries
            .mapNotNull { prayer -> profile.adjustmentsMinutes[prayer]?.takeIf { it != 0 }?.let { prayer to it } }
            .forEachIndexed { index, (prayer, minutes) ->
                if (index > 0) append(',')
                appendNumber(prayer.name, minutes.toLong())
            }
        append('}')
    }

    private fun StringBuilder.appendString(key: String, value: String) {
        append('"').append(key).append("\":")
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.isISOControl()) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun StringBuilder.appendNumber(key: String, value: Long) {
        append('"').append(key).append("\":").append(value)
    }

    private const val KEY_CONFIGURATION_ID = "configuration_id"
    private const val KEY_REVISION_ID = "revision_id"
    private const val KEY_REVISION_NUMBER = "revision_number"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_GLOBAL = "global"
    private const val KEY_AREAS = "areas"
    private const val KEY_AREA_ID = "area_id"
    private const val KEY_SCOPE = "scope"
    private const val KEY_METHOD = "method"
    private const val KEY_ASR_METHOD = "asr_method"
    private const val KEY_HIGH_LATITUDE_RULE = "high_latitude_rule"
    private const val KEY_ADJUSTMENTS = "adjustments"
}
