package com.ez.gboardw

import android.content.Context
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * Live gate before any hook installs (second gate after DexKit): revalidates
 * the resolved symbols against the running class loader and derives every
 * member structurally, so no obfuscated member name is trusted.
 *
 * Derivation uses only stable shapes:
 * - loader: static, `(Context, Locale)`, non-trivial return (= ruleset).
 * - rule message: no-arg constructor, one instance `int` (bitmask), two
 *   instance `String`s (regex then replacement, declaration order).
 * - ruleset message: no-arg constructor, first instance `java.util.List`
 *   field (declaration order; the legacy ruleset had two, rules first).
 * - list growth: the single `(int)` method on the list type whose return
 *   fits the field. The stock list is never mutated in place; a grown copy
 *   is installed on a freshly constructed ruleset instead. No builder
 *   (`l`/`s`/`p`) is touched at all.
 *
 * Schema assumptions (proto schema, not obfuscation): bitmask bit 1 =
 * regex set, bit 2 = replacement set (value 3); regex field precedes
 * replacement in declaration order.
 */
internal object GboardPreflight {
    /** Bitmask bits for proto fields 1 (regex) and 2 (replacement). */
    const val BITMASK_BOTH = 3

    /** Fully derived access kit; splice code uses nothing else. */
    data class StructAccess(
        val loaderMethod: Method,
        val rulesetCls: Class<*>,
        val ruleCls: Class<*>,
        val rulesetCtor: Constructor<*>,
        val ruleCtor: Constructor<*>,
        val bitmaskField: Field,
        val regexField: Field,
        val replacementField: Field,
        val listField: Field,
        val growMethod: Method,
        val via: String,
    )

    /** DexKit path: query, fingerprint, classify, derive. Null + reasons. */
    fun resolveViaDexKit(
        apkPath: String,
        loader: ClassLoader,
        errors: MutableList<String>,
    ): StructAccess? {
        val loadError = GboardBridgeRunner.ensureLoaded()
        if (loadError != null) {
            errors.add("dexkit native unavailable: $loadError")
            return null
        }
        val query = GboardBridgeRunner.findLoaderMethods(
            apkPath, GboardCombinationFingerprint.RULE_PARSE_ERROR)
        val resolution = GboardCombinationFingerprint.evaluate(query)
        if (!resolution.resolved()) {
            errors.add("dexkit fingerprint ${resolution.status} " +
                "(matches=${resolution.matchCount})")
            return null
        }
        return try {
            val loaderCls = Class.forName(resolution.loaderClass, false, loader)
            val loaderMethod = findLoaderMethod(loaderCls)
                ?: return null.also {
                    errors.add("${resolution.loaderClass} has no static " +
                        "(Context, Locale) method")
                }
            derive(loaderMethod, resolution.usedFields, loader,
                "dexkit:${resolution.loaderClass}", errors)
        } catch (t: Throwable) {
            errors.add("dexkit classify failed: ${short(t)}")
            null
        }
    }

    /** Hardcoded R8 mapping path; members still derived structurally. */
    fun resolveLegacy(
        loader: ClassLoader,
        triple: Triple<String, String, String>,
        errors: MutableList<String>,
    ): StructAccess? {
        return try {
            val loaderCls = Class.forName(triple.first, false, loader)
            Class.forName(triple.second, false, loader)
            Class.forName(triple.third, false, loader)
            val loaderMethod = findLoaderMethod(loaderCls)
                ?: return null.also {
                    errors.add("legacy ${triple.first} has no static " +
                        "(Context, Locale) method")
                }
            // Same owner-shape classification as the DexKit path, seeded
            // from the pinned names instead of used-field refs.
            derive(loaderMethod,
                listOf(
                    GboardBridgeRunner.UsedField(triple.second, ""),
                    GboardBridgeRunner.UsedField(triple.third, ""),
                ),
                loader, "legacy:${triple.first}", errors)
        } catch (t: Throwable) {
            errors.add("legacy ${triple.first} failed: ${short(t)}")
            null
        }
    }

    /** Static (Context, Locale) method; the loader signature. */
    private fun findLoaderMethod(loaderCls: Class<*>): Method? {
        val found = loaderCls.declaredMethods.filter { m ->
            Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.contentEquals(
                    arrayOf(Context::class.java, Locale::class.java))
        }
        return found.singleOrNull()
    }

    private fun derive(
        loaderMethod: Method,
        owners: List<GboardBridgeRunner.UsedField>,
        loader: ClassLoader,
        via: String,
        errors: MutableList<String>,
    ): StructAccess? {
        val rulesetCls = loaderMethod.returnType
        if (rulesetCls == Void.TYPE || rulesetCls.isPrimitive) {
            errors.add("$via loader returns trivial type")
            return null
        }
        var rulesetSeen = false
        val ruleCandidates = mutableListOf<Class<*>>()
        for (owner in owners.map { it.owner }.distinct()) {
            if (owner.isEmpty() || isResourceClass(owner)) continue
            val cls = try {
                Class.forName(owner, false, loader)
            } catch (_: Throwable) {
                continue
            }
            if (cls == rulesetCls) {
                rulesetSeen = true
                continue
            }
            if (isRuleShaped(cls)) ruleCandidates.add(cls)
        }
        if (!rulesetSeen) {
            errors.add("$via ruleset ${rulesetCls.name} not referenced")
            return null
        }
        if (ruleCandidates.size != 1) {
            errors.add("$via rule candidates: ${ruleCandidates.size}")
            return null
        }
        val ruleCls = ruleCandidates[0]

        val rulesetCtor = noArgCtor(rulesetCls)
            ?: return null.also { errors.add("$via ruleset has no no-arg ctor") }
        val ruleCtor = noArgCtor(ruleCls)
            ?: return null.also { errors.add("$via rule has no no-arg ctor") }

        val intFields = instanceFields(ruleCls, Integer.TYPE)
        val stringFields = instanceFields(ruleCls, String::class.java)
        if (intFields.size != 1 || stringFields.size != 2) {
            errors.add("$via rule shape drift (int=${intFields.size}, " +
                "str=${stringFields.size})")
            return null
        }

        val listFields = ruleListFields(rulesetCls)
        if (listFields.isEmpty()) {
            errors.add("$via ruleset has no List field")
            return null
        }
        val listField = listFields[0]
        val grow = growMethod(listField)
            ?: return null.also {
                errors.add("$via no (int)->List grow on ${listField.type.name}")
            }

        access(
            rulesetCtor, ruleCtor, intFields[0],
            stringFields[0], stringFields[1], listField)
        return StructAccess(
            loaderMethod, rulesetCls, ruleCls,
            rulesetCtor, ruleCtor, intFields[0],
            stringFields[0], stringFields[1], listField, grow, via,
        )
    }

    private fun isResourceClass(name: String): Boolean =
        name.endsWith(".R") || name.contains(".R\$")

    /** Rule shape: one instance int + two instance Strings. */
    private fun isRuleShaped(cls: Class<*>): Boolean {
        if (noArgCtor(cls) == null) return false
        return instanceFields(cls, Integer.TYPE).size == 1 &&
            instanceFields(cls, String::class.java).size == 2
    }

    private fun noArgCtor(cls: Class<*>): Constructor<*>? {
        return try {
            cls.getDeclaredConstructor()
        } catch (_: Throwable) {
            null
        }
    }

    /** Non-static fields of [type] in declaration order. */
    private fun instanceFields(cls: Class<*>, type: Class<*>): List<Field> =
        cls.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) && f.type == type
        }

    /** Non-static `java.util.List` fields in declaration order. */
    private fun ruleListFields(rulesetCls: Class<*>): List<Field> =
        rulesetCls.declaredFields.filter { f ->
            !Modifier.isStatic(f.modifiers) &&
                java.util.List::class.java.isAssignableFrom(f.type)
        }

    /** Single `(int)` method whose return fits the list field. */
    private fun growMethod(listField: Field): Method? {
        val found = listField.type.methods.filter { m ->
            !Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.contentEquals(arrayOf(Integer.TYPE)) &&
                listField.type.isAssignableFrom(m.returnType)
        }
        return found.singleOrNull()
    }

    private fun access(vararg members: java.lang.reflect.AccessibleObject) {
        for (m in members) {
            try {
                m.isAccessible = true
            } catch (_: Throwable) {
            }
        }
    }

    /** One injected rule instance. */
    fun buildRule(access: StructAccess, regex: String, replacement: String): Any {
        val inst = access.ruleCtor.newInstance()
        access.bitmaskField.setInt(inst, BITMASK_BOTH)
        access.regexField.set(inst, regex)
        access.replacementField.set(inst, replacement)
        return inst
    }

    /**
     * Fresh ruleset with undos first, stock copied over, forwards last.
     * The stock list is only read, never mutated.
     */
    fun buildRuleset(
        access: StructAccess,
        undos: List<Any>,
        stock: List<*>,
        forwards: List<Any>,
    ): Any {
        val inst = access.rulesetCtor.newInstance()
        val current = access.listField.get(inst)
        val grown = access.growMethod.invoke(
            current, undos.size + stock.size + forwards.size + 8)
        access.listField.set(inst, grown)
        @Suppress("UNCHECKED_CAST")
        val target = grown as MutableList<Any>
        target.addAll(undos)
        for (rule in stock) target.add(rule as Any)
        target.addAll(forwards)
        return inst
    }

    /** Stock rule list read from a built ruleset. */
    fun stockRules(access: StructAccess, ruleset: Any): List<*> =
        access.listField.get(ruleset) as List<*>

    private fun short(t: Throwable): String =
        t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
}
