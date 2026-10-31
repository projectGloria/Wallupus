package dev.wallupus.filter

import java.net.IDN

/** One usable line from a blocklist. */
sealed interface Rule {
    val domain: String

    data class Block(override val domain: String) : Rule
    data class Allow(override val domain: String) : Rule
}

/**
 * Reads the three list formats that cover basically every DNS blocklist:
 *   hosts      `0.0.0.0 ads.example.com`
 *   domains    `ads.example.com`
 *   adblock    `||ads.example.com^`  and exceptions `@@||ok.example.com^`
 *
 * Anything fancier (paths, regex, cosmetic `##` rules, `$app=` modifiers…) is meaningless at the
 * DNS layer and is skipped rather than half-interpreted. Silently ignoring a rule is safe;
 * misreading `||example.com/ads^` as "block example.com" would break a whole site.
 */
object ListParser {
    // Hosts files point blocked names at one of these. Anything else (a real IP) is a redirect,
    // not a block, and we don't do redirects.
    private val SINK_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "0", "0:0:0:0:0:0:0:0")
    private val HOSTS_NOISE = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0",
    )

    fun parseLine(raw: String): Rule? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        when (line[0]) {
            '#', '!', '[' -> return null // hosts comment, adblock comment, adblock header
        }
        if (line.startsWith("@@")) return adblock(line.substring(2))?.let { Rule.Allow(it) }
        if (line.startsWith("||")) return adblock(line)?.let { Rule.Block(it) }

        // Hosts or plain domain. A '#' only starts a comment after whitespace; one glued to a
        // name means adblock cosmetic syntax (`example.com##.banner`), and naively stripping it
        // would turn "hide one banner" into "block the entire site".
        val hash = line.indexOf('#')
        if (hash > 0 && !line[hash - 1].isWhitespace()) return null
        val tokens = (if (hash > 0) line.substring(0, hash) else line)
            .trim().split(' ', '\t').filter { it.isNotEmpty() }
        return when (tokens.size) {
            1 -> normalize(tokens[0])?.let { Rule.Block(it) }
            // Multi-name hosts lines ("0.0.0.0 a.com b.com") exist but are rare; we take the
            // first name only, which matches what StevenBlack and HaGeZi actually ship.
            else -> if (tokens[0] in SINK_IPS) normalize(tokens[1])?.let { Rule.Block(it) } else null
        }
    }

    /** `||domain^` with an optional `$important` (AdGuard DNS uses it). Nothing else qualifies. */
    private fun adblock(line: String): String? {
        if (!line.startsWith("||")) return null
        var body = line.substring(2)
        body = body.removeSuffix("\$important")
        if (!body.endsWith("^")) return null
        body = body.dropLast(1)
        // Any remaining pattern syntax means it's not a plain domain rule.
        if (body.any { it == '*' || it == '/' || it == '|' || it == '^' || it == '$' }) return null
        return normalize(body)
    }

    fun normalize(input: String): String? {
        var d = input.trim().lowercase().removeSuffix(".")
        if (d.isEmpty() || d in HOSTS_NOISE) return null
        if (d.any { it.code > 0x7F }) {
            // Lists sometimes carry raw Unicode names; DNS queries arrive in punycode (xn--…).
            d = try {
                IDN.toASCII(d, IDN.ALLOW_UNASSIGNED).lowercase()
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
        if (d.length > 253 || !isValidName(d)) return null
        // Reject anything that's really an IPv4 literal ("10.0.0.1" passes the label check).
        if (d.substringAfterLast('.').all { it in '0'..'9' }) return null
        return d
    }

    /**
     * Two or more dot-separated labels of 1–63 chars from [a-z0-9_-], none starting or ending
     * with '-'. A plain loop rather than a Regex: Android's regex is ICU-backed, and a native
     * matcher per line over ~400k lines left ~90 MB of native heap stranded after each update.
     */
    internal fun isValidName(d: String): Boolean {
        var labels = 0
        var start = 0
        while (start <= d.length) {
            var end = d.indexOf('.', start)
            if (end < 0) end = d.length
            val len = end - start
            if (len !in 1..63 || d[start] == '-' || d[end - 1] == '-') return false
            for (i in start until end) {
                val c = d[i]
                if (c !in 'a'..'z' && c !in '0'..'9' && c != '_' && c != '-') return false
            }
            labels++
            start = end + 1
        }
        return labels >= 2
    }
}
