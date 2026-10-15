package io.github.towkirahmedrony.salah.core.domain.dataset

/**
 * A dataset's published version identifier.
 *
 * **The comparison contract, stated explicitly.** Two versions are the same when their normalised
 * strings are equal, and that is the *only* comparison Salah performs. There is no ordering, no
 * major/minor parsing, and no "is newer than" — deliberately:
 *
 *  - The version Salah ships today is a description, not a number: `BGD-COD-AB v03 (2023-05-21)` names
 *    a release of an external dataset. Inventing an ordering over strings like that would be guessing.
 *  - Ordering is the thing an update mechanism is most likely to get wrong, and getting it wrong in the
 *    permissive direction means replacing good data with older data. Equality cannot be wrong in that
 *    direction: a version either differs from the one in use, or it does not.
 *  - Which dataset is *active* is therefore never decided by comparing versions. It is decided by what
 *    has actually been activated and validated, recorded as [ActiveDataset]. A version only answers
 *    "is this a different dataset from the one I already have?" — the question a future publication
 *    check actually needs to ask.
 *
 * When a future publisher wants ordering, the ordering information belongs in a separate field of the
 * publication (a publication timestamp, say), not smuggled into the version string.
 */
data class DatasetVersion(val value: String) {

    /** True when this version carries no usable identifier. */
    val isBlank: Boolean get() = value.isBlank()

    /**
     * True when [other] names the same dataset version.
     *
     * Whitespace-only differences are ignored, because a version that arrives from a text file with a
     * trailing tab is the same version as one that does not. Case is **not** folded: a publisher is
     * entitled to treat `v03` and `V03` as different releases, and silently equating them would defeat
     * the check.
     */
    fun isSameAs(other: DatasetVersion): Boolean = normalised() == other.normalised()

    private fun normalised(): String = value.trim()

    override fun toString(): String = value

    companion object {

        /**
         * The version named by [raw], with surrounding whitespace removed.
         *
         * Trimming here rather than at every comparison means a `DatasetVersion` constructed through
         * this factory already holds the value [isSameAs] compares, so `==` and [isSameAs] agree.
         */
        fun of(raw: String): DatasetVersion = DatasetVersion(raw.trim())
    }
}

/**
 * Where the dataset currently in use came from.
 *
 * Recorded because the two are not equally replaceable: a bundled dataset is present in the APK and
 * cannot be lost, while an applied one lives in the application's files and can be removed by a
 * backup/restore, a cleared cache or a failed write. Diagnostics and the location screen need to be
 * able to say which one is in force.
 */
enum class DatasetSource {
    /** The dataset packaged in the APK's assets. Always available; the recovery floor. */
    BUNDLED,

    /** A dataset that was validated and activated into local storage. */
    APPLIED,
}

/**
 * The dataset version actually in use, and where it came from.
 *
 * This is what the app reports as its active dataset. It is not a stored flag that could disagree with
 * reality: it is produced by resolving the local storage and falling back to the bundled assets, so it
 * describes what is genuinely being read rather than what was intended to be read.
 */
data class ActiveDataset(
    val version: DatasetVersion,
    val source: DatasetSource,
    /**
     * The integrity value of the payload in use, or `null` for the bundled dataset.
     *
     * The bundled assets are not checksummed at runtime: they arrive inside the APK, which the platform
     * has already verified, and re-hashing 141 KB on every launch to re-prove that would buy nothing.
     */
    val checksum: String? = null,
)
