package io.github.towkirahmedrony.salah

import android.os.Process
import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue

/** Temporary startup timing: milliseconds since the process started. Remove after measuring. */
object StartupTrace {
    private val marks = ConcurrentLinkedQueue<Pair<String, Long>>()

    fun mark(label: String) {
        marks.add(label to (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()))
    }

    fun summary(): String = marks.joinToString("\n") { (label, ms) -> "$label: ${ms}ms" }
}
