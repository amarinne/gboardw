package com.ez.gboardw

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Injects word-initial w rules into Gboard's Vietnamese combination set.
 *
 * Resolution is dynamic: DexKit traces the loader from its stable rule-XML
 * log anchor, and [GboardPreflight] derives every member structurally, so
 * R8 renames (18.1.3 `fsc`/`ygj`/`ygi` -> 18.2.4 `fta`/`yhl`/`yhk`) need no
 * update. The hardcoded mapping table below is the last resort when the
 * DexKit native library is unavailable; members stay structurally derived
 * on that path too.
 *
 * Splice order mirrors the loader: [WRules.undoRules] BEFORE stock
 * (repeat-toggle overrides) and [WRules.forwardRules] AFTER stock
 * (gap fill, no overlap).
 */
class XposedHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(p: XC_LoadPackage.LoadPackageParam) {
        if (p.packageName != TARGET) return
        runCatching {
            val errors = mutableListOf<String>()
            val access = GboardPreflight.resolveViaDexKit(
                p.appInfo.sourceDir, p.classLoader, errors)
                ?: legacyAccess(p.classLoader, errors)
                ?: throw IllegalStateException(errors.joinToString("; "))
            XposedBridge.hookMethod(access.loaderMethod, spliceHook(access))
            XposedBridge.log("GboardW: hooked Vietnamese combination rules via " + access.via)
        }.onFailure {
            XposedBridge.log("GboardW: unavailable; unchanged (" + it.javaClass.simpleName + ")")
        }
    }

    private fun legacyAccess(
        loader: ClassLoader,
        errors: MutableList<String>,
    ): GboardPreflight.StructAccess? {
        for (triple in MAPPINGS) {
            val access = GboardPreflight.resolveLegacy(loader, triple, errors)
            if (access != null) return access
        }
        return null
    }

    private fun spliceHook(access: GboardPreflight.StructAccess): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(h: MethodHookParam) {
                try {
                    val locale = h.args[1] as? Locale ?: return
                    if (locale.language != "vi") return
                    val ruleset = h.result ?: return
                    logVersionOnce(h.args[0] as? Context)

                    val undos = WRules.undoRules.map { (re, rep) ->
                        GboardPreflight.buildRule(access, re, rep)
                    }
                    val forwards = WRules.forwardRules.map { (re, rep) ->
                        GboardPreflight.buildRule(access, re, rep)
                    }
                    val stock = GboardPreflight.stockRules(access, ruleset)
                    h.result = GboardPreflight.buildRuleset(access, undos, stock, forwards)
                    XposedBridge.log(
                        "GboardW: prepended " + undos.size +
                            ", appended " + forwards.size + " w rules (vi)"
                    )
                } catch (t: Throwable) {
                    XposedBridge.log(
                        "GboardW: rule inject failed (" +
                            t.javaClass.simpleName + ": " + t.message + ")"
                    )
                }
            }
        }
    }

    /** Host version trace, once per process, from the loader's Context arg. */
    private fun logVersionOnce(context: Context?) {
        if (!versionLogged.compareAndSet(false, true)) return
        try {
            val info = context?.packageManager?.getPackageInfo(TARGET, 0) ?: return
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            XposedBridge.log("GboardW: active on Gboard " + info.versionName + " ($code)")
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TARGET = "com.google.android.inputmethod.latin"
        private val versionLogged = AtomicBoolean(false)

        /**
         * (loader, ruleset, rule) R8 mappings per Gboard release, last
         * resort only: 18.2.4 first, since on 18.2.4 `fsc` still exists but
         * is an unrelated data class.
         */
        private val MAPPINGS = listOf(
            Triple("fta", "yhl", "yhk"), // 18.2.4
            Triple("fsc", "ygj", "ygi"), // 18.1.3
        )
    }
}
