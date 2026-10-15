package io.github.towkirahmedrony.salah.core.domain.dataset

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef

/**
 * The local dataset store, as the remote synchroniser sees it.
 *
 * **Why a port rather than the concrete store.** The synchroniser lives in `core.domain` and must not
 * depend on `data.dataset.LocalDatasetStore`, which is a filesystem implementation; that would make the
 * domain depend on the data layer and make the synchroniser untestable without real files. This interface
 * is exactly the two operations the synchroniser needs — read what is active, offer a candidate — and
 * nothing else.
 *
 * `LocalDatasetStore` implements it unchanged, so **no second activation system exists**: the remote path
 * calls the same staging-and-rename commit that Phase 1 built and tested. The interface deliberately has
 * no method that could clear the stored location or write user data.
 */
interface ActiveDatasetStore {

    /** The dataset currently in force, or `null` when none can be produced. */
    suspend fun activeDataset(): ActiveDataset?

    /**
     * Offers [payload] for activation.
     *
     * @param storedArea the area of the user's saved location, or `null` when none is stored. A candidate
     *   that does not contain it is rejected rather than activated.
     * @param expectedChecksum the checksum published for this payload, when the caller has one.
     */
    suspend fun activate(
        payload: DatasetPayload,
        storedArea: BangladeshAreaRef? = null,
        expectedChecksum: String? = null,
    ): DatasetActivationResult
}
