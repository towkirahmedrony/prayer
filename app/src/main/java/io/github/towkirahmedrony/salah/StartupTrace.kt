package io.github.towkirahmedrony.salah

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Temporary startup timing: milliseconds since the process started. Remove after measuring.
 *
 * **Every mark is logged as well as collected.** [summary] is a one-shot snapshot: it was previously
 * only ever read when the dialog was built, so any event that happened *after* that point — the
 * dataset finishing, Home becoming readable, the reconciliation — was recorded but never seen. The
 * log is the channel that can show those late events, and it shows them in the order they happened.
 *
 * Collect the log with:
 * `adb logcat -s SalahStartup`
 */
object StartupTrace {

    /** Logcat tag every mark is written under. */
    const val TAG: String = "SalahStartup"

    /**
     * Read once, because the process start time cannot change during the process's life and this
     * keeps the marking thread's work to a `SystemClock` read.
     */
    private val processStartElapsedMillis: Long = Process.getStartElapsedRealtime()

    /**
     * Thread-safe: marks are taken from the main thread (Application, Activity, view models) and from
     * `Dispatchers.Default` and `Dispatchers.IO` (the prefetch and the reconciliation), so a plain
     * list would be a data race.
     */
    private val marks = ConcurrentLinkedQueue<Pair<String, Long>>()

    fun mark(label: String) {
        val elapsed = SystemClock.elapsedRealtime() - processStartElapsedMillis
        marks.add(label to elapsed)
        // Written on the calling thread on purpose: the elapsed value and the order relative to the
        // other marks are the measurement, so the line cannot be handed to another thread to be
        // logged at some later point. One short line, no formatting, no I/O of our own.
        Log.i(TAG, "$label=${elapsed}ms")
    }

    fun summary(): String = marks.joinToString("\n") { (label, ms) -> "$label: ${ms}ms" }
}
