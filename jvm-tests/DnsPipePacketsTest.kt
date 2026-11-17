import com.splendor.blastbuster.dns.Blocklist
import com.splendor.blastbuster.dns.DnsParser
import com.splendor.blastbuster.dns.DnsPipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

// JVM unit tests for DnsPipe packet handling (DESIGN.md §5.4).
// Standalone main() runner — no JUnit dependency (see run-tests.sh).
//
// NOTE on scope: runLoop() itself is not exercised here — it reads
// one packet per read() call, which holds for TUN file descriptors
// (packet boundaries preserved, the standard VpnService pattern) but
// NOT for PipedInputStream, so a pipe-based loop test would test the
// wrong thing. handlePacket() below is the real logic, tested with a
// fake upstream UDP server on localhost.

private var passed = 0
private var failed = 0
private var skipped = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
        println("  PASS: $name")
    } else {
        failed++
        println("  FAIL: $name")
    }
}

private fun skip(name: String, reason: String) {
    skipped++
    println("  SKIP: $name ($reason)")
}

/**
 * Probe whether this JVM can actually send UDP. The sandbox blocks
 * Java UDP send (SocketException: Operation not permitted) — the same
 * sandbox that blocks Java TCP (see TOOLS.md). On a real phone
 * DatagramSocket send works fine, so a blocked probe means the
 * live-forwarding checks below are SKIPPED here, not failed: the
 * pipe's local decisions (block/sinkhole/NXDOMAIN/SERVFAIL) are still
 * fully tested, and the forwarding path is verified on-device
 * (design test D-12) instead.
 */
private fun probeUdpSendBlocked(): Boolean {
    var server: DatagramSocket? = null
    var client: DatagramSocket? = null
    return try {
        server = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        client = DatagramSocket().apply { soTimeout = 500 }
        client.send(
            DatagramPacket(
                "p".toByteArray(), 1,
                InetAddress.getByName("127.0.0.1"), server.localPort
            )
        )
        false
    } catch (_: Exception) {
        true
    } finally {
        try { client?.close() } catch (_: Exception) { }
        try { server?.close() } catch (_: Exception) { }
    }
}

private fun u16(v: Int): ByteArray =
    byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

private fun buildQuery(id: Int, name: String, type: Int): ByteArray {
    val out = mutableListOf<Byte>()
    fun add(bytes: ByteArray) = bytes.forEach { out.add(it) }
    add(u16(id)); add(u16(0x0100)); add(u16(1)); add(u16(0)); add(u16(0)); add(u16(0))
    add(DnsParser.encodeName(name))
    add(u16(type)); add(u16(DnsParser.CLASS_IN))
    return out.toByteArray()
}

/** Build a well-formed IPv4/UDP packet carrying [payload]. */
private fun buildIpPacket(
    pipe: DnsPipe,
    srcIp: ByteArray, dstIp: ByteArray,
    srcPort: Int, dstPort: Int, payload: ByteArray
): ByteArray {
    val total = 20 + 8 + payload.size
    val out = ByteArray(total)
    out[0] = 0x45.toByte()
    out[1] = 0
    out[2] = ((total ushr 8) and 0xFF).toByte(); out[3] = (total and 0xFF).toByte()
    out[4] = 0x12; out[5] = 0x34 // IP id
    out[6] = 0x40; out[7] = 0 // Don't Fragment
    out[8] = 64; out[9] = 17 // TTL, UDP
    srcIp.copyInto(out, 12); dstIp.copyInto(out, 16)
    val csum = pipe.ipv4Checksum(out, 0, 20)
    out[10] = ((csum ushr 8) and 0xFF).toByte(); out[11] = (csum and 0xFF).toByte()
    out[20] = ((srcPort ushr 8) and 0xFF).toByte(); out[21] = (srcPort and 0xFF).toByte()
    out[22] = ((dstPort ushr 8) and 0xFF).toByte(); out[23] = (dstPort and 0xFF).toByte()
    val udpLen = 8 + payload.size
    out[24] = ((udpLen ushr 8) and 0xFF).toByte(); out[25] = (udpLen and 0xFF).toByte()
    payload.copyInto(out, 28)
    val ucsum = pipe.udpChecksum(srcIp, dstIp, out, 20, udpLen)
    out[26] = ((ucsum ushr 8) and 0xFF).toByte(); out[27] = (ucsum and 0xFF).toByte()
    return out
}

/** Parsed DNS response: id, rcode, answer rdatas. */
private data class DnsResponse(val id: Int, val rcode: Int, val anCount: Int, val rdatas: List<ByteArray>)

private fun parseDnsResponse(packet: ByteArray, pipe: DnsPipe): DnsResponse? {
    val udp = pipe.parseIpv4Udp(packet) ?: return null
    val dns = udp.payload
    if (dns.size < 12) return null
    val id = DnsParser.u16(dns, 0)
    val flags = DnsParser.u16(dns, 2)
    val anCount = DnsParser.u16(dns, 6)
    val (_, afterName) = DnsParser.decodeName(dns, 12) ?: return null
    var pos = afterName + 4 // skip QTYPE/QCLASS
    val rdatas = mutableListOf<ByteArray>()
    repeat(anCount) {
        if (pos + 12 > dns.size) return null
        // NAME may be a compression pointer (2 bytes) or labels.
        val first = dns[pos].toInt() and 0xFF
        pos += if ((first and 0xC0) == 0xC0) 2 else {
            val (_, after) = DnsParser.decodeName(dns, pos) ?: return null
            after - pos
        }
        val rdLen = DnsParser.u16(dns, pos + 8)
        if (pos + 10 + rdLen > dns.size) return null
        rdatas.add(dns.copyOfRange(pos + 10, pos + 10 + rdLen))
        pos += 10 + rdLen
    }
    return DnsResponse(id, flags and 0xF, anCount, rdatas)
}

/** Fake upstream: answers every A query with 1.2.3.4, counts queries. */
private class FakeUpstream {
    val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    @Volatile var received = 0
    private val thread = Thread {
        val buf = ByteArray(4096)
        while (!socket.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                received++
                val q = DnsParser.parseQuery(p.data.copyOf(p.length)) ?: continue
                val resp = DnsParser.buildResponse(
                    q, listOf(DnsParser.DnsAnswer(DnsParser.TYPE_A, 300, byteArrayOf(1, 2, 3, 4)))
                )
                socket.send(DatagramPacket(resp, resp.size, p.address, p.port))
            } catch (_: Exception) {
                break
            }
        }
    }.apply { isDaemon = true; start() }
    fun stop() {
        try { socket.close() } catch (_: Exception) { }
    }
}

private fun makePipe(blocklist: Blocklist, upstream: InetAddress, port: Int): DnsPipe =
    DnsPipe(
        tunIn = ByteArrayInputStream(ByteArray(0)),
        tunOut = ByteArrayOutputStream(),
        blocklist = blocklist,
        upstream = upstream,
        upstreamPort = port,
        statsEnabled = true
    )

fun main() {
    println("DnsPipePacketsTest")
    val udpSendBlocked = probeUdpSendBlocked()
    println("  INFO: UDP send blocked in this environment: $udpSendBlocked")
    val phoneIp = byteArrayOf(10, 0, 0, 2)
    val dnsIp = byteArrayOf(1, 1, 1, 1)
    val localhost = InetAddress.getByName("127.0.0.1")

    // --- parseIpv4Udp: valid packet ---
    val probePipe = makePipe(Blocklist.EMPTY, localhost, 53)
    val probePayload = buildQuery(0x1111, "example.com", DnsParser.TYPE_A)
    val probePacket = buildIpPacket(probePipe, phoneIp, dnsIp, 12345, 53, probePayload)
    val parsed = probePipe.parseIpv4Udp(probePacket)
    check("parse valid IPv4/UDP", parsed != null)
    check("parse src/dst ports", parsed?.srcPort == 12345 && parsed.dstPort == 53)
    check("parse payload intact", parsed?.payload?.contentEquals(probePayload) == true)
    check("parse ip id kept", parsed?.ipId == 0x1234)

    // --- parseIpv4Udp: rejects ---
    check("reject short packet", probePipe.parseIpv4Udp(ByteArray(10)) == null)
    check("reject IPv6 version byte", run {
        val bad = probePacket.copyOf()
        bad[0] = 0x65.toByte()
        probePipe.parseIpv4Udp(bad) == null
    })
    check("reject non-UDP protocol", run {
        val bad = probePacket.copyOf()
        bad[9] = 6 // TCP
        probePipe.parseIpv4Udp(bad) == null
    })
    check("reject truncated total length", run {
        val bad = probePacket.copyOf()
        bad[2] = 0; bad[3] = 5 // total length 5
        probePipe.parseIpv4Udp(bad) == null
    })

    // --- buildIpv4UdpReply: swap + valid checksums ---
    check("reply swaps addresses and ports", run {
        val reply = probePipe.buildIpv4UdpReply(parsed!!, probePayload)
        val back = probePipe.parseIpv4Udp(reply)
        back != null && back.srcPort == 53 && back.dstPort == 12345 &&
            back.srcIp.contentEquals(dnsIp) && back.dstIp.contentEquals(phoneIp) &&
            back.payload.contentEquals(probePayload)
    })
    check("reply IP checksum valid", run {
        val reply = probePipe.buildIpv4UdpReply(parsed!!, probePayload)
        probePipe.ipv4Checksum(reply, 0, 20) == 0
    })
    check("reply UDP checksum valid", run {
        val reply = probePipe.buildIpv4UdpReply(parsed!!, probePayload)
        // Recompute over the reply UDP section with the stored checksum in
        // place: a valid checksum sums to 0 (one's complement).
        // udpChecksum() computes the checksum for a zeroed field, so
        // verify instead by zeroing and recomparing.
        val stored = DnsParser.u16(reply, 26)
        val zeroed = reply.copyOf()
        zeroed[26] = 0; zeroed[27] = 0
        val recomputed = probePipe.udpChecksum(dnsIp, phoneIp, zeroed, 20, 8 + probePayload.size)
        stored == recomputed && stored != 0
    })

    // --- blocked A query: sinkhole, upstream never sees it ---
    val upstream = FakeUpstream()
    try {
        val blockedList = Blocklist.fromBlockLines(listOf("ads.example.com", "*.tracker.example"))
            .withLie(listOf("lie.example.com"))
        val pipe = makePipe(blockedList, localhost, upstream.port)

        val blockedPacket = buildIpPacket(pipe, phoneIp, dnsIp, 40001, 53,
            buildQuery(0x2222, "ads.example.com", DnsParser.TYPE_A))
        val blockedReply = pipe.handlePacket(blockedPacket)
        check("blocked A gets a reply", blockedReply != null)
        val blockedResp = blockedReply?.let { parseDnsResponse(it, pipe) }
        check("blocked A reply id echoes", blockedResp?.id == 0x2222)
        check("blocked A reply is 0.0.0.0", blockedResp?.rdatas?.firstOrNull()
            ?.contentEquals(byteArrayOf(0, 0, 0, 0)) == true)
        check("blocked query never reaches upstream", upstream.received == 0)
        check("blocked counted in stats", pipe.statsSnapshot().blocked == 1L)

        // --- blocked AAAA: :: ---
        val blocked6 = buildIpPacket(pipe, phoneIp, dnsIp, 40002, 53,
            buildQuery(0x2223, "sub.tracker.example", DnsParser.TYPE_AAAA))
        val resp6 = pipe.handlePacket(blocked6)?.let { parseDnsResponse(it, pipe) }
        check("blocked AAAA reply is ::", resp6?.rdatas?.firstOrNull()?.size == 16 &&
            resp6.rdatas.firstOrNull()?.all { b -> b == 0.toByte() } == true)
        check("upstream still untouched after 2 blocks", upstream.received == 0)

        // --- lie entry: NXDOMAIN ---
        val liePacket = buildIpPacket(pipe, phoneIp, dnsIp, 40003, 53,
            buildQuery(0x2224, "lie.example.com", DnsParser.TYPE_A))
        val lieResp = pipe.handlePacket(liePacket)?.let { parseDnsResponse(it, pipe) }
        check("lie entry gets NXDOMAIN", lieResp?.rcode == 3 && lieResp.anCount == 0)

        // --- allowed query: forwarded, reply relayed ---
        // (When this sandbox blocks UDP send, the pipe answers SERVFAIL
        // instead — that is the environment, not the code — so the
        // relay assertions are skipped and the SERVFAIL is asserted.)
        val allowedPacket = buildIpPacket(pipe, phoneIp, dnsIp, 40004, 53,
            buildQuery(0x2225, "example.com", DnsParser.TYPE_A))
        val allowedReply = pipe.handlePacket(allowedPacket)
        check("allowed query gets a reply", allowedReply != null)
        val allowedResp = allowedReply?.let { parseDnsResponse(it, pipe) }
        check("allowed reply id echoes", allowedResp?.id == 0x2225)
        if (udpSendBlocked) {
            check("allowed query gets SERVFAIL when UDP send is blocked (environment)",
                allowedResp?.rcode == 2)
            skip("allowed reply carries upstream answer 1.2.3.4",
                "sandbox blocks UDP send; verify on-device (D-12)")
            skip("upstream received exactly 1 query",
                "sandbox blocks UDP send; verify on-device (D-12)")
        } else {
            check("allowed reply carries upstream answer 1.2.3.4",
                allowedResp?.rdatas?.firstOrNull()?.contentEquals(byteArrayOf(1, 2, 3, 4)) == true)
            check("upstream received exactly 1 query", upstream.received == 1)
        }
        val stats = pipe.statsSnapshot()
        check("stats: queries=4 blocked=3",
            stats.queries == 4L && stats.blocked == 3L)
        if (udpSendBlocked) {
            check("stats: forwarded=0 forwardFailed=1 when UDP blocked",
                stats.forwarded == 0L && stats.forwardFailed == 1L)
        } else {
            check("stats: forwarded=1 forwardFailed=0",
                stats.forwarded == 1L && stats.forwardFailed == 0L)
        }
        check("stats top blocked lists ads.example.com",
            stats.topBlocked.any { (domain, count) -> domain == "ads.example.com" && count == 1 })

        // --- non-DNS traffic dropped ---
        val webPacket = buildIpPacket(pipe, phoneIp, dnsIp, 40005, 80, "GET / HTTP/1.0\r\n\r\n".toByteArray())
        check("port-80 packet dropped (null reply)", pipe.handlePacket(webPacket) == null)
        check("dropped counted", pipe.statsSnapshot().droppedNonDns == 1L)

        // --- malformed DNS payload dropped ---
        val malformed = buildIpPacket(pipe, phoneIp, dnsIp, 40006, 53, byteArrayOf(1, 2, 3))
        check("malformed DNS dropped", pipe.handlePacket(malformed) == null)

        // --- updateBlocklist takes effect live ---
        pipe.updateBlocklist(Blocklist.EMPTY)
        val nowAllowed = buildIpPacket(pipe, phoneIp, dnsIp, 40007, 53,
            buildQuery(0x2226, "ads.example.com", DnsParser.TYPE_A))
        val before = upstream.received
        val nowReply = pipe.handlePacket(nowAllowed)?.let { parseDnsResponse(it, pipe) }
        if (udpSendBlocked) {
            skip("after list cleared, formerly-blocked host is forwarded",
                "sandbox blocks UDP send; verify on-device (D-12)")
            val first = nowReply?.rdatas?.firstOrNull()
            check("after list cleared, formerly-blocked host is no longer sinkholed",
                first == null || !first.contentEquals(byteArrayOf(0, 0, 0, 0)))
        } else {
            check("after list cleared, formerly-blocked host is forwarded",
                nowReply?.rdatas?.firstOrNull()?.contentEquals(byteArrayOf(1, 2, 3, 4)) == true &&
                    upstream.received == before + 1)
        }
        pipe.stop()
    } finally {
        upstream.stop()
        probePipe.stop()
    }

    // --- upstream dead: SERVFAIL, fail-open counter trips after 11 ---
    run {
        var failOpenCalled = false
        val deadPipe = DnsPipe(
            tunIn = ByteArrayInputStream(ByteArray(0)),
            tunOut = ByteArrayOutputStream(),
            blocklist = Blocklist.EMPTY,
            upstream = localhost,
            upstreamPort = 59999, // nothing listens here
            statsEnabled = true,
            onFailOpen = { failOpenCalled = true }
        )
        val deadPacket = buildIpPacket(deadPipe, phoneIp, dnsIp, 40008, 53,
            buildQuery(0x3333, "example.com", DnsParser.TYPE_A))
        val t0 = System.currentTimeMillis()
        val servFail = deadPipe.handlePacket(deadPacket)?.let { parseDnsResponse(it, deadPipe) }
        val tookMs = System.currentTimeMillis() - t0
        check("dead upstream -> SERVFAIL reply", servFail?.rcode == 2)
        check("dead upstream counted as forward failure",
            deadPipe.statsSnapshot().forwardFailed == 1L)
        println("  INFO: dead-upstream query took ${tookMs} ms (timeout is 2000 ms)")
        // Trip the storm guard: 11 consecutive failures.
        repeat(10) { deadPipe.handlePacket(deadPacket) }
        check("fail-open fires after >10 consecutive failures", failOpenCalled)
        deadPipe.stop()
    }

    println("RESULT DnsPipePacketsTest: $passed passed, $failed failed, $skipped skipped")
    if (failed > 0) kotlin.system.exitProcess(1)
}
