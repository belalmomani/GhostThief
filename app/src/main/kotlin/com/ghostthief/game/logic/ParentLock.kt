package com.ghostthief.game.logic

import com.ghostthief.game.data.KeyValueStore
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * قفل الأهل: رمز رقمي (4–6 أرقام) يحمي الإعدادات والوضع العائم ووضع الطفل.
 * يُخزَّن ملح + بصمة SHA-256 فقط (لا الرمز نفسه). هو حاجز للأطفال وليس حماية قوية ضد مخترق.
 * إن نُسي الرمز: الحل الوحيد مسح بيانات التطبيق من إعدادات Android (يحذف التقدم المحلي أيضاً).
 */
class ParentLock(private val kv: KeyValueStore) {
    fun isSet(): Boolean = !kv.getString(HASH, null).isNullOrEmpty()

    fun set(pin: String): Boolean {
        if (!isValid(pin)) return false
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        kv.put(SALT, salt)
        kv.put(HASH, hash(salt, pin))
        return true
    }

    fun verify(pin: String): Boolean {
        val salt = kv.getString(SALT, null) ?: return false
        val stored = kv.getString(HASH, null) ?: return false
        return hash(salt, pin) == stored
    }

    fun clear() { kv.put(SALT, null as String?); kv.put(HASH, null as String?) }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val SALT = "lock_salt"
        private const val HASH = "lock_hash"
        fun isValid(pin: String) = pin.length in 4..6 && pin.all { it.isDigit() }
    }
}
