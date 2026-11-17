package com.splendor.blastbuster.companion

/**
 * Minimal JSON writer/parser for bridge payloads and EventLog lines.
 *
 * Pure Kotlin, zero Android imports, JVM-testable (DESIGN.md §4.2).
 * No JSON library exists under the no-dependency rule, so this is
 * hand-rolled and deliberately small: objects, arrays, strings,
 * numbers, booleans and null. It does not try to be a general JSON
 * library — it covers exactly what the bridge sends and receives.
 *
 * Writer values: String, Number, Boolean, null, Map<*, *>, List<*>,
 * [Raw] (a pre-built JSON fragment, inserted verbatim).
 */
object MiniJson {

    /** A pre-built JSON fragment, inserted verbatim by the writer. */
    class Raw(val json: String)

    class ParseException(message: String) : Exception(message)

    // ---- Writer ----

    fun string(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format("\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> string(v)
        is Boolean -> if (v) "true" else "false"
        is Number -> {
            val d = v.toDouble()
            if (d.isNaN() || d.isInfinite()) {
                throw IllegalArgumentException("MiniJson: NaN/Infinite not representable")
            }
            when (v) {
                is Long, is Int, is Short, is Byte -> v.toString()
                else -> {
                    // Whole doubles print without a fraction (e.g. 3 not 3.0).
                    if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 9e15) {
                        d.toLong().toString()
                    } else {
                        v.toString()
                    }
                }
            }
        }
        is Raw -> v.json
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            obj(v as Map<String, Any?>)
        }
        is List<*> -> arr(v)
        is Array<*> -> arr(v.toList())
        else -> string(v.toString())
    }

    /** Build an object from key/value pairs. Keys are written in given order. */
    fun obj(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder("{")
        pairs.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append(string(k)).append(':').append(value(v))
        }
        sb.append('}')
        return sb.toString()
    }

    /** Build an object from a map (iteration order preserved for LinkedHashMap). */
    fun obj(map: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            sb.append(string(k)).append(':').append(value(v))
        }
        sb.append('}')
        return sb.toString()
    }

    fun arr(items: Iterable<Any?>): String {
        val sb = StringBuilder("[")
        var first = true
        for (item in items) {
            if (!first) sb.append(',')
            first = false
            sb.append(value(item))
        }
        sb.append(']')
        return sb.toString()
    }

    fun arr(vararg items: Any?): String = arr(items.toList())

    // ---- Parser ----

    /**
     * Parse a JSON document. Returns Map<String, Any?> for objects,
     * List<Any?> for arrays, String / Long / Double / Boolean / null
     * for scalars. Throws [ParseException] on malformed input, including
     * trailing garbage after the top-level value.
     */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val result = p.parseValue()
        p.skipWs()
        if (p.pos != text.length) {
            throw ParseException("Trailing characters at ${p.pos}")
        }
        return result
    }

    /** Parse a top-level object, or return null if the input is not one. */
    @Suppress("UNCHECKED_CAST")
    fun parseObj(text: String): Map<String, Any?>? {
        val v = try {
            parse(text)
        } catch (_: ParseException) {
            return null
        }
        return v as? Map<String, Any?>
    }

    fun getString(map: Map<String, Any?>, key: String): String? =
        (map[key] as? String)

    fun getBool(map: Map<String, Any?>, key: String): Boolean? =
        (map[key] as? Boolean)

    fun getLong(map: Map<String, Any?>, key: String): Long? = when (val v = map[key]) {
        is Long -> v
        is Double -> if (v == kotlin.math.floor(v)) v.toLong() else null
        is Int -> v.toLong()
        else -> null
    }

    fun getMap(map: Map<String, Any?>, key: String): Map<String, Any?>? {
        @Suppress("UNCHECKED_CAST")
        return map[key] as? Map<String, Any?>
    }

    private class Parser(private val text: String) {
        var pos = 0

        fun skipWs() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (pos >= text.length) throw ParseException("Unexpected end of input")
            return when (val ch = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw ParseException("Unexpected character '$ch' at $pos")
            }
        }

        private fun parseLiteral(word: String, result: Any?): Any? {
            if (!text.startsWith(word, pos)) {
                throw ParseException("Invalid literal at $pos")
            }
            pos += word.length
            return result
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                if (pos >= text.length || text[pos] != '"') {
                    throw ParseException("Expected object key at $pos")
                }
                val key = parseString()
                skipWs()
                if (pos >= text.length || text[pos] != ':') {
                    throw ParseException("Expected ':' at $pos")
                }
                pos++
                val value = parseValue()
                map[key] = value
                skipWs()
                if (pos >= text.length) throw ParseException("Unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw ParseException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (pos >= text.length) throw ParseException("Unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw ParseException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < text.length) {
                val ch = text[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) throw ParseException("Unterminated escape")
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) {
                                    throw ParseException("Short unicode escape")
                                }
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw ParseException("Bad unicode escape \\u$hex")
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> throw ParseException("Bad escape \\$esc")
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) {
                            throw ParseException("Raw control character in string")
                        }
                        sb.append(ch)
                    }
                }
            }
            throw ParseException("Unterminated string")
        }

        private fun parseNumber(): Any {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            var isDouble = false
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos < text.length && text[pos] == '.') {
                isDouble = true
                pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            val num = text.substring(start, pos)
            if (num == "-" || num.isEmpty()) throw ParseException("Bad number at $start")
            return try {
                if (isDouble) num.toDouble() else num.toLong()
            } catch (_: NumberFormatException) {
                throw ParseException("Bad number '$num'")
            }
        }
    }
}
