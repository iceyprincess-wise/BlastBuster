package com.splendor.blastbuster.dns

import com.splendor.blastbuster.net.EventSink
import com.splendor.blastbuster.net.EventSinks
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.LocalDate

/**
 * Local DNS pipe (DESIGN.md §5.4). The packet loop behind DnsVpnService.
 *
 * Pattern (NetGuard / RethinkDNS / personalDNSfilter): a VpnService
 * that is NOT a VPN. Only DNS traffic enters the tunnel (the builder
 * routes just the upstream IP, no 0.0.0.0/0 route), this loop answers
 * or forwards each query on-device, and no traffic leaves the phone
 * except the forwarded DNS queries to the chosen upstream. There is no
 * external server, no account, no real tunnel.
 *
 * This class is Android-free (java.net / java.io only) so the packet
 * handling is JVM-testable. The service owns the TUN file descriptor
 * and passes plain streams in.
 *
 * Rules:
 * - Only IPv4/UDP to port 53 is handled. Anything else is dropped
 *   silently (this is a DNS filter, not a firewall — design D-07).
 * - Blocked domains are answered locally and are NEVER sent to the
 *   upstream (no leak of "the user looked up a blocked tracker").
 * - Blocked A -> 0.0.0.0, AAAA -> ::, other types -> NXDOMAIN;
 *   "lie" entries -> NXDOMAIN (see DnsParser).
 * - Full query logging is OFF: only counts + top blocked domains are
 *   kept (design §5.4 privacy rule). Stats can be disabled entirely
 *   via [statsEnabled]; filtering still works.
 * - Fail-open: an upstream timeout storm (>10 consecutive failures)
 *   calls [onFailOpen] and stops the loop; the service then tears the
 *   tunnel down and traffic returns to normal. A dead filter must
 *   never black-hole the phone.
 */
class DnsPipe(
    private val tunIn: InputStream,
    private val tunOut: OutputStream,
    blocklist: Blocklist,
    private val upstream: InetAddress,
    private val upstreamPort: Int = 53,
    private val statsEnabled: Boolean = true,
    private val eventSink: EventSink = EventSinks.NOOP,
    private val onFailOpen: ((String) -> Unit)? = null
) {
    companion object {
        const val UPSTREAM_TIMEOUT_MS = 2_000
        const val MAX_CONSECUTIVE_FAILURES = 10
        const val TUN_BUFFER_SIZE = 8192
        const val TOP_BLOCKED_CAP = 50
        private const val DNS_PORT = 53
    }

    /** One packet's worth of IPv4/UDP headers + payload. */
    data class UdpPacket(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val ipId: Int,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    /** Stats snapshot for the DNS screen + bridge. Counts reset daily. */
    data class DnsStatsSnapshot(
        val queries: Long,
        val blocked: Long,
        val forwarded: Long,
        val forwardFailed: Long,
        val droppedNonDns: Long,
        val topBlocked: List<Pair<String, Int>>
    )

    @Volatile
    private var currentBlocklist: Blocklist = blocklist

    @Volatile
    private var stopped = false

    private val socket = DatagramSocket().apply { soTimeout = UPSTREAM_TIMEOUT_MS }

    private val statsLock = Any()
    private var statsDate: String = LocalDate.now().toString()
    private var queries = 0L
    private var blocked = 0L
    private var forwarded = 0L
    private var forwardFailed = 0L
    private var droppedNonDns = 0L
    private var consecutiveFailures = 0
    private val topBlocked = LinkedHashMap<String, Int>()

    /** Swap the blocklist live (user edited entries, or bundled reload). Blast-gated by the service, not here. */
    fun updateBlocklist(newList: Blocklist) {
        currentBlocklist = newList
    }

    /**
     * Stats push callback (service wires it to its companion state).
     * Called at most once per second from the loop thread so the UI
     * and the bridge can read counters without touching the pipe.
     */
    var statsListener: ((DnsStatsSnapshot) -> Unit)? = null
    private var lastStatsPushMs = 0L

    fun statsSnapshot(): DnsStatsSnapshot = synchronized(statsLock) {
        DnsStatsSnapshot(
            queries = queries,
            blocked = blocked,
            forwarded = forwarded,
            forwardFailed = forwardFailed,
            droppedNonDns = droppedNonDns,
            topBlocked = topBlocked.entries
                .sortedByDescending { it.value }.take(5)
                .map { it.key to it.value }
        )
    }

    private fun maybePushStats() {
        val listener = statsListener ?: return
        val now = System.currentTimeMillis()
        if (now - lastStatsPushMs < 1_000L) return
        lastStatsPushMs = now
        try {
            listener(statsSnapshot())
        } catch (_: Exception) {
            // A failing listener must never break the loop.
        }
    }

    /** Blocking read loop. Returns when stop() is called or the tunnel dies. */
    fun runLoop() {
        val buffer = ByteArray(TUN_BUFFER_SIZE)
        while (!stopped) {
            val len = try {
                tunIn.read(buffer)
            } catch (_: Exception) {
                break // tunnel closed / fd gone — exit, service tears down (fail-open)
            }
            if (len <= 0) continue
            try {
                val reply = handlePacket(buffer.copyOf(len))
                if (reply != null) {
                    synchronized(tunOut) {
                        tunOut.write(reply)
                        tunOut.flush()
                    }
                }
                maybePushStats()
            } catch (e: Exception) {
                // One bad packet must never kill the loop.
                countDropped()
            }
        }
    }

    fun stop() {
        stopped = true
        try {
            socket.close()
        } catch (_: Exception) {
        }
        try {
            tunIn.close()
        } catch (_: Exception) {
        }
        try {
            tunOut.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Handle one TUN packet. Returns the reply packet bytes, or null
     * when the packet is dropped (non-DNS, malformed) or when the
     * upstream failed and the reply is a SERVFAIL already counted.
     * (A SERVFAIL reply IS returned on upstream failure — the client
     * fails fast instead of hanging.)
     */
    fun handlePacket(packet: ByteArray): ByteArray? {
        val udp = parseIpv4Udp(packet) ?: run { countDropped(); return null }
        if (udp.dstPort != DNS_PORT) {
            countDropped()
            return null
        }
        val query = DnsParser.parseQuery(udp.payload) ?: run { countDropped(); return null }
        maybeResetDailyStats()
        countQuery()
        val list = currentBlocklist
        if (list.isBlocked(query.name)) {
            countBlocked(query.name)
            val replyDns = if (list.isLie(query.name)) {
                DnsParser.buildNxDomainResponse(query)
            } else {
                DnsParser.buildSinkholeResponse(query)
            }
            return buildIpv4UdpReply(udp, replyDns)
        }
        val replyDns = forwardQuery(udp.payload)
        if (replyDns == null) {
            countForwardFailed()
            consecutiveFailures++
            if (consecutiveFailures > MAX_CONSECUTIVE_FAILURES) {
                val reason = "upstream timeout storm (>${MAX_CONSECUTIVE_FAILURES} consecutive failures)"
                eventSink.log("dns", "DNS_FAIL_OPEN", reason)
                onFailOpen?.invoke(reason)
                stopped = true
            }
            return buildIpv4UdpReply(udp, DnsParser.buildServFailResponse(query))
        }
        consecutiveFailures = 0
        countForwarded()
        return buildIpv4UdpReply(udp, replyDns)
    }

    /** Send the raw query bytes to the upstream and wait for the reply. Null on timeout/error. */
    private fun forwardQuery(queryBytes: ByteArray): ByteArray? {
        return try {
            val out = DatagramPacket(queryBytes, queryBytes.size, upstream, upstreamPort)
            socket.send(out)
            val buf = ByteArray(TUN_BUFFER_SIZE)
            val inPacket = DatagramPacket(buf, buf.size)
            socket.receive(inPacket)
            inPacket.data.copyOf(inPacket.length)
        } catch (_: Exception) {
            null
        }
    }

    // ---------- stats (daily reset, counts only) ----------

    private fun maybeResetDailyStats() {
        val today = LocalDate.now().toString()
        synchronized(statsLock) {
            if (today != statsDate) {
                statsDate = today
                queries = 0; blocked = 0; forwarded = 0; forwardFailed = 0; droppedNonDns = 0
                topBlocked.clear()
            }
        }
    }

    private fun countQuery() {
        if (!statsEnabled) return
        synchronized(statsLock) { queries++ }
    }

    private fun countBlocked(domain: String) {
        if (!statsEnabled) return
        synchronized(statsLock) {
            blocked++
            val lower = domain.lowercase()
            topBlocked[lower] = (topBlocked[lower] ?: 0) + 1
            while (topBlocked.size > TOP_BLOCKED_CAP) {
                val eldest = topBlocked.keys.first()
                topBlocked.remove(eldest)
            }
        }
    }

    private fun countForwarded() {
        if (!statsEnabled) return
        synchronized(statsLock) { forwarded++ }
    }

    private fun countForwardFailed() {
        if (!statsEnabled) return
        synchronized(statsLock) { forwardFailed++ }
    }

    private fun countDropped() {
        if (!statsEnabled) return
        synchronized(statsLock) { droppedNonDns++ }
    }

    // ---------- IPv4/UDP wire helpers (pure byte work, JVM-tested) ----------

    /**
     * Parse an IPv4/UDP packet. Returns null for anything that is not a
     * well-formed IPv4 UDP packet (wrong version, truncated, wrong
     * protocol, bad lengths). IP options (IHL > 5) are honoured.
     */
    fun parseIpv4Udp(packet: ByteArray): UdpPacket? {
        if (packet.size < 28) return null // 20 IP + 8 UDP minimum
        val version = (packet[0].toInt() and 0xFF) ushr 4
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl > packet.size) return null
        val totalLength = u16(packet, 2)
        if (totalLength < ihl + 8 || totalLength > packet.size) return null
        if ((packet[9].toInt() and 0xFF) != 17) return null // not UDP
        val ipId = u16(packet, 4)
        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val udpStart = ihl
        val srcPort = u16(packet, udpStart)
        val dstPort = u16(packet, udpStart + 2)
        val udpLength = u16(packet, udpStart + 4)
        if (udpLength < 8 || udpStart + udpLength > totalLength) return null
        val payload = packet.copyOfRange(udpStart + 8, udpStart + udpLength)
        return UdpPacket(srcIp, dstIp, ipId, srcPort, dstPort, payload)
    }

    /**
     * Build the IPv4/UDP reply for [request] carrying [payload]:
     * addresses and ports swapped, fresh checksums. The UDP checksum
     * is computed properly (pseudo-header) rather than left zero, so
     * strict stacks accept the reply.
     */
    fun buildIpv4UdpReply(request: UdpPacket, payload: ByteArray): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val out = ByteArray(totalLength)
        // IP header.
        out[0] = 0x45.toByte() // version 4, IHL 5
        out[1] = 0
        putU16(out, 2, totalLength)
        putU16(out, 4, request.ipId)
        putU16(out, 6, 0x4000) // Don't Fragment
        out[8] = 64 // TTL
        out[9] = 17 // UDP
        putU16(out, 10, 0) // checksum placeholder
        request.dstIp.copyInto(out, 12)
        request.srcIp.copyInto(out, 16)
        putU16(out, 10, ipv4Checksum(out, 0, 20))
        // UDP header.
        putU16(out, 20, request.dstPort)
        putU16(out, 22, request.srcPort)
        putU16(out, 24, 8 + payload.size)
        putU16(out, 26, 0) // checksum placeholder
        payload.copyInto(out, 28)
        putU16(out, 26, udpChecksum(request.dstIp, request.srcIp, out, 20, 8 + payload.size))
        return out
    }

    /** Standard Internet checksum over [length] bytes at [offset]. */
    fun ipv4Checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += u16(data, i)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** UDP checksum over the pseudo-header + UDP header/payload. 0 is returned as 0xFFFF (a zero checksum is illegal on send). */
    fun udpChecksum(srcIp: ByteArray, dstIp: ByteArray, udp: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0L
        sum += u16(srcIp, 0) + u16(srcIp, 2)
        sum += u16(dstIp, 0) + u16(dstIp, 2)
        sum += 17 // protocol
        sum += udpLength
        var i = udpOffset
        val end = udpOffset + udpLength
        while (i + 1 < end) {
            sum += u16(udp, i)
            i += 2
        }
        if (i < end) sum += (udp[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        val result = (sum.inv() and 0xFFFF).toInt()
        return if (result == 0) 0xFFFF else result
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }
}
