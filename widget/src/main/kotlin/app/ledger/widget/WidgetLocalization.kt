package app.ledger.widget

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

internal fun Context.withLanguageTag(languageTag: String): Context {
    val locale = Locale.forLanguageTag(languageTag)
    if (locale.language.isBlank()) return this
    return createConfigurationContext(
        Configuration(resources.configuration).apply {
            setLocales(LocaleList(locale))
        },
    )
}
