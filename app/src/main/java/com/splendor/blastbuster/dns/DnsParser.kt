package com.splendor.blastbuster.dns

/**
 * DNS wire format (DESIGN.md §4.2, §5.4). Pure Kotlin — no Android imports
 * — so it is JVM-testable.
 *
 * Scope, honestly stated: this parses and builds plain DNS over UDP only
 * (the v1.2 pipe forwards to a plain-UDP upstream). It does not do DoH/DoT
 * (design decision D-09, v1.3 at the earliest), and it does not validate
 * DNSSEC. A malformed packet is rejected (parse returns null) rather than
 * guessed at — a bad packet is dropped, never forwarded on a guess.
 *
 * Name handling: compression pointers (0xC0..) are followed when parsing,
 * with a jump limit so a malicious pointer loop cannot spin the parser.
 * Responses point the answer NAME back at offset 12 (0xC00C), the
 * standard compression for the question just asked.
 */

object DnsParser {

    const val TYPE_A = 1
    const val TYPE_NS = 2
    const val TYPE_CNAME = 5
    const val TYPE_MX = 15
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41
    const val CLASS_IN = 1

    /** TTL (seconds) on sinkhole / NODATA answers. Short on purpose. */
    const val SINKHOLE_TTL = 60

    private const val HEADER_LEN = 12
    private const val MAX_NAME_LEN = 253
    private const val MAX_LABEL_LEN = 63
    private const val MAX_POINTER_JUMPS = 10

    /** One parsed query. [rawQuestion] is the exact question section bytes, echoed verbatim in responses. */
    data class DnsQuery(
        val id: Int,
        val name: String,
        val type: Int,
        val qclass: Int,
        val rawQuestion: ByteArray
    )

    /** One answer record for [buildResponse]. */
    data class DnsAnswer(
        val type: Int,
        val ttlSeconds: Int,
        val rdata: ByteArray
    )

    // ---------- parsing ----------

    /**
     * Parse a DNS query packet. Returns null when the packet is not a
     * well-formed single-question query: too short, not a query (QR set),
     * QDCOUNT != 1, truncated name, bad label, pointer loop. Callers drop
     * null results — they are never forwarded.
     */
    fun parseQuery(packet: ByteArray): DnsQuery? {
        if (packet.size < HEADER_LEN) return null
        val id = u16(packet, 0)
        val flags = u16(packet, 2)
        val isResponse = (flags and 0x8000) != 0
        if (isResponse) return null
        val qdCount = u16(packet, 4)
        if (qdCount != 1) return null
        val (name, afterName) = decodeName(packet, HEADER_LEN) ?: return null
        if (name.isEmpty() || name.length > MAX_NAME_LEN) return null
        if (afterName + 4 > packet.size) return null
        val type = u16(packet, afterName)
        val qclass = u16(packet, afterName + 2)
        val rawQuestion = packet.copyOfRange(HEADER_LEN, afterName + 4)
        return DnsQuery(id, name, type, qclass, rawQuestion)
    }

    /**
     * Decode a domain name at [offset]. Returns the name and the offset
     * just past the name (past the terminating zero, or past the 2-byte
     * compression pointer). Returns null on any malformed input:
     * out-of-bounds reads, label overruns, pointer loops, over-long names.
     */
    fun decodeName(packet: ByteArray, offset: Int): Pair<String, Int>? {
        val labels = StringBuilder()
        var pos = offset
        var nextAfterName = -1
        var jumps = 0
        while (true) {
            if (pos < 0 || pos >= packet.size) return null
            val len = packet[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (nextAfterName == -1) nextAfterName = pos + 1
                    break
                }
                (len and 0xC0) == 0xC0 -> {
                    // Compression pointer: 14-bit offset.
                    if (pos + 1 >= packet.size) return null
                    val pointer = ((len and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                    if (nextAfterName == -1) nextAfterName = pos + 2
                    if (++jumps > MAX_POINTER_JUMPS) return null
                    if (pointer >= packet.size) return null
                    pos = pointer
                }
                (len and 0xC0) != 0 -> return null // reserved label forms
                len > MAX_LABEL_LEN -> return null
                else -> {
                    if (pos + 1 + len > packet.size) return null
                    val label = String(packet, pos + 1, len, Charsets.US_ASCII)
                    if (labels.isNotEmpty()) labels.append('.')
                    labels.append(label)
                    if (labels.length > MAX_NAME_LEN) return null
                    pos += 1 + len
                }
            }
        }
        if (nextAfterName == -1) return null
        return Pair(labels.toString(), nextAfterName)
    }

    /** Encode a domain name as length-prefixed labels + terminating zero. Throws on over-long labels. */
    fun encodeName(name: String): ByteArray {
        if (name.isEmpty() || name == ".") return byteArrayOf(0)
        val clean = name.trim().trimEnd('.')
        val out = mutableListOf<Byte>()
        for (label in clean.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..MAX_LABEL_LEN) { "label too long: $label" }
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        out.add(0)
        return out.toByteArray()
    }

    // ---------- building ----------

    /**
     * Build a response for [query] with the given answers.
     * Flags: QR=1, RD=1 (copied intent), RA=1, RCODE=0 (NOERROR).
     * The question section is echoed verbatim; each answer NAME is the
     * compression pointer 0xC00C (offset 12, the question name).
     */
    fun buildResponse(query: DnsQuery, answers: List<DnsAnswer>): ByteArray {
        val out = ByteArrayBuilder()
        out.u16(query.id)
        out.u16(0x8180)
        out.u16(1) // QDCOUNT
        out.u16(answers.size) // ANCOUNT
        out.u16(0) // NSCOUNT
        out.u16(0) // ARCOUNT
        out.bytes(query.rawQuestion)
        for (answer in answers) {
            out.u16(0xC00C) // NAME: pointer to offset 12
            out.u16(answer.type)
            out.u16(CLASS_IN)
            out.u32(answer.ttlSeconds)
            out.u16(answer.rdata.size)
            out.bytes(answer.rdata)
        }
        return out.toByteArray()
    }

    /**
     * Sinkhole answer: A -> 0.0.0.0, AAAA -> :: (all zeros).
     * Any other type gets a NODATA response (NOERROR, zero answers) —
     * the name is blocked, but answering a made-up MX/TXT record would
     * be inventing data, so we answer "no records of that type".
     */
    fun buildSinkholeResponse(query: DnsQuery): ByteArray = when (query.type) {
        TYPE_A -> buildResponse(
            query,
            listOf(DnsAnswer(TYPE_A, SINKHOLE_TTL, byteArrayOf(0, 0, 0, 0)))
        )
        TYPE_AAAA -> buildResponse(
            query,
            listOf(DnsAnswer(TYPE_AAAA, SINKHOLE_TTL, ByteArray(16)))
        )
        else -> buildNodataResponse(query)
    }

    /** NXDOMAIN (RCODE=3): the name does not exist — used for "lie" entries and blocked non-A/AAAA queries. */
    fun buildNxDomainResponse(query: DnsQuery): ByteArray =
        buildResponseWithFlags(query, 0x8183, emptyList())

    /** SERVFAIL (RCODE=2): the upstream could not be reached. Lets the client fail fast instead of hanging. */
    fun buildServFailResponse(query: DnsQuery): ByteArray =
        buildResponseWithFlags(query, 0x8182, emptyList())

    /** NOERROR with zero answers: the name exists, but has no records of the asked type. */
    fun buildNodataResponse(query: DnsQuery): ByteArray =
        buildResponseWithFlags(query, 0x8180, emptyList())

    private fun buildResponseWithFlags(query: DnsQuery, flags: Int, answers: List<DnsAnswer>): ByteArray {
        val out = ByteArrayBuilder()
        out.u16(query.id)
        out.u16(flags)
        out.u16(1)
        out.u16(answers.size)
        out.u16(0)
        out.u16(0)
        out.bytes(query.rawQuestion)
        for (answer in answers) {
            out.u16(0xC00C)
            out.u16(answer.type)
            out.u16(CLASS_IN)
            out.u32(answer.ttlSeconds)
            out.u16(answer.rdata.size)
            out.bytes(answer.rdata)
        }
        return out.toByteArray()
    }

    /** Human name for a query type, for stats/debug only. */
    fun typeName(type: Int): String = when (type) {
        TYPE_A -> "A"
        TYPE_AAAA -> "AAAA"
        TYPE_CNAME -> "CNAME"
        TYPE_MX -> "MX"
        TYPE_NS -> "NS"
        TYPE_TXT -> "TXT"
        TYPE_OPT -> "OPT"
        else -> "TYPE$type"
    }

    // ---------- byte helpers ----------

    fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    private class ByteArrayBuilder {
        private val bytes = mutableListOf<Byte>()
        fun u16(v: Int) {
            bytes.add(((v ushr 8) and 0xFF).toByte())
            bytes.add((v and 0xFF).toByte())
        }
        fun u32(v: Int) {
            bytes.add(((v ushr 24) and 0xFF).toByte())
            bytes.add(((v ushr 16) and 0xFF).toByte())
            bytes.add(((v ushr 8) and 0xFF).toByte())
            bytes.add((v and 0xFF).toByte())
        }
        fun bytes(b: ByteArray) = b.forEach { bytes.add(it) }
        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}
