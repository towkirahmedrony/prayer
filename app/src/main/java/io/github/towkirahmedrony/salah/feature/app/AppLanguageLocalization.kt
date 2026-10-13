package io.github.towkirahmedrony.salah.feature.app

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import io.github.towkirahmedrony.salah.core.model.AppLanguage
import java.util.Locale

/**
 * Resolves every `stringResource` in [content] in the selected interface [language].
 *
 * **One local, and only the one that matters.** A localized `Context` would be the obvious lever, but
 * Salah has two screens that read `LocalContext` to walk up to the hosting `Activity` — the location
 * screen needs it to request a permission and the notification screen to open a system settings
 * intent — and a `createConfigurationContext` result is a sibling of the Activity, not a wrapper
 * around it, so that walk would stop finding it. String lookup does not go through `LocalContext`
 * anyway: Compose's `stringResource` reads `LocalResources`, whose default is the current context's
 * resources. Providing [LocalResources] therefore changes exactly what the interface is written in and
 * nothing else: the theme, the activity lookups, dialogs and every platform call keep the real
 * context.
 *
 * **Nothing is recreated.** Because the language arrives as a value, changing it recomposes the
 * subtree with a new [Resources]; no `Activity.recreate()`, no navigation reset and no lost scroll
 * position, which is what makes the switch immediate rather than a restart.
 *
 * **The device's configuration comes from the composition, not from the context.** The base
 * `Configuration` is read from [LocalConfiguration] rather than from `LocalContext.current.resources`,
 * because only the former is re-read when the device's own configuration changes: the context's copy
 * can be stale, so pinning the language from it could resurrect a previous device locale (the
 * `LocalContextConfigurationRead` check states this). Reading it from the composition also means the
 * `remember` below is keyed on the configuration, so changing the device locale rebuilds the resources
 * for the new base rather than keeping the old ones.
 *
 * [AppLanguage.SYSTEM] is a no-op on purpose: it forwards the caller's own resources untouched, so the
 * default and the pre-existing behaviour are the same code path — the device's locale decides.
 */
@Composable
fun ProvideAppLanguageStrings(
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    val tag = language.languageTag
    if (tag == null) {
        // Follow the device: the current resources already are the answer, so nothing is provided and
        // no wrapper is added to the composition.
        content()
    } else {
        val context = LocalContext.current
        // Configuration-aware: re-read when the device's configuration changes, unlike the context's own
        // (possibly stale) copy. It is also the `remember` key, so a device-locale change rebuilds.
        val deviceConfiguration = LocalConfiguration.current
        val resources = remember(tag, context, deviceConfiguration) {
            val configuration = Configuration(deviceConfiguration)
            // setLocale also updates the locale *list*, which is what Android 24+ actually resolves
            // resources against, so a plain setLocale is enough and stays off the deprecated path.
            configuration.setLocale(Locale.forLanguageTag(tag))
            context.createConfigurationContext(configuration).resources
        }
        CompositionLocalProvider(LocalResources provides resources) {
            content()
        }
    }
}
