package io.github.towkirahmedrony.salah.core.domain.prayerconfig

/**
 * The prayer configuration currently stored on the device, with the revision identity it was accepted
 * under.
 *
 * The three identity values travel together because they are checked together on every read: the revision
 * id names the release, the revision number orders it against a candidate, and the checksum is the value
 * the stored payload must independently reproduce.
 */
data class ActivePrayerConfig(
    val snapshot: PrayerConfigurationSnapshot,
    val checksum: String,
) {
    val revisionId: String get() = snapshot.revisionId
    val revisionNumber: Long get() = snapshot.revisionNumber
}

/** The outcome of offering a snapshot for activation. A value, never an exception. */
sealed interface PrayerConfigActivationResult {

    /** A validated snapshot became the active configuration. */
    data class Activated(val active: ActivePrayerConfig) : PrayerConfigActivationResult

    /** The same revision, with the same contents, is already active. */
    data class AlreadyActive(val active: ActivePrayerConfig) : PrayerConfigActivationResult

    /** The candidate was refused. The active configuration is unchanged. */
    data class Rejected(val problems: List<PrayerConfigProblem>) : PrayerConfigActivationResult

    /** The candidate passed every check but the local store could not commit it. */
    data class Failed(val detail: String) : PrayerConfigActivationResult
}

/**
 * The local prayer-configuration store, as the synchroniser and the applier see it.
 *
 * **Why a port rather than the concrete store.** The synchroniser lives in `core.domain` and must not
 * depend on a filesystem implementation; the interface is exactly what it needs — read what is active,
 * offer a candidate — and nothing else. It deliberately has no method that could touch user preferences,
 * the stored location or the notification settings, so a failed synchronisation cannot disturb any of them.
 */
interface ActivePrayerConfigStore {

    /** The configuration currently stored, or `null` when none has been accepted. Never throws. */
    suspend fun active(): ActivePrayerConfig?

    /**
     * Offers [snapshot] for activation.
     *
     * The store enforces the revision rules itself — a reused revision id with different content, or a
     * revision that is not newer than the accepted one, is refused here as well as in the synchroniser, so
     * the rule holds even if a caller forgets it. The caller is expected to have already verified the
     * downloaded payload against the published checksum; the store independently checksums the snapshot it
     * persists so a corrupted read is detected later.
     */
    suspend fun activate(snapshot: PrayerConfigurationSnapshot): PrayerConfigActivationResult
}
