package dev.panoterm.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backup of hosts, software SSH keys, known_hosts and snippets.
 * File layout: "PANOBAK2" | salt(16) | iv(12) | AES-256-GCM(JSON), the magic as AAD.
 * Key = PBKDF2-HMAC-SHA256(password, salt, 600000 iterations — OWASP's current minimum).
 * Older "PANOBAK1" files (310000 iterations) still import.
 * Hardware (StrongBox) keys cannot leave the chip and are not included.
 */
object Backup {
    /** Format magic → PBKDF2 iterations. The first entry is what new backups are written with. */
    private val FORMATS = listOf(
        "PANOBAK2".toByteArray() to 600_000,
        "PANOBAK1".toByteArray() to 310_000,
    )
    private const val MAGIC_LEN = 8

    data class Summary(val hosts: Int, val keys: Int, val skippedHardwareKeys: Int)

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val raw = try { f.generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try { SecretKeySpec(raw, "AES") } finally { raw.fill(0) }
    }

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val (magic, iterations) = FORMATS.first()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(password, salt, iterations), GCMParameterSpec(128, iv))
        c.updateAAD(magic)
        return magic + salt + iv + c.doFinal(plain)
    }

    /** Throws IllegalArgumentException on wrong password / corrupt file. */
    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        val head = if (blob.size > MAGIC_LEN + 28) blob.copyOfRange(0, MAGIC_LEN) else null
        val (magic, iterations) = FORMATS.firstOrNull { head != null && it.first.contentEquals(head) }
            ?: throw IllegalArgumentException("PanoTerm 백업 파일이 아닙니다")
        val salt = blob.copyOfRange(MAGIC_LEN, MAGIC_LEN + 16)
        val iv = blob.copyOfRange(MAGIC_LEN + 16, MAGIC_LEN + 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, derive(password, salt, iterations), GCMParameterSpec(128, iv))
        c.updateAAD(magic)
        return try {
            c.doFinal(blob, MAGIC_LEN + 28, blob.size - MAGIC_LEN - 28)
        } catch (e: Exception) {
            throw IllegalArgumentException("암호가 틀렸거나 파일이 손상되었습니다")
        }
    }

    fun export(hosts: HostRepository, keys: KeyRepository, knownHostsFile: File, snippets: Snippets, password: CharArray): Pair<ByteArray, Summary> {
        val exp = keys.exportable()
        val json = JSONObject()
            .put("version", 1)
            .put("hosts", JSONArray(hosts.hosts.value.map { it.toJson() }))
            .put("keys", JSONArray(exp.map { (info, pem) ->
                JSONObject().put("info", info.toJson()).put("pem", Base64.encodeToString(pem, Base64.NO_WRAP))
            }))
            .put("knownHosts", if (knownHostsFile.exists()) knownHostsFile.readText() else "")
            .put("snippets", snippets.toJson())
        val plain = json.toString().toByteArray()
        exp.forEach { it.second.fill(0) }
        val skipped = keys.keys.value.count { it.hardware }
        return try {
            encrypt(plain, password) to Summary(hosts.hosts.value.size, exp.size, skipped)
        } finally {
            plain.fill(0)
        }
    }

    fun import(blob: ByteArray, password: CharArray, hosts: HostRepository, keys: KeyRepository, knownHosts: KnownHosts, snippets: Snippets): Summary {
        val plain = decrypt(blob, password)
        try {
            val o = JSONObject(plain.decodeToString())
            var nKeys = 0
            o.optJSONArray("keys")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val k = arr.getJSONObject(i)
                    val pem = Base64.decode(k.getString("pem"), Base64.NO_WRAP)
                    if (keys.restore(SshKeyInfo.fromJson(k.getJSONObject("info")), pem)) nKeys++
                    pem.fill(0)
                }
            }
            var nHosts = 0
            o.optJSONArray("hosts")?.let { arr ->
                for (i in 0 until arr.length()) { hosts.upsert(Host.fromJson(arr.getJSONObject(i))); nHosts++ }
            }
            knownHosts.merge(o.optString("knownHosts"))
            o.optJSONArray("snippets")?.let { snippets.importJson(it) }
            return Summary(nHosts, nKeys, 0)
        } finally {
            plain.fill(0)
        }
    }
}
