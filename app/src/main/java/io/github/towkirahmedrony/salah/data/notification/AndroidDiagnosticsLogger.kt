package io.github.towkirahmedrony.salah.data.notification

import android.util.Log
import io.github.towkirahmedrony.salah.core.domain.DiagnosticsLogger

/**
 * [DiagnosticsLogger] over `android.util.Log`.
 *
 * Every line is rendered as `event key=value key=value` under the single `Salah` tag, so a log
 * dump of the notification subsystem can be read as a sequence of events and filtered by event
 * name with one `grep`. That matters because alarms, receivers and reboot recovery have no UI:
 * the log is the only evidence they ran.
 *
 * The logger renders whatever the caller passes and adds nothing of its own, which is deliberate.
 * The decision about what is safe to record lives with the call site, and the call sites in the
 * notification path pass counts, identities, reason codes and zone ids — never a coordinate, never
 * a notification body. See `docs/PRAYER_NOTIFICATIONS.md` §8.
 *
 * Log levels are used as documented: [info] for normal operation, [warn] for a degraded-but-correct
 * path (exact alarms unavailable, notifications not permitted, a date with no schedule), and
 * [error] only for real failures. Nothing here reports success for work that did not happen.
 */
class AndroidDiagnosticsLogger(
    private val tag: String = DEFAULT_TAG,
) : DiagnosticsLogger {

    override fun info(event: String, details: Map<String, Any?>) {
        Log.i(tag, render(event, details))
    }

    override fun warn(event: String, details: Map<String, Any?>) {
        Log.w(tag, render(event, details))
    }

    override fun error(event: String, throwable: Throwable?, details: Map<String, Any?>) {
        val message = render(event, details)
        if (throwable == null) {
            Log.e(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
    }

    /** `event key=value key=value`, with keys sorted so a line is stable and diffable. */
    private fun render(event: String, details: Map<String, Any?>): String {
        if (details.isEmpty()) return event
        return buildString {
            append(event)
            details.toSortedMap().forEach { (key, value) ->
                append(' ').append(key).append('=').append(value)
            }
        }
    }

    companion object {
        /** The single tag every Salah background log line carries. */
        const val DEFAULT_TAG = "Salah"
    }
}
