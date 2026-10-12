package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationDataset

/**
 * Access to Salah's bundled Bangladesh administrative geography.
 *
 * A port rather than a direct file read, for two reasons that both matter here:
 *
 *  - **It is testable without a device.** The parser and every rule that consumes the dataset are pure
 *    and tested against the real bundled files; the port is the only platform-shaped part, so a fake
 *    can supply a small or deliberately broken dataset to exercise the failure paths.
 *  - **It has a real failure mode.** Assets can be missing from a build, and a truncated file must
 *    produce a reported state instead of an empty picker. Modelling the result rather than throwing
 *    keeps that state visible all the way to the screen.
 */
interface BangladeshAreaCatalog {

    /**
     * The bundled dataset, or `null` when it could not be read at all.
     *
     * Implementations cache the parsed result: the files are read once per process. A non-null result
     * may still have `isUsable == false` — a dataset with areas but no boundary, for instance — which
     * the setup state reports separately from "could not be read".
     */
    suspend fun dataset(): BangladeshLocationDataset?
}
