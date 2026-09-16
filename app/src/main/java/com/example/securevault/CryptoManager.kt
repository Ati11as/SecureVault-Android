package com.example.securevault

import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class CryptoManager {
    private val alias = "secure_vault_aes_256_v21"

    init {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
                generateKey()
            }
        }
    }

    private fun key(): SecretKey =
        (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey

    fun encrypt(input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        output.write(byteArrayOf(0x53,0x56,0x32,0x01))
        output.write(cipher.iv.size)
        output.write(cipher.iv)
        CipherOutputStream(output, cipher).use { out ->
            input.use { it.copyTo(out, 1024 * 1024) }
        }
    }

    fun decrypt(input: InputStream, output: OutputStream) {
        input.use { inp ->
            require(inp.read()==0x53 && inp.read()==0x56 && inp.read()==0x32) { "Invalid vault file" }
            require(inp.read()==0x01) { "Unsupported vault version" }
            val ivSize=inp.read()
            require(ivSize in 12..16)
            val iv=ByteArray(ivSize)
            inp.readFully(iv)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))
            CipherInputStream(inp,cipher).use { cin ->
                output.use { out -> cin.copyTo(out,1024*1024) }
            }
        }
    }
}
private fun InputStream.readFully(b:ByteArray) {
    var p=0
    while(p<b.size) { val n=read(b,p,b.size-p); require(n>0); p+=n }
}
