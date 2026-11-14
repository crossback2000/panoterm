package dev.panoterm.data

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val address: String = "",
    val port: Int = 22,
    val user: String = "",
    val keyId: String? = null,
    val useTailnet: Boolean = true,
    val tmux: Boolean = true,
    val tmuxSession: String = "main",
    val fontSize: Int = 14,
) {
    val title: String get() = name.ifBlank { address }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("address", address)
        .put("port", port)
        .put("user", user)
        .put("keyId", keyId ?: "")
        .put("useTailnet", useTailnet)
        .put("tmux", tmux)
        .put("tmuxSession", tmuxSession)
        .put("fontSize", fontSize)

    companion object {
        fun fromJson(o: JSONObject) = Host(
            id = o.getString("id"),
            name = o.optString("name"),
            address = o.optString("address"),
            port = o.optInt("port", 22),
            user = o.optString("user"),
            keyId = o.optString("keyId").ifBlank { null },
            useTailnet = o.optBoolean("useTailnet", true),
            tmux = o.optBoolean("tmux", true),
            tmuxSession = o.optString("tmuxSession", "main"),
            fontSize = o.optInt("fontSize", 14),
        )
    }
}

data class SshKeyInfo(
    val id: String,
    val name: String,
    val type: String,
    val publicKey: String,
    val fingerprint: String,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type)
        .put("publicKey", publicKey).put("fingerprint", fingerprint).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = SshKeyInfo(
            id = o.getString("id"),
            name = o.optString("name"),
            type = o.optString("type"),
            publicKey = o.optString("publicKey"),
            fingerprint = o.optString("fingerprint"),
            createdAt = o.optLong("createdAt"),
        )
    }
}

internal fun readJsonArray(file: File): JSONArray? = try {
    if (!file.exists()) null else JSONArray(AtomicFile(file).readFully().decodeToString())
} catch (e: Exception) {
    null
}

internal fun writeAtomic(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val af = AtomicFile(file)
    val out = af.startWrite()
    try {
        out.write(bytes)
        af.finishWrite(out)
    } catch (e: Exception) {
        af.failWrite(out)
        throw e
    }
}
