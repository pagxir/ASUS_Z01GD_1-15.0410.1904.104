package com.voicedialer.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import com.voicedialer.app.data.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactRepository(private val context: Context) {

    suspend fun getAllContacts(): List<Contact> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<Contact>()
        val contentResolver: ContentResolver = context.contentResolver

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val id = it.getString(idIndex) ?: continue
                val name = it.getString(nameIndex) ?: continue
                val phone = it.getString(phoneIndex) ?: continue

                val normalizedPhone = phone.replace(Regex("[^0-9+]"), "")

                if (contacts.none { c -> c.name == name && c.phone == normalizedPhone }) {
                    contacts.add(Contact(id, name, normalizedPhone))
                }
            }
        }

        contacts
    }

    fun findContactByName(name: String, contacts: List<Contact>): Contact? {
        val normalizedInput = name.lowercase().replace(Regex("\\s+"), "")
        
        return contacts.find { contact ->
            val normalizedName = contact.name.lowercase().replace(Regex("\\s+"), "")
            normalizedName.contains(normalizedInput) || 
            normalizedInput.contains(normalizedName) ||
            fuzzyMatch(normalizedInput, normalizedName)
        }
    }

    private fun fuzzyMatch(input: String, name: String): Boolean {
        if (input.isEmpty() || name.isEmpty()) return false
        
        var inputIdx = 0
        for (char in name) {
            if (inputIdx < input.length && (char == input[inputIdx] || isSimilarChar(char, input[inputIdx]))) {
                inputIdx++
            }
        }
        return inputIdx == input.length
    }

    private fun isSimilarChar(c1: Char, c2: Char): Boolean {
        val pinyinMap = mapOf(
            'b' to listOf('b', 'p'), 'p' to listOf('p', 'b'),
            'm' to listOf('m', 'n'), 'n' to listOf('n', 'm'),
            'l' to listOf('l', 'r'), 'r' to listOf('r', 'l'),
            'g' to listOf('g', 'k'), 'k' to listOf('k', 'g'),
            'd' to listOf('d', 't'), 't' to listOf('t', 'd'),
            'z' to listOf('z', 'j'), 'j' to listOf('j', 'z'),
            'c' to listOf('c', 'q'), 'q' to listOf('q', 'c'),
            's' to listOf('s', 'x'), 'x' to listOf('x', 's'),
            'f' to listOf('f', 'h'), 'h' to listOf('h', 'f'),
            'a' to listOf('a', 'o', 'e'), 'o' to listOf('o', 'a', 'e'), 'e' to listOf('e', 'a', 'o')
        )
        return pinyinMap[c1]?.contains(c2) == true || pinyinMap[c2]?.contains(c1) == true
    }
}
