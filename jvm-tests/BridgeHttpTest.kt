package com.splendor.blastbuster.bridge

/**
 * JVM tests for BridgeHttp (DESIGN.md §7.1 — request parsing / routing).
 * Standalone main() runner — no JUnit dependency in the offline sandbox.
 * Run via jvm-tests/run-tests.sh.
 */
fun main() {
    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean) {
        if (cond) {
            passed++
            println("PASS: $name")
        } else {
            failed++
            println("FAIL: $name")
        }
    }

    // 1. Simple GET parses.
    val get = BridgeHttp.parse("GET /v1/status HTTP/1.1\r\nAuthorization: Bearer abc\r\n\r\n")
    check(
        "GET parses",
        get != null && get.method == "GET" && get.path == "/v1/status" &&
            get.headers["authorization"] == "Bearer abc" && get.body.isEmpty()
    )
    // 2. POST with body + Content-Length parses.
    val body = "{\"command\":\"blast.status\"}"
    val post = BridgeHttp.parse(
        "POST /v1/command HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"
    )
    check("POST with body parses", post != null && post.method == "POST" && post.body == body)
    // 3. Query string decoding.
    val q = BridgeHttp.parse("GET /v1/events?since=12&limit=5 HTTP/1.1\r\n\r\n")
    check("query parses", q != null && q.query["since"] == "12" && q.query["limit"] == "5")
    check("query percent decoding", BridgeHttp.parseQuery("a=hello%20world&b=x+y")["a"] == "hello world")
    // 4. No blank line -> null.
    check("no blank line rejected", BridgeHttp.parse("GET /v1/status HTTP/1.1\r\nAuthorization: x\r\n") == null)
    // 5. Bad request line -> null.
    check("bad request line rejected", BridgeHttp.parse("GARBAGE\r\n\r\n") == null)
    // 6. Header without a colon -> null.
    check("header without colon rejected", BridgeHttp.parse("GET /v1/status HTTP/1.1\r\nNoColonHere\r\n\r\n") == null)
    // 7. Body longer than Content-Length is truncated to it.
    val over = BridgeHttp.parse(
        "POST /v1/command HTTP/1.1\r\nContent-Length: 3\r\n\r\nabcdef"
    )
    check("body truncated to Content-Length", over != null && over.body == "abc")
    // 8. Body shorter than Content-Length -> null.
    check(
        "short body rejected",
        BridgeHttp.parse("POST /v1/command HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc") == null
    )
    // 9. Content-Length over the cap -> null.
    check(
        "oversize body rejected",
        BridgeHttp.parse("POST /v1/command HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n") == null
    )
    // 10. Route table.
    check(
        "route table",
        BridgeHttp.route("GET", "/v1/status") == BridgeHttp.Route.STATUS &&
            BridgeHttp.route("GET", "/v1/events") == BridgeHttp.Route.EVENTS &&
            BridgeHttp.route("GET", "/v1/ram") == BridgeHttp.Route.RAM &&
            BridgeHttp.route("GET", "/v1/dnd") == BridgeHttp.Route.DND &&
            BridgeHttp.route("GET", "/v1/net") == BridgeHttp.Route.NET &&
            BridgeHttp.route("GET", "/v1/health") == BridgeHttp.Route.HEALTH &&
            BridgeHttp.route("POST", "/v1/command") == BridgeHttp.Route.COMMAND &&
            BridgeHttp.route("POST", "/v1/status") == BridgeHttp.Route.METHOD_NOT_ALLOWED &&
            BridgeHttp.route("GET", "/v1/command") == BridgeHttp.Route.METHOD_NOT_ALLOWED &&
            BridgeHttp.route("GET", "/nope") == BridgeHttp.Route.NOT_FOUND
    )
    // 11. Response builder: status line, byte-accurate Content-Length, close.
    val resp = BridgeHttp.buildResponse(200, "OK", "{\"ok\":true}")
    val expectedLen = "{\"ok\":true}".toByteArray(Charsets.UTF_8).size
    check(
        "response builder",
        resp.startsWith("HTTP/1.1 200 OK\r\n") &&
            resp.contains("Content-Length: $expectedLen\r\n") &&
            resp.contains("Connection: close\r\n") &&
            resp.endsWith("{\"ok\":true}")
    )
    // 12. URL decoding.
    check("urlDecode", BridgeHttp.urlDecode("%41+%42") == "A B" && BridgeHttp.urlDecode("a%2Fb") == "a/b")
    // 13. Query edge cases.
    check(
        "query edge cases",
        BridgeHttp.parseQuery("").isEmpty() &&
            BridgeHttp.parseQuery("flag")["flag"] == "" &&
            BridgeHttp.parseQuery("a=1&&b=2").size == 2
    )
    // 14. Header names are lower-cased.
    val h = BridgeHttp.parse("GET /v1/status HTTP/1.1\r\nX-Custom-Header: v\r\n\r\n")
    check("headers lower-cased", h != null && h.headers["x-custom-header"] == "v")

    println("BridgeHttpTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
