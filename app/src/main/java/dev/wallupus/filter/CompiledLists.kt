package dev.wallupus.filter

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The compiled, on-disk form of all enabled lists: two sorted hash arrays.
 * Format: "WLPS" | version:int | blockCount:int | allowCount:int | block longs | allow longs.
 */
class CompiledLists(val block: LongArray, val allow: LongArray) {

    /** Atomic write: readers (the running VPN) only ever see the old file or the new one. */
    fun writeTo(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(block.size)
                out.writeInt(allow.size)
                block.forEach(out::writeLong)
                allow.forEach(out::writeLong)
            }
        } catch (e: IOException) {
            tmp.delete() // disk full: don't leave a half-written file eating the space
            throw e
        }
        // Files.move, not File.renameTo: renameTo won't replace an existing file on a Windows JVM,
        // which broke the round-trip test there. On Android both are rename(2).
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("rename ${tmp.name} → ${file.name} failed", e)
        }
    }

    companion object {
        private const val MAGIC = 0x574C5053 // "WLPS"
        private const val VERSION = 1
        private const val MAX_ENTRIES = 5_000_000 // sanity cap: a corrupt header can't OOM us
        private const val HEADER_BYTES = 16L // magic, version, two counts

        val EMPTY = CompiledLists(LongArray(0), LongArray(0))

        /** Missing or corrupt file → EMPTY. The VPN keeps working (unfiltered) rather than crashing. */
        fun readOrEmpty(file: File): CompiledLists {
            if (!file.exists()) return EMPTY
            return try {
                DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
                    if (input.readInt() != MAGIC || input.readInt() != VERSION) return EMPTY
                    val nb = input.readInt()
                    val na = input.readInt()
                    if (nb !in 0..MAX_ENTRIES || na !in 0..MAX_ENTRIES) return EMPTY
                    // Exact size before allocating: a corrupt header must not cost 40 MB of
                    // arrays only to hit EOF. Long math, so the product can't overflow.
                    if (file.length() != HEADER_BYTES + 8L * (nb + na)) return EMPTY
                    val lists = CompiledLists(LongArray(nb) { input.readLong() }, LongArray(na) { input.readLong() })
                    // Binary search silently misses on an unsorted array: treat it as corrupt.
                    if (!lists.block.isStrictlySorted() || !lists.allow.isStrictlySorted()) return EMPTY
                    lists
                }
            } catch (_: EOFException) {
                EMPTY
            } catch (_: IOException) {
                EMPTY
            }
        }

        /**
         * Parses list text into hashes. Streams line by line: a 20 MB list never sits in memory
         * as one String, only as the growing hash arrays. Throws IOException past MAX_ENTRIES rules.
         */
        fun build(readers: Sequence<BufferedReader>): CompiledLists {
            val block = LongList()
            val allow = LongList()
            for (reader in readers) {
                reader.useLines { lines ->
                    for (line in lines) {
                        when (val r = ListParser.parseLine(line)) {
                            is Rule.Block -> block.add(DomainHash.of(r.domain))
                            is Rule.Allow -> allow.add(DomainHash.of(r.domain))
                            null -> Unit
                        }
                        // Counted before dedupe, so repeated rules can't grow the arrays without
                        // bound either. Also keeps the output readable: readOrEmpty rejects more
                        // than MAX_ENTRIES, which would turn a big install into EMPTY on restart.
                        if (block.size + allow.size > MAX_ENTRIES) throw IOException("More than $MAX_ENTRIES list rules")
                    }
                }
            }
            return CompiledLists(block.sortedUnique(), allow.sortedUnique())
        }
    }
}

private fun LongArray.isStrictlySorted(): Boolean {
    for (i in 1 until size) if (this[i - 1] >= this[i]) return false
    return true
}

/** Growable primitive long array. ArrayList<Long> would box every element (~16 B overhead each). */
internal class LongList(initial: Int = 1 shl 16) {
    private var data = LongArray(initial)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun sortedUnique(): LongArray {
        if (size == 0) return LongArray(0)
        val a = data.copyOf(size)
        a.sort()
        var w = 1
        for (r in 1 until a.size) if (a[r] != a[w - 1]) a[w++] = a[r]
        return a.copyOf(w)
    }
}
