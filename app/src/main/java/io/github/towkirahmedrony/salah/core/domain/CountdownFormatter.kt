package io.github.towkirahmedrony.salah.core.domain

import java.time.Duration
import java.util.Locale

/**
 * Formats a remaining duration for the next-prayer countdown.
 *
 * Only used when a real schedule exists, so a countdown is never displayed on its own.
 */
object CountdownFormatter {

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
}
