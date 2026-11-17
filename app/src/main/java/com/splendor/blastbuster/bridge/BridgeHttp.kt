package com.splendor.blastbuster.bridge

/**
 * Hand-rolled HTTP/1.1 request parsing and response building for the
 * Muse bridge (DESIGN.md §2.3).
 *
 * Pure Kotlin, zero Android imports, JVM-testable. Deliberately tiny:
 * request line + headers + optional body, Content-Length only, no
 * chunked encoding, no keep-alive — one request per connection, close
 * after each response. Polling is infrequent, so this is enough, and
 * the small surface is the point: less parser, fewer ways to be wrong.
 */
object BridgeHttp {

    /** Routes the bridge serves. */
    enum class Route {
        STATUS, EVENTS, RAM, DND, NET, HEALTH, COMMAND, NOT_FOUND, METHOD_NOT_ALLOWED
    }

    data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String
    )

    /** Map a (method, path) pair to a route. Unknown paths are NOT_FOUND. */
    fun route(method: String, path: String): Route {
        val m = method.uppercase()
        return when (path) {
            "/v1/status" -> if (m == "GET") Route.STATUS else Route.METHOD_NOT_ALLOWED
            "/v1/events" -> if (m == "GET") Route.EVENTS else Route.METHOD_NOT_ALLOWED
            "/v1/ram" -> if (m == "GET") Route.RAM else Route.METHOD_NOT_ALLOWED
            "/v1/dnd" -> if (m == "GET") Route.DND else Route.METHOD_NOT_ALLOWED
            "/v1/net" -> if (m == "GET") Route.NET else Route.METHOD_NOT_ALLOWED
            "/v1/health" -> if (m == "GET") Route.HEALTH else Route.METHOD_NOT_ALLOWED
            "/v1/command" -> if (m == "POST") Route.COMMAND else Route.METHOD_NOT_ALLOWED
            else -> Route.NOT_FOUND
        }
    }

    /**
     * Parse one raw HTTP request (head + body already read as text).
     * Returns null on any malformed input — the server answers 400 and
     * closes the connection; a bad request never reaches a handler.
     * Headers are stored lower-cased; a missing Content-Length means an
     * empty body. A body longer than [maxBodyBytes] is rejected.
     */
    fun parse(raw: String, maxBodyBytes: Int = 64 * 1024): HttpRequest? {
        val headEnd = raw.indexOf("\r\n\r\n")
        if (headEnd < 0) return null
        val head = raw.substring(0, headEnd)
        var body = raw.substring(headEnd + 4)
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(" ")
        if (requestLine.size != 3) return null
        val method = requestLine[0]
        if (method.isEmpty() || !method.all { it.isLetter() }) return null
        if (requestLine[2] != "HTTP/1.0" && requestLine[2] != "HTTP/1.1") return null
        val target = requestLine[1]
        if (!target.startsWith("/")) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name.isEmpty()) return null
            headers[name] = value
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0 || contentLength > maxBodyBytes) return null
        // The caller may have read past the body; keep exactly Content-Length bytes.
        if (body.length > contentLength) body = body.substring(0, contentLength)
        if (body.length < contentLength) return null
        val pathPart = target.substringBefore('?')
        val queryPart = if ('?' in target) target.substringAfter('?', "") else ""
        return HttpRequest(method, pathPart, parseQuery(queryPart), headers, body)
    }

    /** Parse a `a=1&b=2` query string, percent-decoding keys and values. */
    fun parseQuery(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (query.isEmpty()) return out
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                out[urlDecode(pair)] = ""
            } else {
                out[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
            }
        }
        return out
    }

    fun urlDecode(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            when (val ch = s[i]) {
                '%' -> {
                    if (i + 2 < s.length) {
                        val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                        if (hex != null) {
                            sb.append(hex.toChar())
                            i += 3
                            continue
                        }
                    }
                    sb.append(ch)
                    i++
                }
                '+' -> {
                    sb.append(' ')
                    i++
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /**
     * Build a complete HTTP/1.1 response. Always `Connection: close` —
     * one request per connection, by design (§2.3).
     */
    fun buildResponse(status: Int, reason: String, jsonBody: String): String {
        val bytes = jsonBody.toByteArray(Charsets.UTF_8)
        return "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            jsonBody
    }
}
