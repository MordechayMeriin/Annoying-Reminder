package com.prominder.myapp.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The language of the app's own words (screens, buttons, notifications): the phone's, or one you pick in ⚙ Settings.
 * The words themselves are string resources (res/values = English, res/values-iw = Hebrew); this only chooses.
 *
 * Android's per-app language does the work: AppCompatDelegate.setApplicationLocales, which on Android 13+ is the
 * system's own setting (also in Settings → Apps → ProMinder → Language; the build lists the languages there from the
 * res/values-* folders) and before 13 is AppCompat's, for its activities only. So before 13 the choice is also kept here, applied at every start, and
 * [localized] gives code without an activity (notifications from an alarm, a tick) a context in that language.
 *
 * What the assistant writes isn't affected: it answers in the language you write in.
 */
object AppLanguage {
    /** The languages the app has words for (BCP 47 tags): one res/values-* folder each ("iw" is Hebrew's). */
    val SUPPORTED = listOf("en", "he")

    /** "" means the phone's language. */
    const val SYSTEM = ""

    private const val PREFS = "app_language"
    private const val KEY = "tag"

    /** The language picked in the app ([SYSTEM] when none): one of [SUPPORTED], or [SYSTEM]. */
    fun current(context: Context): String {
        val tags = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else {
            prefs(context).getString(KEY, SYSTEM).orEmpty()
        }
        return normalize(tags)
    }

    /** Switch to [tag] ([SYSTEM]: the phone's language). Open screens are recreated in it. */
    fun set(context: Context, tag: String) {
        val t = normalize(tag)
        if (Build.VERSION.SDK_INT < 33) prefs(context).edit { putString(KEY, t) }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(t))
    }

    /** Before Android 13, at the app's start: AppCompat doesn't remember the choice by itself (we keep it). */
    fun restore(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val t = current(context)
        if (t != SYSTEM) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(t))
    }

    /**
     * [context] in the app's language, for words shown outside an activity (notifications, channel names, the
     * shortcut). Before Android 13 only AppCompat's activities have the choice; on 13+ the app's context gets it too,
     * but only once the change has gone through, so [tag] is applied here either way.
     */
    fun localized(context: Context, tag: String = current(context)): Context {
        if (tag == SYSTEM) return context
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config)
    }

    /** The first supported language in [tags] ("he-IL,en" → "he"; "iw" is Java's old name for Hebrew), else [SYSTEM]. */
    fun normalize(tags: String): String =
        tags.split(',').map { it.trim().substringBefore('-').substringBefore('_').lowercase() }
            .map { if (it == "iw") "he" else it }
            .firstOrNull { it in SUPPORTED } ?: SYSTEM

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
