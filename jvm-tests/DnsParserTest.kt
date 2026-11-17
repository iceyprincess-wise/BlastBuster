import com.splendor.blastbuster.dns.DnsParser

// JVM unit tests for DnsParser (DESIGN.md §7.1: 20 tests aimed).
// Standalone main() runner — no JUnit dependency (see run-tests.sh).
// Run: java -cp <classes>:<stdlib> DnsParserTestKt

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
        println("  PASS: $name")
    } else {
        failed++
        println("  FAIL: $name")
    }
}

private fun u16(v: Int): ByteArray =
    byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

private fun buildQuery(id: Int, name: String, type: Int): ByteArray {
    val out = mutableListOf<Byte>()
    fun add(bytes: ByteArray) = bytes.forEach { out.add(it) }
    add(u16(id))
    add(u16(0x0100)) // RD set, query
    add(u16(1)) // QDCOUNT
    add(u16(0)); add(u16(0)); add(u16(0))
    add(DnsParser.encodeName(name))
    add(u16(type))
    add(u16(DnsParser.CLASS_IN))
    return out.toByteArray()
}

fun main() {
    println("DnsParserTest")

    // --- parse A / AAAA ---
    val qA = DnsParser.parseQuery(buildQuery(0x1234, "example.com", DnsParser.TYPE_A))
    check("parse A: not null", qA != null)
    check("parse A: id", qA?.id == 0x1234)
    check("parse A: name", qA?.name == "example.com")
    check("parse A: type", qA?.type == DnsParser.TYPE_A)
    check("parse A: class IN", qA?.qclass == DnsParser.CLASS_IN)

    val qAaaa = DnsParser.parseQuery(buildQuery(0xABCD, "ads.example.org", DnsParser.TYPE_AAAA))
    check("parse AAAA: name+type", qAaaa?.name == "ads.example.org" && qAaaa.type == DnsParser.TYPE_AAAA)

    val qMx = DnsParser.parseQuery(buildQuery(7, "mail.example.net", DnsParser.TYPE_MX))
    check("parse MX", qMx?.name == "mail.example.net" && qMx.type == DnsParser.TYPE_MX)

    // --- malformed packets rejected ---
    check("reject empty", DnsParser.parseQuery(ByteArray(0)) == null)
    check("reject 5 bytes", DnsParser.parseQuery(ByteArray(5)) == null)
    check("reject response packet (QR set)", run {
        val bytes = buildQuery(1, "example.com", DnsParser.TYPE_A)
        bytes[2] = 0x81.toByte() // QR=1
        bytes[3] = 0x80.toByte()
        DnsParser.parseQuery(bytes) == null
    })
    check("reject QDCOUNT 0", run {
        val out = mutableListOf<Byte>()
        fun add(b: ByteArray) = b.forEach { out.add(it) }
        add(u16(1)); add(u16(0x0100)); add(u16(0)); add(u16(0)); add(u16(0)); add(u16(0))
        DnsParser.parseQuery(out.toByteArray()) == null
    })
    check("reject truncated name", run {
        val bytes = buildQuery(1, "example.com", DnsParser.TYPE_A)
        DnsParser.parseQuery(bytes.copyOf(14)) == null // header + 2 bytes of name
    })
    check("reject truncated question (no QTYPE/QCLASS)", run {
        val bytes = buildQuery(1, "example.com", DnsParser.TYPE_A)
        DnsParser.parseQuery(bytes.copyOf(bytes.size - 2)) == null
    })
    check("reject pointer loop", run {
        // Question name is a pointer to itself (offset 12).
        val out = mutableListOf<Byte>()
        fun add(b: ByteArray) = b.forEach { out.add(it) }
        add(u16(0x9999)); add(u16(0x0100)); add(u16(1)); add(u16(0)); add(u16(0)); add(u16(0))
        add(byteArrayOf(0xC0.toByte(), 0x0C.toByte()))
        add(u16(DnsParser.TYPE_A)); add(u16(DnsParser.CLASS_IN))
        DnsParser.parseQuery(out.toByteArray()) == null
    })

    // --- compression pointer in a real question ---
    check("parse name with compression pointer", run {
        // "www" label + pointer to "example.com" written at offset 12... but
        // the question must start at 12. Craft: first a dummy answer-free
        // layout is impossible in a query, so instead put the full name at
        // 12 and a pointer-based second label is not possible for QDCOUNT=1.
        // Direct decodeName test instead:
        val packet = ByteArray(64)
        // offset 12: "example" "com" 0
        val nameBytes = DnsParser.encodeName("example.com")
        nameBytes.copyInto(packet, 12)
        // offset 30: "www" + pointer to 12
        packet[30] = 3
        packet[31] = 'w'.code.toByte(); packet[32] = 'w'.code.toByte(); packet[33] = 'w'.code.toByte()
        packet[34] = 0xC0.toByte(); packet[35] = 0x0C.toByte()
        val decoded = DnsParser.decodeName(packet, 30)
        decoded?.first == "www.example.com" && decoded.second == 36
    })

    // --- encode/decode round-trip ---
    check("encode/decode round-trip", run {
        val encoded = DnsParser.encodeName("a.b.example.com")
        val decoded = DnsParser.decodeName(encoded, 0)
        decoded?.first == "a.b.example.com" && decoded.second == encoded.size
    })
    check("encode root", DnsParser.encodeName(".").contentEquals(byteArrayOf(0)))
    check("encode single label", run {
        val decoded = DnsParser.decodeName(DnsParser.encodeName("localhost"), 0)
        decoded?.first == "localhost"
    })

    // --- buildResponse: structure + echo ---
    check("buildResponse echoes id + question", run {
        val q = DnsParser.parseQuery(buildQuery(0x5555, "example.com", DnsParser.TYPE_A))!!
        val resp = DnsParser.buildResponse(
            q, listOf(DnsParser.DnsAnswer(DnsParser.TYPE_A, 300, byteArrayOf(1, 2, 3, 4)))
        )
        val id = DnsParser.u16(resp, 0)
        val flags = DnsParser.u16(resp, 2)
        val qd = DnsParser.u16(resp, 4)
        val an = DnsParser.u16(resp, 6)
        val questionEcho = resp.copyOfRange(12, 12 + q.rawQuestion.size)
        id == 0x5555 && flags == 0x8180 && qd == 1 && an == 1 &&
            questionEcho.contentEquals(q.rawQuestion)
    })
    check("buildResponse answer rdata is 1.2.3.4", run {
        val q = DnsParser.parseQuery(buildQuery(1, "example.com", DnsParser.TYPE_A))!!
        val resp = DnsParser.buildResponse(
            q, listOf(DnsParser.DnsAnswer(DnsParser.TYPE_A, 300, byteArrayOf(1, 2, 3, 4)))
        )
        // Answer starts after header(12) + question.
        val ansOff = 12 + q.rawQuestion.size
        val namePtr = DnsParser.u16(resp, ansOff)
        val type = DnsParser.u16(resp, ansOff + 2)
        val ttl = ((resp[ansOff + 6].toInt() and 0xFF) shl 24) or
            ((resp[ansOff + 7].toInt() and 0xFF) shl 16) or
            ((resp[ansOff + 8].toInt() and 0xFF) shl 8) or
            (resp[ansOff + 9].toInt() and 0xFF)
        val rdLen = DnsParser.u16(resp, ansOff + 10)
        val rdata = resp.copyOfRange(ansOff + 12, ansOff + 12 + rdLen)
        namePtr == 0xC00C && type == DnsParser.TYPE_A && ttl == 300 &&
            rdLen == 4 && rdata.contentEquals(byteArrayOf(1, 2, 3, 4))
    })

    // --- sinkhole / NXDOMAIN / SERVFAIL / NODATA ---
    check("sinkhole A -> 0.0.0.0", run {
        val q = DnsParser.parseQuery(buildQuery(2, "ads.example.com", DnsParser.TYPE_A))!!
        val resp = DnsParser.buildSinkholeResponse(q)
        val ansOff = 12 + q.rawQuestion.size
        val rdLen = DnsParser.u16(resp, ansOff + 10)
        val rdata = resp.copyOfRange(ansOff + 12, ansOff + 12 + rdLen)
        DnsParser.u16(resp, 6) == 1 && rdata.contentEquals(byteArrayOf(0, 0, 0, 0))
    })
    check("sinkhole AAAA -> :: (16 zeros)", run {
        val q = DnsParser.parseQuery(buildQuery(3, "ads.example.com", DnsParser.TYPE_AAAA))!!
        val resp = DnsParser.buildSinkholeResponse(q)
        val ansOff = 12 + q.rawQuestion.size
        val rdLen = DnsParser.u16(resp, ansOff + 10)
        val rdata = resp.copyOfRange(ansOff + 12, ansOff + 12 + rdLen)
        DnsParser.u16(resp, 6) == 1 && rdLen == 16 && rdata.all { it == 0.toByte() }
    })
    check("sinkhole MX -> NODATA (no answers, NOERROR)", run {
        val q = DnsParser.parseQuery(buildQuery(4, "ads.example.com", DnsParser.TYPE_MX))!!
        val resp = DnsParser.buildSinkholeResponse(q)
        DnsParser.u16(resp, 2) == 0x8180 && DnsParser.u16(resp, 6) == 0
    })
    check("NXDOMAIN flags 0x8183, no answers", run {
        val q = DnsParser.parseQuery(buildQuery(5, "nope.example.com", DnsParser.TYPE_A))!!
        val resp = DnsParser.buildNxDomainResponse(q)
        DnsParser.u16(resp, 2) == 0x8183 && DnsParser.u16(resp, 6) == 0 &&
            DnsParser.u16(resp, 0) == 5
    })
    check("SERVFAIL flags 0x8182, no answers", run {
        val q = DnsParser.parseQuery(buildQuery(6, "example.com", DnsParser.TYPE_A))!!
        val resp = DnsParser.buildServFailResponse(q)
        DnsParser.u16(resp, 2) == 0x8182 && DnsParser.u16(resp, 6) == 0
    })
    check("NODATA flags 0x8180, no answers", run {
        val q = DnsParser.parseQuery(buildQuery(7, "example.com", DnsParser.TYPE_TXT))!!
        val resp = DnsParser.buildNodataResponse(q)
        DnsParser.u16(resp, 2) == 0x8180 && DnsParser.u16(resp, 6) == 0
    })

    // --- type names ---
    check("typeName A/AAAA/unknown", DnsParser.typeName(1) == "A" &&
        DnsParser.typeName(28) == "AAAA" && DnsParser.typeName(99) == "TYPE99")

    println("RESULT DnsParserTest: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
