package com.ez.gboardw

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData

/**
 * Sole holder of direct DexKit references.
 *
 * Isolation matters: if the native library or the DexKit classes are
 * unavailable, only this file fails to link, and the caller catches that
 * [Throwable] and keeps the hardcoded R8 mapping fallback. Nothing else in
 * the module references `org.luckypray.dexkit`.
 *
 * One bridge per discovery session, closed deterministically. File-backed
 * base-APK inspection only (all combination-rule anchors live in the base
 * APK on both mapped Gboard releases).
 *
 * Never extracts raw invoke lists (`getInvokes` aborts the process on some
 * methods); used-field lists are read with a per-hit cap instead.
 */
internal object GboardBridgeRunner {
    private var loadAttempted = false
    private var loadError = ""

    /** Cap on hits that receive used-field detail extraction. */
    const val MAX_DETAIL_HITS = 512

    /** Loads the native library once per process. Null on success, else why. */
    @Synchronized
    fun ensureLoaded(): String? {
        if (loadAttempted) return loadError.ifEmpty { null }
        loadAttempted = true
        try {
            System.loadLibrary("dexkit")
        } catch (t: Throwable) {
            loadError = t.javaClass.simpleName +
                (t.message?.let { ": $it" } ?: "")
        }
        return loadError.ifEmpty { null }
    }

    /** A static/instance field touched by a method. */
    data class UsedField(val owner: String, val name: String)

    /** Plain shape of one method hit; no DexKit types escape. */
    data class LoaderHit(
        val className: String,
        val methodName: String,
        val returnType: String,
        val paramTypes: List<String>,
        val isStatic: Boolean,
        val usedFields: List<UsedField>,
    )

    /** Outcome of the loader-method query. */
    data class LoaderQuery(
        val matchCount: Int,
        val hits: List<LoaderHit>,
        val error: String,
    )

    /**
     * Finds static `(Context, Locale)`-shaped methods whose body uses
     * [anchorString]. One bridge session; failures are reported per query,
     * never thrown.
     */
    fun findLoaderMethods(apkPath: String, anchorString: String): LoaderQuery {
        var bridge: DexKitBridge? = null
        try {
            bridge = DexKitBridge.create(apkPath)
            val matcher = MethodMatcher.create()
                .usingStrings(listOf(anchorString))
                .paramCount(2)
            val found = bridge.findMethod(FindMethod.create().matcher(matcher))
            val hits = mutableListOf<LoaderHit>()
            val detailed = found.size <= MAX_DETAIL_HITS
            for (method in found) {
                hits.add(toHit(method, detailed))
            }
            return LoaderQuery(found.size, hits, "")
        } catch (t: Throwable) {
            return LoaderQuery(0, emptyList(), failure(t))
        } finally {
            try {
                bridge?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun toHit(method: MethodData, detailed: Boolean): LoaderHit {
        val used = mutableListOf<UsedField>()
        if (detailed) {
            try {
                for (field in method.usingFields) {
                    val f = field?.field ?: continue
                    used.add(UsedField(f.className, f.name))
                }
            } catch (_: Throwable) {
            }
        }
        return LoaderHit(
            className = method.className,
            methodName = method.name,
            returnType = normalizeType(method.returnTypeName),
            paramTypes = method.paramTypeNames.map { normalizeType(it) },
            isStatic = (method.modifiers and 0x0008) != 0,
            usedFields = used,
        )
    }

    /** DexKit type names to readable dotted form (descriptors converted). */
    fun normalizeType(name: String?): String {
        if (name.isNullOrEmpty()) return ""
        if (name.startsWith("L") && name.endsWith(";")) {
            return name.substring(1, name.length - 1).replace('/', '.')
        }
        if (name.length == 1) {
            return when (name[0]) {
                'V' -> "void"
                'Z' -> "boolean"
                'B' -> "byte"
                'C' -> "char"
                'S' -> "short"
                'I' -> "int"
                'J' -> "long"
                'F' -> "float"
                'D' -> "double"
                else -> name
            }
        }
        return name
    }

    private fun failure(t: Throwable): String =
        t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
}
