package dev.leash.personal.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The app's text is English only. Android picks a plural's quantity class from the device locale, though, and a language such
 * as Chinese has only "other": "1 agents still running" on such a device. Resolve plurals with English rules instead.
 */
fun englishPlural(context: Context, @PluralsRes id: Int, count: Int): String {
    val config = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
    return context.createConfigurationContext(config).resources.getQuantityString(id, count, count)
}

@Composable
fun pluralText(@PluralsRes id: Int, count: Int): String {
    val context = LocalContext.current
    return remember(context, id, count) { englishPlural(context, id, count) }
}
