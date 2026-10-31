package dev.wallupus.filter

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * [name] and its parents, stopping at the registrable domain: `metrics.bbc.co.uk` offers
 * `bbc.co.uk` but never `co.uk`, which would block a whole country's commercial web. OkHttp
 * already bundles the Public Suffix List, so this costs no new dependency. Empty for names with
 * no registrable domain (single labels, bare public suffixes) — a rule there would do nothing.
 */
internal fun ruleTargets(name: String): List<String> {
    val top = "https://$name/".toHttpUrlOrNull()?.topPrivateDomain() ?: return emptyList()
    // OkHttp canonicalizes the host; if that ever changed it, the walk below would be wrong.
    if (!name.endsWith(top) || ListParser.normalize(top) == null) return emptyList()
    val out = ArrayList<String>(4)
    var d = name
    while (true) {
        out += d
        if (d.length <= top.length) break
        d = d.substringAfter('.')
    }
    return out
}
