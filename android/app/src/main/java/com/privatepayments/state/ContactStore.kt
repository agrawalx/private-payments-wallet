package com.privatepayments.state

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved contact: a name against a public G… and/or a shielded "stella:" address. */
data class Contact(
    val id: String,
    val name: String,
    val publicAddress: String? = null,
    val shieldedAddress: String? = null,
)

/**
 * Local address book. Deliberately simple (a JSON blob in `SharedPreferences`,
 * not a SQLite table like [NoteStore]/[ChainStore]) — contacts are a handful of
 * small records read/written as a whole list, no querying needed.
 */
class ContactStore(context: Context) {
    private val prefs = context.getSharedPreferences("stella_contacts", Context.MODE_PRIVATE)

    fun list(): List<Contact> {
        val arr = runCatching { JSONArray(prefs.getString("contacts", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Contact(
                id = o.optString("id"),
                name = o.optString("name"),
                publicAddress = o.optString("publicAddress").takeIf { it.isNotEmpty() },
                shieldedAddress = o.optString("shieldedAddress").takeIf { it.isNotEmpty() },
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun add(name: String, publicAddress: String?, shieldedAddress: String?): Contact {
        val contact = Contact(UUID.randomUUID().toString(), name, publicAddress, shieldedAddress)
        save(list() + contact)
        return contact
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    private fun save(contacts: List<Contact>) {
        val arr = JSONArray()
        contacts.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("publicAddress", c.publicAddress ?: "")
                    .put("shieldedAddress", c.shieldedAddress ?: ""),
            )
        }
        prefs.edit().putString("contacts", arr.toString()).apply()
    }
}
