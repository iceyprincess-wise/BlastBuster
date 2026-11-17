package com.splendor.blastbuster.companion

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * Shared append-only event log (DESIGN.md §2.6). Every module writes here;
 * the bridge reads it and serves it; the UI reads it for history screens.
 *
 * Store: one JSON-lines file (`eventlog.jsonl`) in the app's private files
 * dir, plus the in-memory tail returned by [readTail]. Cap: 500 events /
 * 256 KB, oldest-first eviction. Privacy: phone numbers are never logged
 * and GPS coordinates are never logged — callers must not put them in
 * [append]'s summary or data.
 *
 * Writes go through a single-threaded writer (DESIGN.md §4.4), so no
 * caller ever does file I/O on its own thread and concurrent modules
 * cannot interleave lines. [append] only enqueues; the writer thread does
 * the I/O. Reads are synchronous and take the same lock as the writer.
 *
 * The JSON encode / parse / eviction helpers are pure (no Android use)
 * and JVM-tested; the file-I/O half is on-device only.
 */
object EventLog {

    const val FILE_NAME = "eventlog.jsonl"

    /** Ring cap: at most this many events are kept. */
    const val MAX_EVENTS = 500

    /** Ring cap: at most this many bytes are kept. */
    const val MAX_BYTES = 256 * 1024

    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BlastBuster-EventLog").apply { isDaemon = true }
    }

    @Volatile
    private var lastId = -1L

    @Volatile
    private var idInitialised = false

    data class LoggedEvent(
        val id: Long,
        val ts: Long,
        val module: String,
        val type: String,
        val summary: String,
        val data: Map<String, String> = emptyMap()
    )

    /**
     * Appends one event. Fire-and-forget: the write happens on the
     * single writer thread. Never put phone numbers, GPS coordinates,
     * codes or tokens in [summary] or [data].
     */
    fun append(
        ctx: Context,
        module: String,
        type: String,
        summary: String,
        data: Map<String, String> = emptyMap()
    ) {
        val appCtx = ctx.applicationContext
        writer.execute {
            try {
                appendNow(appCtx, module, type, summary, data)
            } catch (_: Exception) {
                // The log must never crash a caller. A lost log line is
                // acceptable; a crashed blast or guard is not.
            }
        }
    }

    /** Synchronous append, writer-thread only. Takes [lock]. */
    private fun appendNow(
        ctx: Context,
        module: String,
        type: String,
        summary: String,
        data: Map<String, String>
    ) {
        synchronized(lock) {
            val file = File(ctx.filesDir, FILE_NAME)
            if (!idInitialised) {
                lastId = scanMaxId(file)
                idInitialised = true
            }
            val event = LoggedEvent(
                id = lastId + 1,
                ts = System.currentTimeMillis(),
                module = module,
                type = type,
                summary = summary,
                data = data
            )
            lastId = event.id
            file.appendText(encodeEvent(event) + "\n")
            enforceCap(file)
        }
    }

    /**
     * Events with `id > since`, oldest first, at most [limit]. Returns the
     * events and the `next_since` cursor (the highest id seen, or [since]
     * when nothing is newer). Malformed lines are skipped, never fatal.
     */
    fun readSince(ctx: Context, since: Long, limit: Int): Pair<List<LoggedEvent>, Long> {
        synchronized(lock) {
            val file = File(ctx.filesDir, FILE_NAME)
            if (!file.exists()) return Pair(emptyList(), since)
            var nextSince = since
            val out = ArrayList<LoggedEvent>()
            for (line in file.readLines()) {
                val event = parseLine(line) ?: continue
                if (event.id <= since) continue
                if (event.id > nextSince) nextSince = event.id
                out.add(event)
            }
            val tail = if (out.size > limit) out.subList(out.size - limit, out.size) else out
            return Pair(tail.toList(), nextSince)
        }
    }

    /** Newest-first tail for the in-app history screens, at most [limit]. */
    fun readTail(ctx: Context, limit: Int): List<LoggedEvent> {
        synchronized(lock) {
            val file = File(ctx.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            val out = ArrayList<LoggedEvent>()
            for (line in file.readLines()) {
                parseLine(line)?.let { out.add(it) }
            }
            return out.takeLast(limit).reversed()
        }
    }

    /** Number of stored events (lines that parse). */
    fun count(ctx: Context): Int {
        synchronized(lock) {
            val file = File(ctx.filesDir, FILE_NAME)
            if (!file.exists()) return 0
            var n = 0
            for (line in file.readLines()) if (parseLine(line) != null) n++
            return n
        }
    }

    // ---------- pure helpers (JVM-tested, no Android use) ----------

    /** JSON-escapes one string value (quotes, backslash, control chars). */
    fun jsonEscape(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        for (ch in raw) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code))
                else sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Encodes one event as a single JSON line. Field order is fixed. */
    fun encodeEvent(event: LoggedEvent): String {
        val sb = StringBuilder()
        sb.append("{\"id\":").append(event.id)
        sb.append(",\"ts\":").append(event.ts)
        sb.append(",\"module\":\"").append(jsonEscape(event.module)).append('"')
        sb.append(",\"type\":\"").append(jsonEscape(event.type)).append('"')
        sb.append(",\"summary\":\"").append(jsonEscape(event.summary)).append('"')
        sb.append(",\"data\":{")
        var first = true
        for ((k, v) in event.data) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(jsonEscape(k)).append("\":\"")
                .append(jsonEscape(v)).append('"')
        }
        sb.append("}}")
        return sb.toString()
    }

    /**
     * Parses one JSON line produced by [encodeEvent]. Returns null for a
     * malformed line — callers skip it, never crash on it.
     */
    fun parseLine(line: String): LoggedEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return try {
            val parser = MiniParser(trimmed)
            val obj = parser.parseObject() ?: return null
            val id = (obj["id"] as? Long) ?: return null
            val ts = (obj["ts"] as? Long) ?: return null
            val module = (obj["module"] as? String) ?: return null
            val type = (obj["type"] as? String) ?: return null
            val summary = (obj["summary"] as? String) ?: return null
            @Suppress("UNCHECKED_CAST")
            val dataObj = obj["data"] as? Map<String, Any?> ?: emptyMap()
            val data = LinkedHashMap<String, String>()
            for ((k, v) in dataObj) if (v is String) data[k] = v
            LoggedEvent(id, ts, module, type, summary, data)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Oldest-first eviction over raw lines: drops the oldest lines until
     * at most [maxEvents] lines and at most [maxBytes] bytes remain.
     * Malformed lines are kept (they still cost bytes) — eviction is
     * about size, parsing is the reader's job.
     */
    fun evictLines(lines: List<String>, maxEvents: Int, maxBytes: Int): List<String> {
        if (lines.isEmpty()) return lines
        var start = 0
        var bytes = 0
        for (line in lines) bytes += line.toByteArray(Charsets.UTF_8).size + 1
        while (start < lines.size &&
            (lines.size - start > maxEvents || bytes > maxBytes)
        ) {
            bytes -= lines[start].toByteArray(Charsets.UTF_8).size + 1
            start++
        }
        return if (start == 0) lines else lines.subList(start, lines.size)
    }

    /** Highest `id` in the file, or -1 when the file is empty/missing. */
    fun scanMaxId(file: File): Long {
        if (!file.exists()) return -1L
        var max = -1L
        for (line in file.readLines()) {
            val event = parseLine(line) ?: continue
            if (event.id > max) max = event.id
        }
        return max
    }

    /** Drops oldest lines in [file] until both caps hold. Caller holds [lock]. */
    private fun enforceCap(file: File) {
        val lines = file.readLines()
        var bytes = 0
        for (line in lines) bytes += line.toByteArray(Charsets.UTF_8).size + 1
        if (lines.size <= MAX_EVENTS && bytes <= MAX_BYTES) return
        val kept = evictLines(lines, MAX_EVENTS, MAX_BYTES)
        file.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
    }

    /**
     * Minimal JSON parser: flat objects with string / long / nested-object
     * values only — exactly what [encodeEvent] writes. Anything else is a
     * parse failure (null), never a crash.
     */
    private class MiniParser(private val text: String) {
        private var pos = 0

        fun parseObject(): Map<String, Any?>? {
            skipWs()
            if (!consume('{')) return null
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (consume('}')) return out
            while (true) {
                skipWs()
                val key = parseString() ?: return null
                skipWs()
                if (!consume(':')) return null
                skipWs()
                val value = parseValue() ?: return null
                out[key] = value
                skipWs()
                if (consume('}')) return out
                if (!consume(',')) return null
            }
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= text.length) return null
            return when (text[pos]) {
                '"' -> parseString()
                '{' -> parseObject()
                't' -> if (text.startsWith("true", pos)) {
                    pos += 4; true
                } else null
                'f' -> if (text.startsWith("false", pos)) {
                    pos += 5; false
                } else null
                'n' -> if (text.startsWith("null", pos)) {
                    pos += 4; null
                } else null
                '-', in '0'..'9' -> parseLong()
                else -> null
            }
        }

        private fun parseLong(): Long? {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos == start) return null
            return text.substring(start, pos).toLongOrNull()
        }

        private fun parseString(): String? {
            if (pos >= text.length || text[pos] != '"') return null
            pos++
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) return null
                        when (text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > text.length) return null
                                val code = text.substring(pos, pos + 4)
                                    .toIntOrNull(16) ?: return null
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> return null
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            return null
        }

        private fun skipWs() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        private fun consume(c: Char): Boolean {
            if (pos < text.length && text[pos] == c) {
                pos++
                return true
            }
            return false
        }
    }
}
