package com.ardeman.krida.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ardeman.krida.R
import java.util.Locale

/**
 * The app language: Indonesian or English, following the device until the player picks one.
 * Android 13+ keeps the choice in the system per-app language setting ([LocaleManager]), so
 * the in-app picker and Settings stay in sync. Older versions store it here and apply it in
 * the activity's attachBaseContext via [wrap].
 */
object Language {
    enum class Lang(val tag: String) { ID("id"), EN("en") }

    private const val PREFS = "language"
    private const val KEY = "tag"

    /** The language the activity's resources are showing now. */
    fun current(context: Context): Lang {
        // Java still reports Indonesian as the legacy code "in".
        val language = context.resources.configuration.locales[0].language
        return if (language == "in" || language == "id") Lang.ID else Lang.EN
    }

    fun set(activity: Activity, lang: Lang) {
        if (current(activity) == lang) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the activity with the new locale.
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(lang.tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang.tag).apply()
            activity.recreate()
        }
    }

    /** Before Android 13: the base context with the stored language applied, if any. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }
}

/** ID | EN segmented switch, styled like the difficulty picker. */
@Composable
fun LanguagePicker(modifier: Modifier = Modifier) {
    val activity = LocalContext.current as Activity
    val now = Language.current(activity)
    val label = stringResource(R.string.language)
    Row(
        modifier.background(Theme.track, RoundedCornerShape(12.dp)).padding(3.dp).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            Triple(Language.Lang.ID, R.string.language_id_short, R.string.language_id_name),
            Triple(Language.Lang.EN, R.string.language_en_short, R.string.language_en_name),
        ).forEach { (lang, short, name) ->
            val on = lang == now
            val shape = RoundedCornerShape(10.dp)
            val spoken = stringResource(name)
            Box(
                Modifier.height(38.dp)
                    .then(if (on) Modifier.shadow(1.5.dp, shape) else Modifier)
                    .clip(shape)
                    .background(if (on) Theme.raised else Color.Transparent)
                    .clickable(role = Role.Tab) { Sounds.play(Sounds.Effect.Tap); Language.set(activity, lang) }
                    .semantics { selected = on; contentDescription = spoken }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(short), style = Theme.font(14, color = if (on) Theme.ink else Theme.muted))
            }
        }
    }
}
