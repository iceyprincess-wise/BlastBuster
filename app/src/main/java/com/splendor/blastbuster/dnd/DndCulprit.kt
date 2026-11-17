package com.splendor.blastbuster.dnd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * DND culprit resolution (DESIGN.md §5.2). Three tiers, honestly labelled
 * — a history row never claims a culprit it did not observe:
 *
 * 1. LOGCAT — "seen in system log": the package named in a
 *    `ZenModeHelper` / `NotificationManagerService` logcat line. Needs
 *    the `READ_LOGS` ADB grant, and on Android 16 a third-party app may
 *    see only its own logs even with the grant — this tier must prove
 *    itself on the Redmi 15C (§7, D-07) and reports nothing when it
 *    cannot. It is a progressive enhancement, never a requirement.
 * 2. LISTENER — "app in front when DND changed": the foreground package
 *    at the moment the change was observed. An inference, not proof.
 * 3. POLLING — "app in front when noticed (up to 60 s after the change)":
 *    the foreground package at the guard's 60 s poll. Also an inference.
 *
 * The pure resolution ([resolve]) is JVM-testable; the logcat attempt is
 * on-device only.
 */
object DndCulprit {

    enum class Tier { LOGCAT, LISTENER, POLLING, UNKNOWN }

    data class CulpritResult(
        val packageName: String?,
        val tier: Tier,
        /** The exact label shown on the history row. */
        val label: String
    )

    /** Tier 1: the package was named in the system log. */
    fun fromLogcat(pkg: String): CulpritResult =
        CulpritResult(pkg, Tier.LOGCAT, "seen in system log")

    /** Tier 2: the package was in front when the change was observed. */
    fun fromListener(foregroundPkg: String): CulpritResult =
        CulpritResult(foregroundPkg, Tier.LISTENER, "app in front when DND changed (inferred)")

    /** Tier 3: the package was in front when the poll noticed the change. */
    fun fromPolling(foregroundPkg: String): CulpritResult =
        CulpritResult(
            foregroundPkg, Tier.POLLING,
            "app in front when noticed, up to 60 s after the change (inferred)"
        )

    /** No evidence at all — the row says "unknown", never a guess. */
    fun unknown(): CulpritResult =
        CulpritResult(null, Tier.UNKNOWN, "unknown — no culprit observed")

    /**
     * Pure resolution order: a logcat package beats a foreground
     * inference; a foreground package beats unknown. JVM-tested.
     */
    fun resolve(logcatPkg: String?, foregroundPkg: String?, tierOfForeground: Tier): CulpritResult {
        if (!logcatPkg.isNullOrBlank()) return fromLogcat(logcatPkg)
        if (!foregroundPkg.isNullOrBlank()) {
            return when (tierOfForeground) {
                Tier.LISTENER -> fromListener(foregroundPkg)
                Tier.POLLING -> fromPolling(foregroundPkg)
                else -> fromListener(foregroundPkg)
            }
        }
        return unknown()
    }

    /** True when the `READ_LOGS` ADB grant is currently held. */
    fun hasReadLogsGrant(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_LOGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Attempts the logcat tier: reads recent logcat output and looks for a
     * package named as the caller of a DND (`setInterruptionFilter`)
     * change. Returns the package, or null when the grant is missing,
     * logcat is unavailable, or no such line exists. Runs a subprocess
     * with a 3 s timeout; callers must call it off the main thread.
     * On Android 16 this is expected to often return null — that is the
     * honest result, recorded by the caller, not an error.
     */
    fun attemptLogcat(): String? {
        var proc: Process? = null
        return try {
            proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "500"))
            val finished = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroy()
                return null
            }
            val out = proc.inputStream.bufferedReader().readText()
            findCulpritInLogcat(out)
        } catch (_: Exception) {
            null
        } finally {
            try {
                proc?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Pure logcat-line scan: looks for `callingPkg=<package>` or
     * `pkg=<package>` on lines mentioning DND changes, newest line wins.
     * JVM-testable.
     */
    fun findCulpritInLogcat(logText: String): String? {
        var found: String? = null
        for (line in logText.lines()) {
            val lower = line.lowercase()
            if (!lower.contains("interruptionfilter") &&
                !lower.contains("zenmode") &&
                !lower.contains("setinterruption")
            ) continue
            val pkg = extractPkg(line) ?: continue
            found = pkg
        }
        return found
    }

    private fun extractPkg(line: String): String? {
        for (key in listOf("callingPkg=", "callingPackage=", "pkg=")) {
            val i = line.indexOf(key)
            if (i < 0) continue
            val rest = line.substring(i + key.length).trim()
            val end = rest.indexOfFirst { it == ' ' || it == ',' || it == ']' || it == '}' }
            val pkg = if (end < 0) rest else rest.substring(0, end)
            if (pkg.contains('.')) return pkg
        }
        return null
    }
}
