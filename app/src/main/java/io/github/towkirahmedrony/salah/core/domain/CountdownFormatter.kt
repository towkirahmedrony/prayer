package io.github.towkirahmedrony.salah.core.domain

import java.time.Duration
import java.util.Locale

/**
 * Formats a remaining duration for the next-prayer countdown.
 *
 * Only used when a real schedule exists, so a countdown is never displayed on its own.
 */
object CountdownFormatter {

    private val ONE_MINUTE: Duration = Duration.ofMinutes(1)

    /**
     * Formats [remaining] as `H:MM:SS`, or as `MM:SS` when it is shorter than an hour.
     *
     * Negative values are clamped to `0:00`. Digits are formatted with [Locale.ROOT] on
     * purpose: the countdown is a reading of elapsed time, not a religious value, and must
     * render identically on every device.
     */
    fun format(remaining: Duration): String {
        val totalSeconds = remaining.seconds.coerceAtLeast(0L)
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Formats [remaining] as a compact hour-and-minute reading such as `1h 17m`, or `17m` below an
     * hour.
     *
     * The Home hero card and the today list read the remaining time as a coarse phrase rather than a
     * ticking `H:MM:SS`, so they need a second rendering of the same [Duration] — not a second clock.
     * Seconds are dropped rather than rounded up, so the reading never claims more time is left than
     * there is. Like [format], digits use [Locale.ROOT] and negative values are clamped to `0m`.
     */
    fun formatCompact(remaining: Duration): String {
        val totalMinutes = remaining.seconds.coerceAtLeast(0L) / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%dh %dm", hours, minutes)
        } else {
            String.format(Locale.ROOT, "%dm", minutes)
        }
    }

    /**
     * Like [formatCompact], but never claims a running countdown has already reached zero.
     *
     * [formatCompact] drops seconds, so a period with 30 seconds left reads `0m` even though it has
     * not ended, which on the Home hero card looks like the prayer is already over. Here a remaining
     * time that is positive but shorter than a minute reads `<1m`; an exhausted or negative duration
     * still reads `0m`, and every other value is rendered exactly as [formatCompact] renders it.
     *
     * It is a separate function rather than a change to [formatCompact] on purpose: the day and night
     * lengths are measurements, not countdowns, and a daylight span shorter than a minute is not a
     * thing `<1m` should ever describe. Only the countdown fields of the hero, current and next prayer
     * use this one.
     */
    fun formatCompactRemaining(remaining: Duration): String {
        val underAMinute = !remaining.isNegative && !remaining.isZero && remaining < ONE_MINUTE
        return if (underAMinute) "<1m" else formatCompact(remaining)
    }
}
