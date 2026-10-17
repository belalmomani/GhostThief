package com.ghostthief.game.data

import android.content.Context

/** تخزين مفتاح/قيمة بسيط. نسخة Android ونسخة ذاكرة للاختبارات. */
interface KeyValueStore {
    fun getString(key: String, default: String?): String?
    fun getLong(key: String, default: Long): Long
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun put(key: String, value: String?)
    fun put(key: String, value: Long)
    fun put(key: String, value: Int)
    fun put(key: String, value: Boolean)
    fun clear()
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, Any?>()
    override fun getString(key: String, default: String?) = (map[key] as? String) ?: default
    override fun getLong(key: String, default: Long) = (map[key] as? Long) ?: default
    override fun getInt(key: String, default: Int) = (map[key] as? Int) ?: default
    override fun getBoolean(key: String, default: Boolean) = (map[key] as? Boolean) ?: default
    override fun put(key: String, value: String?) { map[key] = value }
    override fun put(key: String, value: Long) { map[key] = value }
    override fun put(key: String, value: Int) { map[key] = value }
    override fun put(key: String, value: Boolean) { map[key] = value }
    override fun clear() = map.clear()
}

class PrefsKeyValueStore(context: Context, name: String = "ghost_thief_prefs") : KeyValueStore {
    private val sp = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String, default: String?) = sp.getString(key, default)
    override fun getLong(key: String, default: Long) = sp.getLong(key, default)
    override fun getInt(key: String, default: Int) = sp.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean) = sp.getBoolean(key, default)
    override fun put(key: String, value: String?) { sp.edit().putString(key, value).apply() }
    override fun put(key: String, value: Long) { sp.edit().putLong(key, value).apply() }
    override fun put(key: String, value: Int) { sp.edit().putInt(key, value).apply() }
    override fun put(key: String, value: Boolean) { sp.edit().putBoolean(key, value).apply() }
    override fun clear() { sp.edit().clear().apply() }
}
