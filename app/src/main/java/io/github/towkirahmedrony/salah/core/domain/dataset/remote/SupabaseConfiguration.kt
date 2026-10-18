package io.github.towkirahmedrony.salah.core.domain.dataset.remote

import java.net.URI

/**
 * The one rule that decides whether a Supabase client configuration is usable.
 *
 * Salah has three independent remote reads — the location-dataset publication, the prayer-configuration
 * publication and the published reference prices — and each has its own small configuration holder, because
 * each carries settings the others do not (a bucket and a dataset slot, a configuration slot, a table name).
 * What they must **not** each have is their own opinion about what "configured" means: three copies of a
 * blank-check would drift, and the drift would show up as one remote read attempting a request against an
 * endpoint another had already rejected. This object is that shared opinion, so "the app is configured for
 * Supabase" has exactly one definition and one test.
 *
 * It lives beside [RemoteFetch] because it is the same kind of thing: the small vocabulary the dataset,
 * prayer-configuration and price paths all share. It is deliberately Android-free — no `android.*` type — so
 * the rule is a JVM unit test rather than a device test.
 *
 * **What it checks, and what it does not.** It answers "is this value shaped like the thing it claims to be",
 * which is all a *client* can answer: the URL must be an absolute `http`/`https` URL with a host, and the key
 * must be a non-blank token with no embedded whitespace (which is what a value pasted with a trailing newline
 * looks like, and what would otherwise be sent as a broken header). It says nothing about whether the project
 * exists, whether the key is valid, or whether any row may be read. Those are decided by the project and by
 * row-level security at request time, and a client must never treat a well-formed configuration as proof of
 * access — see `docs/SUPABASE_SETUP.md` and `docs/DATABASE-SCHEMA.md`.
 */
object SupabaseConfiguration {

    /**
     * True when [value] is a usable Supabase project URL.
     *
     * A blank value is not configured, by design: an unconfigured build is a supported state, not an error.
     * A malformed one is rejected here rather than at the socket, so no code path has to build a request
     * against an endpoint that cannot exist.
     */
    fun isValidBaseUrl(value: String): Boolean {
        val candidate = value.trim()
        if (candidate.isEmpty()) return false
        // A URL cannot contain whitespace. Rejecting it here also catches the common paste that carries a
        // trailing newline, which `trim` would otherwise have accepted into a malformed host later.
        if (candidate.any { it.isWhitespace() }) return false
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        val scheme = uri.scheme ?: return false
        if (!scheme.equals(SCHEME_HTTPS, ignoreCase = true) && !scheme.equals(SCHEME_HTTP, ignoreCase = true)) {
            return false
        }
        val host = uri.host ?: return false
        return host.isNotBlank()
    }

    /**
     * True when [value] is a usable publishable (anon) client key.
     *
     * Only the shape is checked. Nothing here can tell a publishable key from a privileged one, which is
     * exactly why the privileged key must never be supplied to the build at all: no runtime check in the app
     * could contain the damage. See `docs/SUPABASE_SETUP.md`.
     */
    fun isValidClientKey(value: String): Boolean {
        val candidate = value.trim()
        return candidate.isNotEmpty() && candidate.none { it.isWhitespace() }
    }

    private const val SCHEME_HTTPS = "https"
    private const val SCHEME_HTTP = "http"
}
