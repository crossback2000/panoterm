package dev.panoterm.data

import core.Core
import core.KeyPair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** SSH keys. Metadata in keys.json, private keys encrypted with [SecretBox] in <id>.key. */
class KeyRepository(private val dir: File) {
    private val meta = File(dir, "keys.json")
    private val _keys = MutableStateFlow(load())
    val keys: StateFlow<List<SshKeyInfo>> = _keys

    init {
        dir.mkdirs()
    }

    private fun load(): List<SshKeyInfo> {
        val arr = readJsonArray(meta) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { SshKeyInfo.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun get(id: String): SshKeyInfo? = _keys.value.firstOrNull { it.id == id }

    fun generateEd25519(name: String): SshKeyInfo = store(name, Core.generateEd25519(name))

    /** Throws with message "PASSPHRASE_REQUIRED" if the key is encrypted and [passphrase] is empty. */
    fun import(name: String, data: ByteArray, passphrase: String): SshKeyInfo =
        store(name, Core.importKey(data, passphrase, name))

    /** Decrypted private key. Caller should zero it after use. */
    fun privateKey(id: String): ByteArray = SecretBox.decrypt(File(dir, "$id.key").readBytes())

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.key").delete()
        save(_keys.value.filterNot { it.id == id })
    }

    @Synchronized
    private fun store(name: String, kp: KeyPair): SshKeyInfo {
        val id = UUID.randomUUID().toString()
        val pem = kp.getPrivatePEM()
        try {
            writeAtomic(File(dir, "$id.key"), SecretBox.encrypt(pem))
        } finally {
            pem.fill(0)
        }
        val info = SshKeyInfo(
            id = id,
            name = name.ifBlank { "key" },
            type = kp.getKeyType(),
            publicKey = kp.getPublicKey(),
            fingerprint = kp.getFingerprint(),
            createdAt = System.currentTimeMillis(),
        )
        save(_keys.value + info)
        return info
    }

    private fun save(list: List<SshKeyInfo>) {
        writeAtomic(meta, JSONArray(list.map { it.toJson() }).toString().toByteArray())
        _keys.value = list
    }
}
