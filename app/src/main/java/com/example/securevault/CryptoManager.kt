package com.example.securevault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CryptoManager {
    private val alias = "secure_vault_aes_256_v22"
    private val key: SecretKey

    init {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
        }
        key = (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun encrypt(input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        BufferedOutputStream(output, BUFFER_SIZE).use { out ->
            out.write(MAGIC)
            out.write(cipher.iv)
            CipherOutputStream(out, cipher).use { cipherOut ->
                BufferedInputStream(input, BUFFER_SIZE).use { inputBuf ->
                    inputBuf.copyTo(cipherOut, BUFFER_SIZE)
                }
            }
        }
    }

    fun decrypt(input: InputStream, output: OutputStream) {
        BufferedInputStream(input, BUFFER_SIZE).use { inp ->
            val magic = ByteArray(MAGIC.size)
            inp.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Invalid vault file" }

            val iv = ByteArray(IV_SIZE)
            inp.readFully(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

            CipherInputStream(inp, cipher).use { cipherIn ->
                BufferedOutputStream(output, BUFFER_SIZE).use { out ->
                    cipherIn.copyTo(out, BUFFER_SIZE)
                }
            }
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val BUFFER_SIZE = 4 * 1024 * 1024
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private val MAGIC = byteArrayOf(0x53, 0x56, 0x33, 0x01)
    }
}

private fun InputStream.readFully(buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
        val count = read(buffer, offset, buffer.size - offset)
        require(count > 0) { "Unexpected end of vault file" }
        offset += count
    }
}
