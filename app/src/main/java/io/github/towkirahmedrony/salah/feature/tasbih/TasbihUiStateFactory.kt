package io.github.towkirahmedrony.salah.feature.tasbih

import io.github.towkirahmedrony.salah.core.domain.calendar.CalendarFormatting
import io.github.towkirahmedrony.salah.core.domain.timeFormatterFor
import io.github.towkirahmedrony.salah.core.model.TasbihSession
import io.github.towkirahmedrony.salah.core.model.TimeFormatPreference
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Turns the stored counter into the screen's state.
 *
 * Pure and Android-free, like `RamadanUiStateFactory` and `HomeUiStateFactory`, and for the same reason:
 * the decisions worth testing are *which numerals, which fraction, which timestamp* — and a test can
 * assert those without a device, while it could not assert them from inside a composition.
 *
 * ## What it formats, and what it leaves alone
 *
 * It formats **values**: the count and the target in the interface language's numerals, the progress as a
 * fraction, a session's start as a date and a time. It formats no **copy** — no label, no heading, no
 * message. Every sentence the screen shows is a string resource resolved by the composable, which is what
 * keeps the words in the translation files where they can be reviewed and out of a class that would have
 * to be handed a `Context` to hold them.
 *
 * ## The numerals
 *
 * Counts go through [CalendarFormatting.numerals], the same rule the calendar, the Ramadan table and the
 * Home timetable use. A Bengali interface therefore reads `৩৩`, and an English one `33`. Salah has exactly
 * one answer to "which digits", and this is where the counter asks it — a second digit table would be a
 * second answer that could disagree with the dates on the History list beside it.
 *
 * ## The timestamps
 *
 * A session's start is rendered as a date and a time in the **device's own** zone, not the configured
 * location's. The distinction matters and is deliberate: every other time in Salah is a *prayer* time,
 * which belongs to the place the times were calculated for, but a counting session belongs to the person
 * holding the phone. "When did I count this" is a question about the wall clock they were looking at. The
 * clock format follows the app's own 12/24-hour preference through [timeFormatterFor], so a history entry
 * is written the same way as the timetable above it, and the digits of that formatted time are passed
 * through [CalendarFormatting.localizeDigits] so the time is in the same script as the date beside it.
 */
class TasbihUiStateFactory(
    private val formatting: CalendarFormatting = CalendarFormatting(),
) {

    /**
     * The state for one reading of the store.
     *
     * Every parameter is an input rather than something read here: the session and history come from the
     * repository, [locale] from the interface-language preference, [timeFormat] from the prayer settings,
     * [zone] from the device, and [message] from whatever the last action reported. Nothing in this class
     * consults a clock, a preference or a device, so the same inputs always produce the same state.
     */
    fun create(
        session: TasbihSession,
        history: List<TasbihSession>,
        hapticsEnabled: Boolean,
        locale: Locale,
        timeFormat: TimeFormatPreference,
        zone: ZoneId,
        message: TasbihMessage?,
    ): TasbihUiState.Ready {
        // Normalised here as well as on the way out of the store, so a caller that hands this factory a
        // session built in a test — or one from a newer build — gets a state the screen can always draw.
        val safe = session.normalized()

        return TasbihUiState.Ready(
            sessionId = safe.id,
            countDigits = formatting.numerals(safe.count, locale),
            targetDigits = formatting.numerals(safe.target, locale),
            progress = safe.progress,
            isComplete = safe.isComplete,
            hasCountedPastTarget = safe.count > safe.target,
            // Present only while something is still owed. A completed session shows the completion notice
            // instead, and "0 to go" beside a "target reached" line would be two ways of saying it.
            remainingDigits = if (safe.isComplete) null else formatting.numerals(safe.remainingToTarget, locale),
            canCount = safe.canCount,
            canUndo = safe.count > 0,
            target = safe.target,
            targetChoices = targetChoices(safe.target, locale),
            history = history.map { archived -> historyRow(archived, locale, timeFormat, zone) },
            historyLimitDigits = formatting.numerals(TasbihSession.HISTORY_LIMIT, locale),
            hapticsEnabled = hapticsEnabled,
            message = message,
        )
    }

    /**
     * The offered targets, with the session's own marked.
     *
     * A stored target that is not one of the shipped choices — a value written by an older build, or one
     * this build no longer offers — is added to the list rather than dropped. Silently rewriting a number
     * the user chose would change what they are counting towards, and the only honest way to offer a
     * different set is to show their value alongside it.
     */
    private fun targetChoices(target: Int, locale: Locale): List<TasbihTargetChoice> {
        val offered = if (target in TasbihSession.TARGET_CHOICES) {
            TasbihSession.TARGET_CHOICES
        } else {
            (TasbihSession.TARGET_CHOICES + target).sorted()
        }
        return offered.map { value ->
            TasbihTargetChoice(
                target = value,
                label = formatting.numerals(value, locale),
                selected = value == target,
            )
        }
    }

    /**
     * One finished session as a row.
     *
     * The date and the time are one label because they are one fact — when the session started — and the
     * list is narrow enough that splitting them into two columns would cost a line without adding a
     * distinction. `Instant` is converted with [zone] rather than formatted with it directly, so the date
     * and the time come from the same zone and cannot disagree across a midnight boundary.
     */
    private fun historyRow(
        session: TasbihSession,
        locale: Locale,
        timeFormat: TimeFormatPreference,
        zone: ZoneId,
    ): TasbihHistoryRow {
        val safe = session.normalized()
        val local = Instant.ofEpochMilli(safe.startedAtMillis).atZone(zone)
        val date = formatting.gregorianDayMonthYear(local.toLocalDate(), locale)
        val time = formatting.localizeDigits(timeFormatterFor(zone, locale, timeFormat).format(local), locale)

        return TasbihHistoryRow(
            id = safe.id,
            countDigits = formatting.numerals(safe.count, locale),
            targetDigits = formatting.numerals(safe.target, locale),
            isComplete = safe.isComplete,
            timestampLabel = "$date · $time",
        )
    }
}
