package com.mywanul.voicecommander

import android.content.Context
import android.content.pm.PackageManager

/**
 * Resolves a spoken app name (Bengali or English) to an installed package.
 * Known apps are looked up first; anything else is matched against the
 * labels of installed apps by similarity.
 */
object AppResolver {

    const val BARTA_URL = "https://mywanul-bit.github.io/Barta/"

    private val knownApps = mapOf(
        "whatsapp" to "com.whatsapp",
        "হোয়াটসঅ্যাপ" to "com.whatsapp",
        "হোয়াটসাপ" to "com.whatsapp",
        "messenger" to "com.facebook.orca",
        "মেসেঞ্জার" to "com.facebook.orca",
        "facebook" to "com.facebook.katana",
        "ফেসবুক" to "com.facebook.katana",
        "ফেইসবুক" to "com.facebook.katana",
        "youtube" to "com.google.android.youtube",
        "ইউটিউব" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "ক্রোম" to "com.android.chrome",
        "camera" to "com.android.camera",
        "ক্যামেরা" to "com.android.camera",
        "gallery" to "com.google.android.apps.photos",
        "গ্যালারি" to "com.google.android.apps.photos",
        "photos" to "com.google.android.apps.photos",
        "ফটো" to "com.google.android.apps.photos",
        "settings" to "com.android.settings",
        "সেটিংস" to "com.android.settings",
        "phone" to "com.google.android.dialer",
        "ফোন" to "com.google.android.dialer",
        "messages" to "com.google.android.apps.messaging",
        "মেসেজ" to "com.google.android.apps.messaging",
        "gmail" to "com.google.android.gm",
        "জিমেইল" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "ম্যাপ" to "com.google.android.apps.maps",
        "গুগল ম্যাপ" to "com.google.android.apps.maps",
        "play store" to "com.android.vending",
        "প্লে স্টোর" to "com.android.vending",
        "clock" to "com.google.android.deskclock",
        "ঘড়ি" to "com.google.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "ক্যালকুলেটর" to "com.google.android.calculator",
        "calendar" to "com.google.android.calendar",
        "ক্যালেন্ডার" to "com.google.android.calendar",
        "drive" to "com.google.android.apps.docs",
        "ভয়েস কমান্ডার" to "com.mywanul.voicecommander"
    )

    fun normalize(s: String): String =
        s.trim().lowercase().replace("\\s+".toRegex(), " ")

    fun isKnownAppName(name: String): Boolean =
        knownApps.containsKey(normalize(name))

    fun isBarta(name: String): Boolean {
        val key = normalize(name)
        return key == "barta" || key == "বার্তা"
    }

    fun isInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Returns the package name for a spoken app name, or null if nothing matches.
     * Match scores: 3 = exact label, 2 = one contains the other. Score < 2 is rejected.
     */
    fun resolveApp(context: Context, name: String): String? {
        val key = normalize(name)
        knownApps[key]?.let { pkg ->
            if (isInstalled(context, pkg)) return pkg
        }
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        var best: String? = null
        var bestScore = 0
        for (app in apps) {
            val label = normalize(pm.getApplicationLabel(app).toString())
            val score = matchScore(label, key)
            if (score > bestScore) {
                bestScore = score
                best = app.packageName
            }
        }
        return if (bestScore >= 2) best else null
    }

    private fun matchScore(label: String, key: String): Int {
        if (label.isEmpty() || key.isEmpty()) return 0
        if (label == key) return 3
        if (label.contains(key) || key.contains(label)) return 2
        return 0
    }
}
