package com.splendor.blastbuster.bridge

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.splendor.blastbuster.BlastService
import com.splendor.blastbuster.Prefs
import com.splendor.blastbuster.companion.BlastGate
import com.splendor.blastbuster.companion.MiniJson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled local HTTP bridge server (DESIGN.md §2.1–2.3, §5.5).
 *
 * Plain `java.net.ServerSocket`, one accept thread + a small fixed
 * thread pool (3 threads max — this is a 4 GB phone). No HTTP library,
 * no JSON library, no dependencies. One request per connection, close
 * after each response.
 *
 * THE POLL DIRECTION IS THE WHOLE DESIGN: the phone never pushes to
 * Muse. Muse polls `GET /v1/status` and `GET /v1/events`, and sends
 * commands with `POST /v1/command`. There is no endpoint on Muse's
 * side to push to, and this server never pretends otherwise — nothing
 * here opens an outbound connection to Muse.
 *
 * Binding: the Tailscale interface address (100.64.0.0/10) and
 * 127.0.0.1, port 8741 fixed. Binding 0.0.0.0 is forbidden — the
 * server is never reachable from Wi-Fi/LAN. If no Tailscale address
 * is present the server still starts on localhost and reports
 * `tailscale: false`. If port 8741 is taken it fails loudly (returns
 * false from [start]) — it never silently picks another port Muse
 * doesn't know about.
 *
 * Auth: `Authorization: Bearer <token>` on EVERY endpoint, including
 * `/v1/health` (a health endpoint without auth is a presence oracle
 * for anyone scanning the tailnet). Wrong/missing token -> 401, and
 * the attempt is counted (never the token value).
 *
 * Blast rule: reads are allowed during a blast; writes are blocked
 * except `blast.stop` (token + stop code). The check is routed
 * through BlastGate (MERGED) — the same rule CompanionService uses,
 * one enforcement point; this server is the enforcement point for
 * bridge writes either way.
 *
 * The server never returns: the token, any code (trigger/stop/voice),
 * any trusted number, or GPS coordinates. `location` is null in v1.2
 * until a location provider is wired at merge (the SMS reply path
 * already owns location; the bridge adds no new location exposure).
 *
 * Modules (RAM/DND/Net/DNS/EventLog) are supplied through the
 * provider interfaces below. Until Agent A/B wire the real modules,
 * the defaults answer honestly with `module_not_available` / empty
 * event lists — never with invented numbers.
 */
class BridgeServer(private val appCtx: Context) {

    companion object {
        const val PORT = 8741
        private const val TAG = "BridgeServer"
        private const val COMMAND_TIMEOUT_MS = 10_000L
        private const val MAX_BODY_BYTES = 64 * 1024
    }

    /** Extra status fields from the companion modules (merged into /v1/status). */
    interface StatusProvider {
        /** JSON object string of extra fields, e.g. `{"ram":{...}}`; null if none. */
        fun extraStatusJson(): String?
    }

    /** EventLog access. Agent A wires the real EventLog at merge. */
    interface EventProvider {
        /** Events with id > [since], oldest first, at most [limit]. */
        fun eventsSince(since: Long, limit: Int): List<Map<String, Any?>>
        /** Monotonic id to pass back as `next_since` (== last id served, or [since]). */
        fun nextSince(since: Long, served: List<Map<String, Any?>>): Long
        /** Total events currently held (for /v1/status counts). */
        fun count(): Long
        /** Append one event. Must never throw. */
        fun append(module: String, type: String, summary: String)
    }

    /** Default providers: honest "nothing wired yet", never fake data. */
    object NoStatusProvider : StatusProvider {
        override fun extraStatusJson(): String? = null
    }

    object NoEventProvider : EventProvider {
        override fun eventsSince(since: Long, limit: Int): List<Map<String, Any?>> = emptyList()
        override fun nextSince(since: Long, served: List<Map<String, Any?>>): Long = since
        override fun count(): Long = 0L
        override fun append(module: String, type: String, summary: String) {}
    }

    var statusProvider: StatusProvider = NoStatusProvider
    var eventProvider: EventProvider = NoEventProvider
    var commandExecutor: BridgeCommands.BridgeCommandExecutor =
        BridgeCommands.UnavailableExecutor

    @Volatile private var running = false
    @Volatile private var startedAtMs = 0L
    @Volatile private var tailscaleAddress: String? = null
    private var sockets: List<ServerSocket> = emptyList()
    private var acceptThreads: List<Thread> = emptyList()
    private var pool: ExecutorService? = null

    fun isRunning(): Boolean = running
    fun uptimeMs(): Long = if (startedAtMs == 0L) 0L else System.currentTimeMillis() - startedAtMs
    fun tailscaleAddress(): String? = tailscaleAddress

    /** Tailscale addresses live in 100.64.0.0/10 (CGNAT range). */
    fun findTailscaleAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address) {
                        val b = addr.address
                        val first = b[0].toInt() and 0xFF
                        val second = b[1].toInt() and 0xFF
                        if (first == 100 && second in 64..127) return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Start listening. Returns true on success. Returns false (and logs)
     * if the port is already taken or binding fails — never silently
     * picks another port.
     */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        tailscaleAddress = findTailscaleAddress()
        val bindAddresses = ArrayList<InetAddress>()
        try {
            bindAddresses.add(InetAddress.getByName("127.0.0.1"))
            val tail = tailscaleAddress
            if (tail != null) bindAddresses.add(InetAddress.getByName(tail))
        } catch (e: Exception) {
            Log.w(TAG, "Bridge bind address lookup failed: ${e.message}")
            return false
        }
        val opened = ArrayList<ServerSocket>()
        return try {
            for (addr in bindAddresses) {
                opened.add(ServerSocket(PORT, 8, addr))
            }
            pool = Executors.newFixedThreadPool(3)
            acceptThreads = opened.map { server ->
                Thread({ acceptLoop(server) }, "bridge-accept-${server.inetAddress.hostAddress}").apply {
                    isDaemon = true
                    start()
                }
            }
            sockets = opened
            startedAtMs = System.currentTimeMillis()
            running = true
            eventProvider.append("bridge", "BRIDGE_STARTED",
                "Bridge listening on port $PORT" +
                    if (tailscaleAddress != null) " (tailnet reachable)" else " (localhost only — Tailscale not up)")
            Log.i(TAG, "Bridge started on port $PORT, tailscale=$tailscaleAddress")
            true
        } catch (e: Exception) {
            for (s in opened) runCatching { s.close() }
            Log.w(TAG, "Bridge failed to start (port $PORT taken?): ${e.message}")
            eventProvider.append("bridge", "BRIDGE_START_FAILED",
                "Bridge could not bind port $PORT — is another server using it?")
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        for (s in sockets) runCatching { s.close() }
        sockets = emptyList()
        acceptThreads = emptyList()
        pool?.shutdownNow()
        pool = null
        eventProvider.append("bridge", "BRIDGE_STOPPED", "Bridge stopped")
        Log.i(TAG, "Bridge stopped")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            try {
                val socket = server.accept()
                pool?.execute { handleConnection(socket) }
            } catch (_: Exception) {
                // ServerSocket closed by stop(): the loop ends with `running`.
                if (!running) return
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        val remoteIp = socket.inetAddress?.hostAddress ?: "unknown"
        try {
            socket.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            // Read head first (up to the blank line), then exactly Content-Length bytes.
            val headBuilder = StringBuilder()
            var line: String?
            var contentLength = 0
            var sawBlankLine = false
            while (true) {
                line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    sawBlankLine = true
                    break
                }
                if (headBuilder.length > 16 * 1024) break
                headBuilder.append(line).append("\r\n")
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            if (!sawBlankLine) {
                writeResponse(socket, 400, "Bad Request", errorJson("bad_request"))
                return
            }
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                writeResponse(socket, 413, "Payload Too Large", errorJson("body_too_large"))
                return
            }
            val bodyChars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(bodyChars, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val raw = headBuilder.toString() + "\r\n" + String(bodyChars, 0, read)
            val request = BridgeHttp.parse(raw, MAX_BODY_BYTES)
            if (request == null) {
                writeResponse(socket, 400, "Bad Request", errorJson("bad_request"))
                return
            }
            val response = dispatch(remoteIp, request)
            writeResponse(socket, response.first, response.second, response.third)
        } catch (e: Exception) {
            Log.w(TAG, "Bridge connection error from $remoteIp: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun writeResponse(socket: Socket, status: Int, reason: String, jsonBody: String) {
        try {
            val bytes = BridgeHttp.buildResponse(status, reason, jsonBody).toByteArray(Charsets.UTF_8)
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        } catch (_: Exception) {
        }
    }

    /** Returns (status, reason, jsonBody). Package-visible for tests via dispatch. */
    internal fun dispatch(remoteIp: String, request: BridgeHttp.HttpRequest): Triple<Int, String, String> {
        // Rate limit first: a blocked IP gets no answer at all beyond 429.
        if (BridgeAuth.isIpBlocked(remoteIp)) {
            return Triple(429, "Too Many Requests", errorJson("rate_limited"))
        }
        val authHeader = request.headers["authorization"]
        if (!BridgeAuth.verify(appCtx, authHeader)) {
            BridgeAuth.recordFailedAuth(remoteIp)
            eventProvider.append("bridge", "BRIDGE_AUTH_FAILED",
                "Rejected bridge request with a wrong or missing token")
            return Triple(401, "Unauthorized", errorJson("unauthorized"))
        }
        BridgeAuth.recordSuccessfulAuth(remoteIp)
        BridgeAuth.setLastPollAt(appCtx, System.currentTimeMillis())

        return when (BridgeHttp.route(request.method, request.path)) {
            BridgeHttp.Route.STATUS -> Triple(200, "OK", statusJson())
            BridgeHttp.Route.HEALTH -> Triple(200, "OK", MiniJson.obj("ok" to true))
            BridgeHttp.Route.EVENTS -> handleEvents(request)
            BridgeHttp.Route.RAM -> moduleJson("ram")
            BridgeHttp.Route.DND -> moduleJson("dnd")
            BridgeHttp.Route.NET -> moduleJson("net")
            BridgeHttp.Route.COMMAND -> handleCommand(request)
            BridgeHttp.Route.NOT_FOUND -> Triple(404, "Not Found", errorJson("not_found"))
            BridgeHttp.Route.METHOD_NOT_ALLOWED -> Triple(405, "Method Not Allowed", errorJson("method_not_allowed"))
        }
    }

    private fun moduleJson(module: String): Triple<Int, String, String> {
        // Module detail endpoints come from the StatusProvider fragment
        // once the modules are wired; until then, say so honestly.
        val extra = statusProvider.extraStatusJson()
        if (extra == null) {
            return Triple(200, "OK", MiniJson.obj("ok" to false, "error" to "module_not_available"))
        }
        return try {
            val parsed = MiniJson.parseObj(extra) ?: return Triple(
                200, "OK", MiniJson.obj("ok" to false, "error" to "module_not_available")
            )
            val fragment = parsed[module]
            if (fragment == null) {
                Triple(200, "OK", MiniJson.obj("ok" to false, "error" to "module_not_available"))
            } else {
                Triple(200, "OK", MiniJson.obj("ok" to true, "result" to fragment))
            }
        } catch (_: Exception) {
            Triple(200, "OK", MiniJson.obj("ok" to false, "error" to "module_not_available"))
        }
    }

    private fun handleEvents(request: BridgeHttp.HttpRequest): Triple<Int, String, String> {
        val since = request.query["since"]?.toLongOrNull() ?: 0L
        val limit = (request.query["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 100)
        val events = try {
            eventProvider.eventsSince(since, limit)
        } catch (_: Exception) {
            emptyList<Map<String, Any?>>()
        }
        val nextSince = try {
            eventProvider.nextSince(since, events)
        } catch (_: Exception) {
            since
        }
        val body = MiniJson.obj(
            "ok" to true,
            "events" to events,
            "next_since" to nextSince
        )
        return Triple(200, "OK", body)
    }

    private fun handleCommand(request: BridgeHttp.HttpRequest): Triple<Int, String, String> {
        val parsed = MiniJson.parseObj(request.body)
            ?: return Triple(400, "Bad Request", errorJson("bad_json"))
        val command = MiniJson.getString(parsed, "command") ?: ""
        val args = MiniJson.getMap(parsed, "args") ?: emptyMap()
        val code = MiniJson.getString(parsed, "code")
        val req = BridgeCommands.CommandRequest(command, args, code)
        val blasting = BlastGate.isBlasting(appCtx)
        val validation = BridgeCommands.validate(req, blasting)
        if (!validation.ok) {
            logCommand(req.command, false, validation.error)
            return Triple(200, "OK", MiniJson.obj(
                "ok" to false, "error" to (validation.error ?: "rejected")
            ))
        }
        when (req.command) {
            "blast.status" -> {
                logCommand(req.command, true, null)
                return Triple(200, "OK", MiniJson.obj("ok" to true, "result" to MiniJson.Raw(statusJson())))
            }
            "blast.stop" -> {
                if (!blasting) {
                    logCommand(req.command, false, "not_blasting")
                    return Triple(200, "OK", MiniJson.obj("ok" to false, "error" to "not_blasting"))
                }
                val supplied = req.code ?: ""
                if (!Prefs.matchesCode(supplied, Prefs.stopCode(appCtx))) {
                    logCommand(req.command, false, "wrong_code")
                    return Triple(200, "OK", MiniJson.obj("ok" to false, "error" to "wrong_code"))
                }
                return try {
                    BlastService.stopBlast(appCtx)
                    logCommand(req.command, true, null)
                    Triple(200, "OK", MiniJson.obj("ok" to true, "result" to "blast_stopped"))
                } catch (e: Exception) {
                    logCommand(req.command, false, "stop_failed")
                    Triple(200, "OK", MiniJson.obj("ok" to false, "error" to "stop_failed"))
                }
            }
        }
        // Module commands run on a worker with a 10 s timeout; a timed-out
        // command returns "timeout" and is logged — never retried blindly.
        return try {
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future: Future<BridgeCommands.ExecutorResult> =
                    executor.submit<BridgeCommands.ExecutorResult> { commandExecutor.execute(req) }
                val result = future.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                logCommand(req.command, result.ok, result.error)
                if (result.ok) {
                    Triple(200, "OK", MiniJson.obj(
                        "ok" to true,
                        "result" to (result.resultJson?.let { MiniJson.Raw(it) } ?: "done")
                    ))
                } else {
                    Triple(200, "OK", MiniJson.obj(
                        "ok" to false, "error" to (result.error ?: "command_failed")
                    ))
                }
            } finally {
                executor.shutdownNow()
            }
        } catch (_: java.util.concurrent.TimeoutException) {
            logCommand(req.command, false, "timeout")
            Triple(200, "OK", MiniJson.obj("ok" to false, "error" to "timeout"))
        } catch (_: Exception) {
            logCommand(req.command, false, "command_failed")
            Triple(200, "OK", MiniJson.obj("ok" to false, "error" to "command_failed"))
        }
    }

    private fun logCommand(command: String, ok: Boolean, error: String?) {
        // Command name + outcome only — never args (they may hold a code).
        val summary = "Bridge command $command: " + if (ok) "ok" else "failed (${error ?: "unknown"})"
        try {
            eventProvider.append("bridge", "BRIDGE_CMD", summary)
        } catch (_: Exception) {
        }
        Log.i(TAG, summary)
    }

    /** Full /v1/status snapshot. Never triggers a scan — a poll must be cheap. */
    fun statusJson(): String {
        val batteryPct = try {
            val bm = appCtx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            -1
        }
        val fields = LinkedHashMap<String, Any?>()
        fields["ok"] = true
        fields["armed"] = Prefs.isArmed(appCtx)
        fields["blasting"] = BlastGate.isBlasting(appCtx)
        fields["battery_pct"] = if (batteryPct in 0..100) batteryPct else null
        fields["bridge_uptime_ms"] = uptimeMs()
        fields["bridge_running"] = running
        fields["tailscale"] = tailscaleAddress != null
        fields["tailscale_address"] = tailscaleAddress
        fields["port"] = PORT
        fields["location"] = null // No new location exposure in v1.2 (§5.5).
        fields["events_count"] = try {
            eventProvider.count()
        } catch (_: Exception) {
            0L
        }
        fields["last_poll_at_ms"] = BridgeAuth.getLastPollAt(appCtx)
        val extra = try {
            statusProvider.extraStatusJson()
        } catch (_: Exception) {
            null
        }
        if (extra != null) {
            val parsed = MiniJson.parseObj(extra)
            if (parsed != null) fields.putAll(parsed)
        }
        return MiniJson.obj(fields)
    }

    /** Fragment for CompanionService's /v1/status merge (§4.8 statusJson). */
    fun statusFragmentJson(): String = MiniJson.obj(
        "bridge_enabled" to BridgeAuth.isEnabled(appCtx),
        "bridge_running" to running,
        "bridge_port" to PORT,
        "bridge_tailscale" to (tailscaleAddress != null),
        "bridge_uptime_ms" to uptimeMs()
    )

    private fun errorJson(error: String): String =
        MiniJson.obj("ok" to false, "error" to error)
}
