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
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(block.size)
            out.writeInt(allow.size)
            block.forEach(out::writeLong)
            allow.forEach(out::writeLong)
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
                    CompiledLists(LongArray(nb) { input.readLong() }, LongArray(na) { input.readLong() })
                }
            } catch (_: EOFException) {
                EMPTY
            } catch (_: IOException) {
                EMPTY
            }
        }

        /**
         * Parses list text into hashes. Streams line by line: a 20 MB list never sits in memory
         * as one String, only as the growing hash arrays.
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
                    }
                }
            }
            return CompiledLists(block.sortedUnique(), allow.sortedUnique())
        }
    }
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
