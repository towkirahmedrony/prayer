package io.github.towkirahmedrony.salah.data.mosque

import java.net.URLEncoder

/**
 * The map style the mosque finder asks MapLibre to render.
 *
 * **MapLibre renders; it does not supply data.** The SDK is a rendering engine and its own download
 * contains no map of the world. The style document names Geoapify's vector tile sources, fonts and
 * sprites, and the API key authorises those requests. This is why the screen must have a configured
 * Geoapify key before it can draw a map at all — the same key the Places search uses.
 *
 * **Why two styles.** `positron` is a light, low-contrast canvas that keeps markers legible on Salah's
 * cream background; `dark-matter` is its dark counterpart. Following the active theme rather than
 * always using one keeps a dark screen from flashing a white map and, just as importantly, keeps the
 * app's own theme promise visible on this screen.
 *
 * **Caching and offline.** The style and its tiles are fetched by MapLibre at draw time. The app does
 * not download tiles in advance, does not bulk-copy them from public OpenStreetMap servers, and makes
 * no claim that the map works offline. Cached Places results are a separate mechanism with separate
 * terms: the mosque *list* can be shown from Room, but a map with no connection is reported as
 * unavailable rather than silently drawing stale or unrelated tiles. See `docs/MOSQUE_FINDER.md`.
 *
 * The key is part of the URL by necessity. It is never logged, never written into error messages, and
 * [toString] is not derived from this object because the object has no state beyond [styleUrl]'s
 * parameters.
 */
object MosqueMapStyle {

    /** The style that reads correctly on a light Salah canvas. */
    const val LIGHT_STYLE: String = "positron"

    /** The style that reads correctly on a dark Salah canvas. */
    const val DARK_STYLE: String = "dark-matter"

    /** The base URL of Geoapify's hosted styles. */
    const val BASE_URL: String = "https://maps.geoapify.com/v1/styles"

    /**
     * The style URL for [apiKey], or `null` when there is no key to authorise the tiles.
     *
     * A blank key is a normal not-configured state: the screen shows the list and explains that the
     * map needs a key, rather than asking MapLibre to load a URL that could only fail.
     */
    fun styleUrl(apiKey: String, darkTheme: Boolean): String? {
        val key = apiKey.trim()
        if (key.isEmpty()) return null
        val style = if (darkTheme) DARK_STYLE else LIGHT_STYLE
        return "$BASE_URL/$style/style.json?apiKey=${URLEncoder.encode(key, Charsets.UTF_8.name())}"
    }
}
