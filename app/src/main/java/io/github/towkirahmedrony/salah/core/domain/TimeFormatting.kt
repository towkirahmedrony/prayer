package io.github.towkirahmedrony.salah.core.domain

import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Builds the clock-format formatter for the user's [TimeFormatPreference].
 *
 * Shared by the Home screen and by prayer reminders, so the time announced in a notification is
 * formatted by exactly the same rule as the time shown in the timetable. Two independent
 * formatters would be a way for the app and its notifications to disagree about what "5:12 PM"
 * means — the kind of inconsistency that makes a user distrust both.
 *
 * `SYSTEM` follows the device locale, which is the only option that respects a locale whose
 * conventional clock is neither of the two fixed patterns the other options assume.
 */
fun timeFormatterFor(
    zone: ZoneId,
    locale: Locale,
    preference: TimeFormatPreference,
): DateTimeFormatter = when (preference) {
    TimeFormatPreference.SYSTEM -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    TimeFormatPreference.TWELVE_HOUR -> DateTimeFormatter.ofPattern("h:mm a", locale)
    TimeFormatPreference.TWENTY_FOUR_HOUR -> DateTimeFormatter.ofPattern("HH:mm", locale)
}.withLocale(locale).withZone(zone)
