package io.github.towkirahmedrony.salah.core.domain.qibla

/**
 * A platform location provider, named without reference to Android's string constants.
 *
 * `FUSED` is deliberately absent: it comes from Google Play Services, and Salah has no Play
 * Services dependency. `LocationManager` is part of the framework, works offline, needs no account,
 * and is more than adequate for a single fix.
 */
enum class LocationSource {
    GPS,
    NETWORK,
    PASSIVE,
}

/**
 * Chooses which provider to ask, given how precise the user has allowed Salah to be.
 *
 * **Why the order differs by precision.** A Qibla bearing barely notices position error — a coarse
 * fix is off by ~0.02° even thousands of kilometres away — so when the user has granted only
 * approximate location there is no reason to power up the GNSS receiver. Asking the network
 * provider first is therefore both the cheaper and the more proportionate choice, and GPS remains
 * the last fallback rather than the first preference. When the user has granted precise location
 * they have asked for the better fix, so GPS leads.
 *
 * The preference is an *ordering*, not a fixed choice: a device indoors may have no GPS fix at all
 * while its network provider works perfectly, and a device with no SIM has the reverse. Only
 * candidates the platform reports as enabled are considered.
 */
object LocationProviderSelection {

    /** Candidate providers for [precision], best first. */
    fun candidates(precision: LocationPrecision): List<LocationSource> = when (precision) {
        LocationPrecision.PRECISE -> listOf(LocationSource.GPS, LocationSource.NETWORK, LocationSource.PASSIVE)
        LocationPrecision.APPROXIMATE -> listOf(LocationSource.NETWORK, LocationSource.PASSIVE, LocationSource.GPS)
    }

    /**
     * The first candidate the platform reports as enabled, or `null` when none is.
     *
     * A null answer is a real state — location switched off, or every provider unavailable — and the
     * caller reports it rather than falling through to a stale fix presented as current.
     */
    fun bestEnabled(
        precision: LocationPrecision,
        isEnabled: (LocationSource) -> Boolean,
    ): LocationSource? = candidates(precision).firstOrNull(isEnabled)
}
