package io.github.towkirahmedrony.salah.core.model

/**
 * Everything the user can configure about one reminder target.
 *
 * Immutable and persisted as a unit, like [PrayerSettings]. Only the fields that a real behaviour
 * depends on exist here: [enabled] controls whether a reminder is scheduled at all, [sound] and
 * [vibration] control how it announces itself, and [advanceMinutes] controls the optional reminder
 * that runs before the prayer.
 *
 * There is deliberately **no** "play the Azan" flag. A long-form Azan recording requires a
 * recording that Salah has the right to redistribute, and none is bundled — so offering the
 * setting would be offering a control that cannot work. The playback subsystem is implemented
 * and unit-tested (`AzanPlaybackManager`) and becomes reachable the moment a licensed recording
 * is added; see `docs/PRAYER_NOTIFICATIONS.md` §5.
 */
data class PrayerNotificationPreference(
    /** Whether Salah schedules a reminder for this target at all. */
    val enabled: Boolean = true,

    /** Which sound the reminder uses. */
    val sound: NotificationSound = NotificationSound.SYSTEM_DEFAULT,

    /** Whether the reminder vibrates, where the device and its settings permit it. */
    val vibration: Boolean = true,

    /**
     * Minutes before the prayer at which an additional reminder is delivered.
     *
     * `0` disables the advance reminder. The value is clamped to
     * `0..[PrayerNotificationSettings.MAX_ADVANCE_MINUTES]`.
     */
    val advanceMinutes: Int = 0,
) {
    /** True when this preference is exactly the documented default, so it need not be stored. */
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        /** The configuration a target has before the user changes anything. */
        val DEFAULT: PrayerNotificationPreference = PrayerNotificationPreference()
    }
}

/**
 * Everything the user can configure about prayer notifications.
 *
 * This is one flat, immutable object on purpose, exactly like [PrayerSettings]: it is persisted as
 * a unit, compared as a unit, and it is the only input the notification planner reads besides the
 * prayer schedule itself.
 *
 * [prayers] is **sparse**: a target whose preference equals [PrayerNotificationPreference.DEFAULT]
 * is not stored, so the store only ever holds decisions the user actually made. Reads go through
 * [preferenceFor], which supplies the default for anything absent, so the planner never has to
 * deal with a missing entry.
 *
 * Sunrise is **not** an obligatory prayer and is therefore not part of [prayers]. It has its own
 * opt-in switch ([sunriseReminderEnabled]), because a sunrise reminder is a solar-event reminder
 * with different semantics, and mixing the two would present an astronomical event as a prayer.
 */
data class PrayerNotificationSettings(
    /**
     * The global master switch.
     *
     * `false` means Salah schedules nothing and cancels every reminder it manages. It is the
     * single control that turns the whole feature off, and it is honoured by the scheduler, the
     * planner and the delivery path independently, so no single missed code path can leak a
     * notification through.
     */
    val masterEnabled: Boolean = true,

    /** Per-prayer overrides. Absent entries mean [PrayerNotificationPreference.DEFAULT]. */
    val prayers: Map<PrayerName, PrayerNotificationPreference> = emptyMap(),

    /**
     * Optional reminder at sunrise.
     *
     * Offered separately from the five prayers and labelled as a solar event everywhere it
     * appears. It is off by default because it is not an obligation.
     */
    val sunriseReminderEnabled: Boolean = false,
) {

    /** The stored preference for [prayer], or the documented default when none was stored. */
    fun preferenceFor(prayer: PrayerName): PrayerNotificationPreference =
        prayers[prayer] ?: PrayerNotificationPreference.DEFAULT

    /**
     * Returns a copy with [prayer]'s preference set to [preference], clamping the advance window.
     *
     * A preference equal to the default is removed rather than stored, so the persisted map only
     * holds real decisions and cannot grow with redundant entries.
     */
    fun withPreference(
        prayer: PrayerName,
        preference: PrayerNotificationPreference,
    ): PrayerNotificationSettings {
        val clamped = preference.withClampedAdvanceMinutes()
        val next = prayers.toMutableMap()
        if (clamped.isDefault) next.remove(prayer) else next[prayer] = clamped
        return copy(prayers = next)
    }

    /** True when at least one obligatory prayer would produce a reminder. */
    val hasAnyEnabledPrayer: Boolean
        get() = masterEnabled && PrayerName.entries.any { preferenceFor(it).enabled }

    /** True when anything at all is enabled. */
    val hasAnyEnabledTarget: Boolean
        get() = hasAnyEnabledPrayer || (masterEnabled && sunriseReminderEnabled)

    companion object {
        /** Largest advance reminder Salah accepts, in minutes. */
        const val MAX_ADVANCE_MINUTES: Int = 30

        /** The advance values the settings screen offers, in minutes. `0` means "off". */
        val ADVANCE_MINUTE_CHOICES: List<Int> = listOf(0, 5, 10, 15, 20, 30)

        /** The configuration a fresh install starts from. */
        val DEFAULT: PrayerNotificationSettings = PrayerNotificationSettings()
    }
}

/** Fixes an arbitrarily stored advance value so the UI can always represent it. */
internal fun PrayerNotificationPreference.withClampedAdvanceMinutes(): PrayerNotificationPreference =
    if (advanceMinutes in 0..PrayerNotificationSettings.MAX_ADVANCE_MINUTES) {
        this
    } else {
        copy(
            advanceMinutes = advanceMinutes.coerceIn(
                minimumValue = 0,
                maximumValue = PrayerNotificationSettings.MAX_ADVANCE_MINUTES,
            ),
        )
    }
