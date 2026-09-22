package com.ez.gboardw

/**
 * Shared DexKit fingerprint for Gboard's Vietnamese combination-rule loader.
 *
 * The loader is the single static method shaped
 * `(android.content.Context, java.util.Locale)` whose body logs
 * [RULE_PARSE_ERROR] when a `<rule>` XML entry lacks regex/replacement
 * (CombinationRulesLoader rule parsing; identical log text on 18.1.3 and
 * 18.2.4). Its return type is the ruleset message; the rule message is the
 * other message-typed class touched by its body (see [GboardPreflight]).
 *
 * Exactly one static method may match; anything else stays unavailable and
 * the caller keeps the hardcoded R8 mapping fallback. Class and method
 * names are read from the hits, so the next R8 rename needs no update.
 */
internal object GboardCombinationFingerprint {
    /** Log text emitted while parsing one combination `<rule>` entry. */
    const val RULE_PARSE_ERROR = "unexpected null regex or replacement in xml"

    /** Loader parameter shape: (Context, Locale). */
    val LOADER_PARAMS = listOf("android.content.Context", "java.util.Locale")

    /** Resolution outcome: loader + ruleset names plus body field refs. */
    data class Resolution(
        val loaderClass: String,
        val loaderMethod: String,
        val rulesetType: String,
        val usedFields: List<GboardBridgeRunner.UsedField>,
        val matchCount: Int,
        val status: String,
    ) {
        fun resolved(): Boolean = status == "resolved"
    }

    /**
     * Resolves the loader: the single static hit with the exact
     * (Context, Locale) parameter shape and a non-trivial return type.
     * Zero or several candidates stay unavailable.
     */
    fun evaluate(query: GboardBridgeRunner.LoaderQuery): Resolution {
        if (query.error.isNotEmpty()) {
            return Resolution("", "", "", emptyList(), query.matchCount, "query_failed")
        }
        val shaped = query.hits.filter { hit ->
            hit.isStatic &&
                hit.paramTypes == LOADER_PARAMS &&
                hit.returnType.isNotEmpty() &&
                hit.returnType != "void"
        }
        if (shaped.isEmpty()) {
            return Resolution("", "", "", emptyList(), query.matchCount, "no_loader")
        }
        if (shaped.size > 1) {
            return Resolution("", "", "", emptyList(), query.matchCount, "ambiguous_loader")
        }
        val hit = shaped[0]
        return Resolution(
            hit.className, hit.methodName, hit.returnType,
            hit.usedFields, query.matchCount, "resolved",
        )
    }
}
