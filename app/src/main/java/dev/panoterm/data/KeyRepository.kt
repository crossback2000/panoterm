package dev.panoterm.data

import dev.panoterm.str
import dev.panoterm.R
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import core.Core
import core.HardwareSigner
import core.KeyPair
import dev.panoterm.term.KeyAuthPrompt
import dev.panoterm.term.Prompts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * SSH keys. Metadata in keys.json.
 *  - Software keys (Ed25519 / imported): private key encrypted with [SecretBox] in <id>.key.
 *  - Hardware keys: EC P-256 generated inside Android Keystore (StrongBox when available),
 *    alias "panoterm_hw_<id>". The private key never leaves the chip and cannot be backed up.
 */
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

    /** Decrypted private key (software keys). Caller should zero it after use. */
    fun privateKey(id: String): ByteArray = SecretBox.decrypt(keyFile(id).readBytes())

    /** <id>.key. Ids also come from backup files, so anything but a UUID is refused (no "../"). */
    private fun keyFile(id: String): File {
        require(ID.matches(id)) { "bad key id" }
        return File(dir, "$id.key")
    }

    // ---- hardware keys ----

    private fun alias(id: String) = "panoterm_hw_$id"

    /**
     * Generates a non-exportable EC P-256 key in StrongBox (or the TEE if StrongBox is unavailable).
     * [userAuth]: the chip refuses to sign unless the user has just passed a fingerprint /
     * screen-lock check for that very signature, so a malicious app or a stolen unlocked phone
     * cannot connect with it silently.
     */
    @Synchronized
    fun generateHardware(name: String, userAuth: Boolean = false): SshKeyInfo {
        val id = UUID.randomUUID().toString()
        fun gen(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setIsStrongBoxBacked(strongBox)
                    .apply {
                        if (userAuth) {
                            setUserAuthenticationRequired(true)
                            // 0 = every signature needs its own check (BiometricPrompt CryptoObject).
                            setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                            // Adding a fingerprint must not destroy the key (the server still trusts it).
                            setInvalidatedByBiometricEnrollment(false)
                        }
                    }
                    .build()
            )
        }.generateKeyPair()
        var strong = true
        val kp = try { gen(true) } catch (e: StrongBoxUnavailableException) { strong = false; gen(false) }
        val spki = kp.public.encoded
        val pub = Core.hardwarePublicKey(spki, name)
        val info = SshKeyInfo(
            id = id,
            name = name.ifBlank { "hardware" },
            type = pub.getKeyType() + (if (strong) " · StrongBox" else " · TEE") + (if (userAuth) str(R.string.key_type_bio) else ""),
            publicKey = pub.getPublicKey(),
            fingerprint = pub.getFingerprint(),
            createdAt = System.currentTimeMillis(),
            hardware = true,
            spki = Base64.encodeToString(spki, Base64.NO_WRAP),
            userAuth = userAuth,
        )
        save(_keys.value + info)
        return info
    }

    fun spki(info: SshKeyInfo): ByteArray = Base64.decode(info.spki, Base64.NO_WRAP)

    /**
     * Signer handed to the Go core; signs inside the Keystore. Runs on the SSH handshake
     * thread, so for [SshKeyInfo.userAuth] keys it can block while the UI asks for a fingerprint.
     */
    fun hardwareSigner(info: SshKeyInfo): HardwareSigner = object : HardwareSigner {
        override fun signSHA256(data: ByteArray): ByteArray {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(alias(info.id), null) as? PrivateKey
                ?: throw IllegalStateException(str(R.string.err_hw_key_missing))
            val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
            if (info.userAuth) {
                val p = KeyAuthPrompt(info.name, sig)
                Prompts.keyAuth.value = p
                val ok = try {
                    p.result.get(120, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    false
                } finally {
                    Prompts.keyAuth.compareAndSet(p, null)
                }
                if (!ok) throw IllegalStateException(str(R.string.err_key_auth_cancelled))
            }
            sig.update(data)
            return sig.sign()
        }
    }

    // ---- backup support ----

    /** Software keys with their decrypted PEM, for the encrypted backup file. */
    fun exportable(): List<Pair<SshKeyInfo, ByteArray>> =
        _keys.value.filter { !it.hardware }.mapNotNull { k -> runCatching { k to privateKey(k.id) }.getOrNull() }

    /** Restores a software key with its original id (so hosts keep pointing to it). Skips existing ids. */
    @Synchronized
    fun restore(info: SshKeyInfo, pem: ByteArray): Boolean {
        if (get(info.id) != null || info.hardware || !ID.matches(info.id)) return false
        writeAtomic(keyFile(info.id), SecretBox.encrypt(pem))
        save(_keys.value + info)
        return true
    }

    @Synchronized
    fun delete(id: String) {
        val k = get(id)
        if (ID.matches(id)) File(dir, "$id.key").delete()
        if (k?.hardware == true) {
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(id)) }
        }
        save(_keys.value.filterNot { it.id == id })
    }

    @Synchronized
    private fun store(name: String, kp: KeyPair): SshKeyInfo {
        val id = UUID.randomUUID().toString()
        val pem = kp.getPrivatePEM()
        try {
            writeAtomic(keyFile(id), SecretBox.encrypt(pem))
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

    private companion object {
        val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
