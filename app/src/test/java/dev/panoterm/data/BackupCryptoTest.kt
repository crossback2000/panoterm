package dev.panoterm.data

import dev.panoterm.net.UpdateChecker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
    @Test fun roundTrip() {
        val data = "{\"hosts\":[\"한글 호스트\"]}".toByteArray()
        val blob = Backup.encrypt(data, "correct horse".toCharArray())
        assertTrue(blob.copyOfRange(0, 8).decodeToString() == "PANOBAK2")
        assertArrayEquals(data, Backup.decrypt(blob, "correct horse".toCharArray()))
    }

    /** Backups written before v1.3.12 (PANOBAK1, 310k iterations) must still import. */
    @Test fun legacyV1Decrypts() {
        val data = "legacy".toByteArray()
        val pw = "old-password".toCharArray()
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 100).toByte() }
        val magic = "PANOBAK1".toByteArray()
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pw, salt, 310_000, 256)).encoded
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        c.updateAAD(magic)
        val blob = magic + salt + iv + c.doFinal(data)
        assertArrayEquals(data, Backup.decrypt(blob, pw))
    }

    @Test fun wrongPasswordFails() {
        val blob = Backup.encrypt("secret".toByteArray(), "pw-one-123".toCharArray())
        try {
            Backup.decrypt(blob, "pw-two-456".toCharArray()); fail("decrypted with wrong password")
        } catch (e: IllegalArgumentException) {
            assertEquals("암호가 틀렸거나 파일이 손상되었습니다", e.message)
        }
    }

    @Test fun tamperedFileFails() {
        val blob = Backup.encrypt("secret".toByteArray(), "pw-one-123".toCharArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        try { Backup.decrypt(blob, "pw-one-123".toCharArray()); fail("tampered file accepted") } catch (_: IllegalArgumentException) {}
    }

    @Test fun notABackup() {
        try { Backup.decrypt("hello world, not a backup file at all".toByteArray(), "x".toCharArray()); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun versionCompare() {
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.3"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.9", "1.1.0"))
        // Release candidates: the final version replaces them, never the other way round.
        assertTrue(UpdateChecker.isNewer("1.3.10", "1.3.10-rc1"))
        assertFalse(UpdateChecker.isNewer("1.3.10-rc1", "1.3.10"))
        assertFalse(UpdateChecker.isNewer("1.3.9", "1.3.10-rc1"))
        assertTrue(UpdateChecker.isNewer("1.3.11", "1.3.10-rc1"))
    }
}
