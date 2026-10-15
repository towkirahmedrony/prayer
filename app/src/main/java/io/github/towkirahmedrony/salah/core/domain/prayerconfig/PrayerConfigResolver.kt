package io.github.towkirahmedrony.salah.core.domain.prayerconfig

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.PrayerCalculationProfile
import io.github.towkirahmedrony.salah.core.model.PrayerConfigOverrides
import io.github.towkirahmedrony.salah.core.model.PrayerSettings

/** Which layer of the resolution produced the effective calculation profile. */
enum class PrayerConfigOriginKind {
    /** Nothing remote applies: the profile is the one bundled with the app. */
    BUNDLED_DEFAULT,

    /** The published Bangladesh-wide default applies. */
    PUBLISHED_GLOBAL,

    /** A published override for the user's own division, district or upazila applies. */
    PUBLISHED_AREA,

    /** The user has explicitly chosen at least one field, so it (or they) win. */
    USER_OVERRIDE,
}

/**
 * Where the effective calculation profile came from, for the UI and diagnostics.
 *
 * A plain value with no reference to a database or a payload: it names a *kind* of origin and, when
 * relevant, the revision and area that produced it. No raw configuration content is ever carried here.
 */
data class PrayerConfigOrigin(
    val kind: PrayerConfigOriginKind,
    val revisionId: String? = null,
    val revisionNumber: Long? = null,
    val areaId: String? = null,
    val scope: PrayerConfigurationScope? = null,

    /** The non-user layer a user override was layered on top of, or `null` when [kind] is not a user override. */
    val baseKind: PrayerConfigOriginKind? = null,
) {
    companion object {
        val BUNDLED: PrayerConfigOrigin = PrayerConfigOrigin(PrayerConfigOriginKind.BUNDLED_DEFAULT)
    }
}

/** The calculation input actually handed to the engine, together with where it came from. */
data class EffectivePrayerConfig(
    val profile: PrayerCalculationProfile,
    val origin: PrayerConfigOrigin,
)

/**
 * The one place the precedence rule is applied.
 *
 * **The order, deepest-first:**
 *
 * ```text
 * explicit user override
 *   → applicable upazila/thana configuration
 *   → district configuration
 *   → division configuration
 *   → global default
 *   → bundled default
 * ```
 *
 * The user override is *field-level*: a field the user owns keeps the user's value even when a remote
 * configuration supplies that field, while every other field comes from the remote chain. That is what
 * lets a user correct only Asr without losing a district's published Fajr and Isha settings.
 *
 * This class is pure and synchronous — no clock, no file, no coroutine — so every branch of the precedence
 * rule is directly testable. It never touches the engine: it produces a [PrayerCalculationProfile], which
 * is the exact input the existing provider already consumes.
 */
class PrayerConfigResolver {

    /**
     * Resolves the effective profile for [area] from the active [snapshot] and the user's stored [settings]
     * and [overrides].
     *
     * @param snapshot the accepted remote configuration, or `null` when none is stored.
     * @param settings the user's calculation settings as persisted.
     * @param overrides the fields the user owns.
     * @param area the user's saved administrative area, or `null` when none is stored.
     */
    fun resolve(
        snapshot: PrayerConfigurationSnapshot?,
        settings: PrayerSettings,
        overrides: PrayerConfigOverrides,
        area: BangladeshAreaRef?,
    ): EffectivePrayerConfig {
        val base = baseProfile(snapshot, area)
        val profile = PrayerCalculationProfile(
            method = if (overrides.contains(PrayerConfigOverrides.Field.METHOD)) {
                settings.method
            } else {
                base.profile.method
            },
            asrMethod = if (overrides.contains(PrayerConfigOverrides.Field.ASR_METHOD)) {
                settings.asrMethod
            } else {
                base.profile.asrMethod
            },
            highLatitudeRule = if (overrides.contains(PrayerConfigOverrides.Field.HIGH_LATITUDE_RULE)) {
                settings.highLatitudeRule
            } else {
                base.profile.highLatitudeRule
            },
            adjustmentsMinutes = if (overrides.contains(PrayerConfigOverrides.Field.ADJUSTMENTS)) {
                settings.adjustmentsMinutes.filterValues { it != 0 }
            } else {
                base.profile.adjustmentsMinutes
            },
        )

        val origin = if (overrides.hasAny) {
            PrayerConfigOrigin(
                kind = PrayerConfigOriginKind.USER_OVERRIDE,
                revisionId = base.origin.revisionId,
                revisionNumber = base.origin.revisionNumber,
                areaId = base.origin.areaId,
                scope = base.origin.scope,
                baseKind = base.origin.kind,
            )
        } else {
            base.origin
        }

        return EffectivePrayerConfig(profile = profile, origin = origin)
    }

    /**
     * The remote-or-bundled layer, before any user override is layered on top.
     *
     * [area] is a hierarchy of ids, so each candidate override is checked against the scope its own id
     * implies: an upazila override only applies to the user's upazila, never to their district. A
     * configuration that has no override for an area simply falls through to the next broader layer.
     */
    private fun baseProfile(
        snapshot: PrayerConfigurationSnapshot?,
        area: BangladeshAreaRef?,
    ): EffectivePrayerConfig {
        if (snapshot == null) {
            return EffectivePrayerConfig(
                profile = PrayerSettings.DEFAULT.toProfile(),
                origin = PrayerConfigOrigin.BUNDLED,
            )
        }

        if (area != null) {
            area.upazilaId?.let { id ->
                snapshot.overrideFor(id)
                    ?.takeIf { it.scope == PrayerConfigurationScope.UPAZILA }
                    ?.let { override ->
                        return EffectivePrayerConfig(
                            profile = override.profile,
                            origin = areaOrigin(snapshot, override),
                        )
                    }
            }
            snapshot.overrideFor(area.districtId)
                ?.takeIf { it.scope == PrayerConfigurationScope.DISTRICT }
                ?.let { override ->
                    return EffectivePrayerConfig(
                        profile = override.profile,
                        origin = areaOrigin(snapshot, override),
                    )
                }
            snapshot.overrideFor(area.divisionId)
                ?.takeIf { it.scope == PrayerConfigurationScope.DIVISION }
                ?.let { override ->
                    return EffectivePrayerConfig(
                        profile = override.profile,
                        origin = areaOrigin(snapshot, override),
                    )
                }
        }

        return EffectivePrayerConfig(
            profile = snapshot.globalProfile,
            origin = PrayerConfigOrigin(
                kind = PrayerConfigOriginKind.PUBLISHED_GLOBAL,
                revisionId = snapshot.revisionId,
                revisionNumber = snapshot.revisionNumber,
            ),
        )
    }

    private fun areaOrigin(
        snapshot: PrayerConfigurationSnapshot,
        override: PrayerAreaOverride,
    ): PrayerConfigOrigin = PrayerConfigOrigin(
        kind = PrayerConfigOriginKind.PUBLISHED_AREA,
        revisionId = snapshot.revisionId,
        revisionNumber = snapshot.revisionNumber,
        areaId = override.areaId,
        scope = override.scope,
    )
}
