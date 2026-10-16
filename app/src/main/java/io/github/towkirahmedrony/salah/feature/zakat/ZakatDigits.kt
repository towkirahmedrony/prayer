package io.github.towkirahmedrony.salah.feature.zakat

/**
 * Rewrites digits from the scripts Salah's own language support can produce into Western form.
 *
 * One implementation, three callers: the numeric fields' keystroke filter, the Hawl date field, and the date
 * parser behind both. A second copy of the mapping is how the same character would start being accepted in one
 * place and rejected in another — which is exactly the bug the leading-decimal and Hawl-digit fixes were
 * about. See `ZakatViewModel` for why Bengali and Arabic-Indic are the two scripts it maps.
 *
 * The mapping is arithmetic rather than a lookup table: each of these ranges is ten consecutive code points in
 * ascending digit order, so the offset within the range *is* the digit. A table would be ten numbers per script
 * with the chance of a typo in any of them.
 */
internal object ZakatDigits {

    /** The first Bengali digit, U+09E6. */
    private const val BENGALI_ZERO = '\u09E6'

    /** The last Bengali digit, U+09EF. */
    private const val BENGALI_NINE = '\u09EF'

    /** The first Arabic-Indic digit, U+0660. */
    private const val ARABIC_ZERO = '\u0660'

    /** The last Arabic-Indic digit, U+0669. */
    private const val ARABIC_NINE = '\u0669'

    /**
     * [character] as a Western digit, or `null` when it is not a digit in any script this feature accepts.
     *
     * Western, Bengali (U+09E6) and Arabic-Indic (U+0660) are the three scripts the app's language support can
     * produce. Everything else — a full-width digit, a Devanagari one, a letter, a separator — is left for the
     * caller to drop or reject.
     */
    fun westernDigit(character: Char): Char? = when (character) {
        in '0'..'9' -> character
        in BENGALI_ZERO..BENGALI_NINE -> '0' + (character - BENGALI_ZERO)
        in ARABIC_ZERO..ARABIC_NINE -> '0' + (character - ARABIC_ZERO)
        else -> null
    }

    /**
     * [text] with every recognised digit rewritten as a Western one, every other character left as it is.
     *
     * Unlike the numeric fields' filter this drops nothing: a date is structured text, and a `-` a user typed
     * has to survive for the ISO parser to read it.
     */
    fun toWestern(text: String): String = buildString(text.length) {
        text.forEach { character -> append(westernDigit(character) ?: character) }
    }
}
