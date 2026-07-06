package com.bingwa.mobile

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal object BlacklistStore {
    private const val PREFS_NAME = "blacklisted_contacts"
    private const val KEY_LIST = "list"
    const val ACTION_BLACKLIST_UPDATED = "com.bingwa.mobile.BLACKLIST_UPDATED"

    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<SavedContact> = load(prefs(context))

    fun load(prefs: SharedPreferences): List<SavedContact> = synchronized(lock) {
        parse(prefs.safeGetString(KEY_LIST, "[]") ?: "[]")
    }

    fun save(context: Context, contacts: List<SavedContact>): List<SavedContact> =
        synchronized(lock) {
            val normalized = normalize(contacts)
            persist(prefs(context), normalized)
            context.sendBroadcast(Intent(ACTION_BLACKLIST_UPDATED).setPackage(context.packageName))
            normalized
        }

    fun upsert(context: Context, phone: String, name: String): List<SavedContact> = synchronized(lock) {
        val normalizedPhone = SmsCommandHandler.normalizePhone(phone)
        val formattedName = formatClientName(name)
        if (!normalizedPhone.matches(Regex("^0\\d{9}$"))) return@synchronized load(context)

        val current = load(prefs(context)).toMutableList()
        val index = current.indexOfFirst { SmsCommandHandler.normalizePhone(it.phone) == normalizedPhone }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(
                name = choosePreferredClientName(existing.name, formattedName),
                phone = normalizedPhone
            )
        } else {
            current += SavedContact(formattedName, normalizedPhone)
        }
        save(context, current)
    }

    fun delete(context: Context, phone: String): List<SavedContact> = synchronized(lock) {
        val normalizedPhone = SmsCommandHandler.normalizePhone(phone)
        val current = load(prefs(context))
        val updated = current.filterNot { SmsCommandHandler.normalizePhone(it.phone) == normalizedPhone }
        save(context, updated)
    }

    fun findMatch(context: Context, phone: String = "", name: String = ""): SavedContact? {
        val normalizedPhone = SmsCommandHandler.normalizePhone(phone)
        val formattedName = formatClientName(name)
        return load(context).firstOrNull { entry ->
            val entryPhone = SmsCommandHandler.normalizePhone(entry.phone)
            val phoneMatched = normalizedPhone.matches(Regex("^0\\d{9}$")) && entryPhone == normalizedPhone
            val nameMatched = formattedName.isNotBlank() &&
                entry.name.isNotBlank() &&
                namesLikelyMatch(entry.name, formattedName)
            phoneMatched || nameMatched
        }
    }

    fun contains(context: Context, phone: String = "", name: String = ""): Boolean =
        findMatch(context, phone, name) != null

    private fun parse(rawJson: String): List<SavedContact> =
        runCatching {
            val arr = JSONArray(rawJson)
            (0 until arr.length()).mapNotNull { index ->
                val item = arr.optJSONObject(index) ?: return@mapNotNull null
                val phone = SmsCommandHandler.normalizePhone(item.optString("phone", ""))
                if (!phone.matches(Regex("^0\\d{9}$"))) return@mapNotNull null
                SavedContact(
                    name = formatClientName(item.optString("name", "")),
                    phone = phone
                )
            }
        }.getOrDefault(emptyList())

    private fun normalize(contacts: List<SavedContact>): List<SavedContact> =
        contacts
            .groupBy { SmsCommandHandler.normalizePhone(it.phone) }
            .mapNotNull { (phone, entries) ->
                if (!phone.matches(Regex("^0\\d{9}$"))) return@mapNotNull null
                val preferredName = entries
                    .map { formatClientName(it.name) }
                    .fold("") { best, candidate -> choosePreferredClientName(best, candidate) }
                SavedContact(preferredName, phone)
            }
            .sortedWith(compareBy<SavedContact> { it.name.ifBlank { "~" }.lowercase() }.thenBy { it.phone })

    private fun persist(prefs: SharedPreferences, contacts: List<SavedContact>) {
        val arr = JSONArray()
        contacts.forEach { contact ->
            arr.put(
                JSONObject().apply {
                    put("name", contact.name)
                    put("phone", contact.phone)
                }
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }
}
