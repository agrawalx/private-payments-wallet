package com.privatepayments.state

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 7 backup crypto. AES-256-GCM over the NoteStore export, keyed by the
 * 32-byte key the Rust FFI derives from the recovery phrase
 * (`backupKeyFromMnemonic`). The key never leaves the device and is never
 * stored — only someone with the seed phrase can decrypt a backup.
 *
 * Blob layout: `[1-byte version=1][12-byte IV][ciphertext || 16-byte GCM tag]`.
 */
object WalletBackup {
    private const val VERSION: Byte = 1
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "backup key must be 32 bytes" }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return ByteArray(1 + IV_LEN + ct.size).also {
            it[0] = VERSION
            System.arraycopy(iv, 0, it, 1, IV_LEN)
            System.arraycopy(ct, 0, it, 1 + IV_LEN, ct.size)
        }
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(key.size == 32) { "backup key must be 32 bytes" }
        require(blob.size > 1 + IV_LEN) { "backup blob too short" }
        require(blob[0] == VERSION) { "unsupported backup version ${blob[0]}" }
        val iv = blob.copyOfRange(1, 1 + IV_LEN)
        val ct = blob.copyOfRange(1 + IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct) // throws AEADBadTagException on wrong key / tampering
    }

    /**
     * Self-test the crypto round-trip with the given key, independent of any
     * cloud transport. Returns true if encrypt→decrypt recovers the input and a
     * wrong key is rejected. Lets us verify Phase 7 end-to-end without OAuth.
     */
    fun selfTest(key: ByteArray): Boolean = try {
        val sample = "umbra-backup-selftest".toByteArray()
        val blob = encrypt(key, sample)
        val ok = decrypt(key, blob).contentEquals(sample)
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        val rejected = runCatching { decrypt(wrongKey, blob) }.isFailure
        ok && rejected
    } catch (e: Exception) {
        false
    }
}
