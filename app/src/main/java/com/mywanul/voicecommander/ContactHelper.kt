package com.mywanul.voicecommander

import android.content.Context
import android.provider.ContactsContract

/**
 * Looks up a person in the phone's contacts by spoken name
 * and normalises Bangladeshi phone numbers to the 880... form
 * used by wa.me links and dial intents.
 */
object ContactHelper {

    data class ContactMatch(val displayName: String, val number: String)

    /**
     * Finds the best contact whose display name matches [name].
     * Scores: 3 = exact, 2 = one starts with / contains the other.
     * Returns null when nothing scores at least 2.
     */
    fun findPhone(context: Context, name: String): ContactMatch? {
        val wanted = normalizeName(name)
        if (wanted.isEmpty()) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null, null
        ) ?: return null
        var best: ContactMatch? = null
        var bestScore = 0
        cursor.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val displayName = it.getString(nameIdx) ?: continue
                val number = it.getString(numIdx) ?: continue
                val score = nameScore(normalizeName(displayName), wanted)
                if (score > bestScore) {
                    bestScore = score
                    best = ContactMatch(displayName, number)
                }
            }
        }
        return if (bestScore >= 2) best else null
    }

    private fun normalizeName(s: String): String =
        s.trim().lowercase().replace("\\s+".toRegex(), " ")

    private fun nameScore(display: String, wanted: String): Int {
        if (display.isEmpty() || wanted.isEmpty()) return 0
        if (display == wanted) return 3
        if (display.startsWith(wanted) || wanted.startsWith(display)) return 2
        if (display.contains(wanted) || wanted.contains(display)) return 2
        return 0
    }

    /**
     * 01XXXXXXXXX -> 8801XXXXXXXXX, +8801... -> 8801..., 8801... stays.
     * Other numbers are returned as digits only.
     */
    fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("880") -> digits
            digits.startsWith("0") && digits.length == 11 -> "880" + digits.substring(1)
            digits.length == 10 -> "880" + digits
            else -> digits
        }
    }
}
