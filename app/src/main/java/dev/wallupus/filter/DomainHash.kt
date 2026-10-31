package dev.wallupus.filter

/**
 * 64-bit FNV-1a over an ASCII domain.
 *
 * We store hashes, not strings: 300k domains as a sorted LongArray is ~2.4 MB, versus ~30 MB as a
 * HashSet<String> (object headers + char arrays + bucket nodes). The cost is a theoretical false
 * positive: with n=300k entries, a random lookup collides with probability n/2^64 ≈ 1.6e-14.
 * You'll be struck by lightning while reading this comment first.
 *
 * Not collision-resistant against an adversary — irrelevant here: the worst a crafted collision
 * can do is get some attacker-chosen name *blocked* on your phone.
 */
object DomainHash {
    private const val OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L

    fun of(s: CharSequence, start: Int = 0): Long {
        var h = OFFSET_BASIS
        for (i in start until s.length) {
            h = h xor s[i].code.toLong()
            h *= PRIME
        }
        return h
    }
}
