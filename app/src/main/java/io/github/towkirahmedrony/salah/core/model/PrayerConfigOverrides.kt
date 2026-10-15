package io.github.towkirahmedrony.salah.core.model

/**
 * Which prayer-calculation fields the user has explicitly chosen on this device.
 *
 * **What it exists to protect.** A published remote configuration supplies defaults, but it must never
 * silently overwrite a value the user set themselves. A field is therefore only supplied by a remote
 * configuration while the user has not marked it here; the moment the user touches that control, the field
 * is recorded and the remote value stops applying to it. Resetting an override removes the mark, and the
 * field returns to whatever the applicable default is.
 *
 * It is stored beside [PrayerSettings] in the same DataStore, under its own key, so choosing a method
 * cannot disturb the theme, the calendar settings or the interface language — and so a remote update can be
 * applied without ever touching a field the user owns.
 */
data class PrayerConfigOverrides(
    val fields: Set<Field> = emptySet(),
) {

    /** The calculation fields a user can own. */
    enum class Field {
        METHOD,
        ASR_METHOD,
        HIGH_LATITUDE_RULE,
        ADJUSTMENTS,
    }

    /** True when the user owns at least one field, so the effective origin is a user override. */
    val hasAny: Boolean get() = fields.isNotEmpty()

    /** True when the user owns [field]. */
    fun contains(field: Field): Boolean = field in fields

    /** Returns a copy with [field] marked as user-owned. */
    fun with(field: Field): PrayerConfigOverrides =
        if (field in fields) this else copy(fields = fields + field)

    /** Returns a copy with [field] released back to the applicable default. */
    fun without(field: Field): PrayerConfigOverrides =
        if (field !in fields) this else copy(fields = fields - field)

    /** Returns a copy with no field owned: every field returns to its applicable default. */
    fun cleared(): PrayerConfigOverrides = if (fields.isEmpty()) this else PrayerConfigOverrides()

    /**
     * A stable, order-independent encoding for the single DataStore string key.
     *
     * Ordered by declaration so two equal sets encode identically and a stored value is byte-for-byte
     * reproducible.
     */
    fun encode(): String = Field.entries.filter { it in fields }.joinToString(",") { it.name }

    companion object {
        /** No field is user-owned. */
        val NONE: PrayerConfigOverrides = PrayerConfigOverrides()

        /**
         * Reads an encoded value.
         *
         * Unknown names are ignored rather than failing, so a value written by a later or earlier build
         * cannot make the calculation settings unreadable. A blank value is [NONE].
         */
        fun decode(raw: String?): PrayerConfigOverrides {
            if (raw.isNullOrBlank()) return NONE
            val parsed = raw.split(',')
                .mapNotNull { token -> Field.entries.firstOrNull { it.name == token.trim() } }
                .toSet()
            return PrayerConfigOverrides(parsed)
        }

        /**
         * Infers the owned fields of a store written before this feature existed.
         *
         * There is no recorded marker in such a store, so a field is treated as user-owned exactly when it
         * differs from [PrayerSettings.DEFAULT]. That errs on the side of *not* overwriting: a value a user
         * had changed is preserved, while a value still at the bundled default may be supplied by a remote
         * configuration — which is the only safe reading of a store that never recorded an explicit choice.
         */
        fun deriving(settings: PrayerSettings): PrayerConfigOverrides {
            val fields = buildSet {
                if (settings.method != PrayerSettings.DEFAULT.method) add(Field.METHOD)
                if (settings.asrMethod != PrayerSettings.DEFAULT.asrMethod) add(Field.ASR_METHOD)
                if (settings.highLatitudeRule != PrayerSettings.DEFAULT.highLatitudeRule) {
                    add(Field.HIGH_LATITUDE_RULE)
                }
                if (settings.adjustmentsMinutes != PrayerSettings.DEFAULT.adjustmentsMinutes) {
                    add(Field.ADJUSTMENTS)
                }
            }
            return PrayerConfigOverrides(fields)
        }
    }
}
