package com.example.securevault

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object AuthStore {
    private const val PREF="auth"
    private const val HASH="hash"
    private const val SALT="salt"

    fun hasPin(c:Context)=c.getSharedPreferences(PREF,0).contains(HASH)

    fun setPin(c:Context,pin:String) {
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
        c.getSharedPreferences(PREF,0).edit()
            .putString(SALT,Base64.encodeToString(salt,Base64.NO_WRAP))
            .putString(HASH,Base64.encodeToString(hash(pin,salt),Base64.NO_WRAP))
            .apply()
    }

    fun verify(c:Context,pin:String):Boolean {
        val p=c.getSharedPreferences(PREF,0)
        val s=p.getString(SALT,null) ?: return false
        val h=p.getString(HASH,null) ?: return false
        return MessageDigest.isEqual(hash(pin,Base64.decode(s,Base64.NO_WRAP)),
            Base64.decode(h,Base64.NO_WRAP))
    }

    private fun hash(pin:String,salt:ByteArray):ByteArray =
        MessageDigest.getInstance("SHA-256").digest(salt+pin.toByteArray(Charsets.UTF_8))
}
