package com.ez.gboardw

/**
 * Word-initial `w` rules injected into Gboard's Vietnamese combination set.
 *
 * Reference behaviour (Unikey engine `ukengine.cpp processTelexW`, Laban Key
 * `Telex-W`; vectors verified against the real engine in
 * `unikey-fixes/test/testunikey.cpp` and a local probe harness):
 * `w`->horn-u, `ww`->`w`, `www`->`ww`, `uw`->horn-u, `cw`->`cu-horn`,
 * `cww`->`cw`, `tw`->`tu-horn`, `W`->horn-U, `WW`->`W`, `qw`/`quw` literal,
 * tones compose after mapping (`ws`, `wng`, `cwngs`->`cung-sac`).
 * Gboard 18.1.3 ships only the modifier half (`uw`, `ow`, `aw`); this adds
 * the initial half plus the repeat-toggle the user required
 * (`ww`->`w`, `cww`->`cw`, `cwnw`->`cwnw`).
 *
 * Dialect: regex over the composing-buffer suffix, replacement group refs
 * are MARKER+digit. Boundary/onset mirror Gboard's own vi XML; `qu` is
 * excluded from ONSET (Unikey keeps `qw`/`quw` literal).
 *
 * Ordering: [undoRules] are PREPENDED before stock (they override stock's
 * horn-u undo on exactly the buffers below); [forwardRules] are APPENDED
 * (no stock rule matches their buffers - all 52 stock `[wW]$` rules need a
 * preceding vowel). R1/R2 fire on lower trigger only so upper-trigger
 * buffers stay on stock hook-undo (`wW`->`uW`, `cwnW`->`cunW` - both match
 * Unikey); no R2U exists because stock already handles every upper
 * vowel+coda+W buffer correctly (`CWnW`->`CUnW`).
 *
 * Known stateless divergences from Unikey (identical buffers, different
 * key history - unresolvable without engine state; resolved toward the
 * initial-w reading per the requested vectors): `uww`->`w` (Unikey `uw`),
 * `cuww`->`cw` (Unikey `cuw`), `cwnw`-keys->`cwnw` (Unikey mid-word `cunw`;
 * `cwnw` is Laban's space-restored form), `UWW`->`W` (Unikey `UW`).
 *
 * Non-goal (deliberate): validity-gated tone restore (`safe`->`safe`).
 * Fully restoring raw keys needs keystroke history the rule layer does not
 * have (same buffer, different keys: `afa` vs `aaf` -> `ầ`); that is engine
 * work and belongs in the fcitx fork, not in this tweak.
 */
internal object WRules {
    /** Gboard private-use marker: group ref is MARKER + digit. */
    const val MARKER = "\uee5c"
    const val BOUND = "(^|\uee5cs|[!-@[-`{-~])"
    /** Vietnamese onsets from Gboard vi XML, minus `[qQ][uU]`. */
    const val ONSET =
        "([bcdđghklmnprstvxyzBCDĐGHKLMNPRSTVXYZ]|[cC][hH]|[gG][iI]|[nN][hH]" +
            "|[kK][hH]|[tT][hH]|[pP][hH]|[nN][gG]|[gG][hH]|[tT][rR]" +
            "|[nN][gG][hH]|[\u0111\u0110]|[\u0111\u0110][rR])?"
    /**
     * Pure-consonant coda for R2. Deliberately narrower than stock's
     * undo coda: vowel glides (u/i/o/a) after horn-u belong to diphthongs
     * (`uoiw`-family, `uow`-family) whose trailing-w behavior stock already
     * gets right (`uoww`-undo, `uuw`->`uuw`-raw-restore); R2 must not steal
     * those buffers (verified against the engine probe).
     */
    const val CODA = "([cgmnptCGMNPT]|[nN][gG]|[nN][hH]|[cC][hH])"
    /** Forward maps (appended after stock). */
    val forwardRules: List<Pair<String, String>> = listOf(
        (BOUND + ONSET + "[w]$") to (MARKER + "1" + MARKER + "2\u01B0"),
        (BOUND + ONSET + "[W]$") to (MARKER + "1" + MARKER + "2\u01AF"),
    )
    /** Repeat-toggle undos (prepended before stock; R2 first). */
    val undoRules: List<Pair<String, String>> = listOf(
        (BOUND + ONSET + "\u01B0" + CODA + "([w])$") to (MARKER + "1" + MARKER + "2w" + MARKER + "3" + MARKER + "4"),
        (BOUND + ONSET + "\u01B0" + "([w])$") to (MARKER + "1" + MARKER + "2w"),
        (BOUND + ONSET + "\u01AF" + "([W])$") to (MARKER + "1" + MARKER + "2W"),
    )
    /** All injected rules (tests iterate both lists separately). */
    val rules: List<Pair<String, String>> = undoRules + forwardRules
    /**
     * JVM-testable equivalents (unit tests only; production sends [rules]
     * verbatim to Gboard). MARKER->`S`, refs->$n, and Gboard's punct class
     * (rejected by java.util.regex) spelled as explicit ASCII ranges.
     */
    fun testPatterns(rules: List<Pair<String, String>>): List<Pair<Regex, String>> =
        rules.map { (re, rep) ->
            val plainRe = re.replace("\uee5c", "S")
                .replace("[!-@[-`{-~]", "[\\x21-\\x40\\x5B-\\x60\\x7B-\\x7E]")
            val plainRep = rep.replace("\uee5c1", "\$1").replace("\uee5c2", "\$2")
                .replace("\uee5c3", "\$3").replace("\uee5c4", "\$4")
            Regex(plainRe) to plainRep
        }
}
