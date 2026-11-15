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
        assertTrue(blob.copyOfRange(0, 8).decodeToString() == "PANOBAK1")
        assertArrayEquals(data, Backup.decrypt(blob, "correct horse".toCharArray()))
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
    }
}
