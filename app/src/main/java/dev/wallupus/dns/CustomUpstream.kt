package dev.wallupus.dns

import dev.wallupus.Upstream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Validation for the Settings screen's "Custom DoH" entry, plus the probe query used to test any
 * resolver before it's trusted with the whole phone's DNS. Pure JVM (OkHttp's URL parser only),
 * so it's unit-tested.
 */

/**
 * True for a literal IPv4/IPv6 address, which `InetAddress.getByName` turns into an address
 * *without* a DNS lookup. Hand-checked rather than via `InetAddress`: a hostname that slipped
 * through would trigger exactly the plain-text lookup bootstrap IPs exist to avoid.
 */
fun isIpLiteral(s: String): Boolean {
    if (s.isEmpty() || s.length > 45) return false
    if (':' in s) {
        // Zone ids (%wlan0) are meaningless for a public resolver; OkHttp rejects them anyway.
        if (s.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return false
        return "https://[$s]/".toHttpUrlOrNull() != null
    }
    val parts = s.split('.')
    return parts.size == 4 && parts.all { p ->
        // No leading zeros: "010" is octal to some parsers and decimal to others.
        // ASCII only: Char.isDigit and toInt() also take Arabic-Indic digits ("١.١.١.١"), which
        // InetAddress doesn't parse as a literal, so it would do a DNS lookup (and throw).
        p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } && (p == "0" || p[0] != '0') && p.toInt() <= 255
    }
}

/**
 * Parses what the user typed into an [Upstream], or throws [IllegalArgumentException] with a
 * message fit to show them. [bootstrap] is optional, comma/space separated.
 */
fun customUpstream(url: String, bootstrap: String): Upstream {
    val u = url.trim()
    require(u.startsWith("https://", ignoreCase = true)) { "The URL must start with https://" }
    val parsed = u.toHttpUrlOrNull()
    require(parsed != null && parsed.host.isNotEmpty()) { "That isn't a valid URL" }
    require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Credentials in the URL aren't supported" }
    val ips = bootstrap.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    ips.firstOrNull { !isIpLiteral(it) }?.let { throw IllegalArgumentException("\"$it\" isn't an IP address") }
    require(ips.size <= MAX_BOOTSTRAP) { "At most $MAX_BOOTSTRAP bootstrap IPs" }
    return Upstream(parsed.toString(), ips)
}

private const val MAX_BOOTSTRAP = 8

/** The name every resolver test asks for. Reserved by IANA (RFC 2606), so it always resolves. */
const val PROBE_NAME = "example.com"

/** A minimal recursive A query for [name], ID 0 as DoH prefers (RFC 8484 §4.1). */
fun probeQuery(name: String = PROBE_NAME): ByteArray {
    val out = ArrayList<Byte>(32)
    fun u16(v: Int) { out += (v ushr 8).toByte(); out += v.toByte() }
    u16(0) // ID
    u16(0x0100) // RD
    u16(1); u16(0); u16(0); u16(0) // QD=1, AN=NS=AR=0
    name.split('.').forEach { label ->
        out += label.length.toByte()
        label.forEach { out += it.code.toByte() }
    }
    out += 0
    u16(1) // A
    u16(1) // IN
    return out.toByteArray()
}
