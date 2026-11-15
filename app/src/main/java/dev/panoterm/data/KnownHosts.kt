package dev.panoterm.data

import java.io.File

/** Minimal known_hosts store: one "host:port keyType base64" per line (trust on first use). */
class KnownHosts(private val file: File) {
    enum class Result { MATCH, UNKNOWN, CHANGED }

    @Synchronized
    fun check(hostPort: String, keyType: String, keyB64: String): Result {
        val entries = read().filter { it.first == hostPort }
        if (entries.isEmpty()) return Result.UNKNOWN
        return if (entries.any { it.second == keyType && it.third == keyB64 }) Result.MATCH else Result.CHANGED
    }

    /** Replaces any existing entry for [hostPort]. */
    @Synchronized
    fun save(hostPort: String, keyType: String, keyB64: String) {
        val kept = read().filterNot { it.first == hostPort } + Triple(hostPort, keyType, keyB64)
        writeAtomic(file, kept.joinToString("") { "${it.first} ${it.second} ${it.third}\n" }.toByteArray())
    }

    /** Adds entries from a known_hosts text (backup restore); existing hosts are kept. */
    @Synchronized
    fun merge(text: String) {
        val cur = read()
        val have = cur.map { it.first }.toSet()
        val add = text.lines().mapNotNull { line ->
            val p = line.trim().split(" ")
            if (p.size >= 3 && p[0] !in have) Triple(p[0], p[1], p[2]) else null
        }
        if (add.isNotEmpty()) writeAtomic(file, (cur + add).joinToString("") { "${it.first} ${it.second} ${it.third}\n" }.toByteArray())
    }

    val path: File get() = file

    private fun read(): List<Triple<String, String, String>> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            val p = line.trim().split(" ")
            if (p.size >= 3) Triple(p[0], p[1], p[2]) else null
        }
    }
}
