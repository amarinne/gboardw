package com.ez.gboardw

import android.content.Context
import java.util.Locale
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Injects word-initial w rules into Gboard's Vietnamese combination set.
 *
 * Gboard 18.1.3 loads per-locale regex/replacement rules in `fsc.a`
 * (CombinationRulesLoader.createCombinationRules): `ygj` is the result
 * message, `ygi` one rule (fields `c` = regex, `d` = replacement,
 * `b` = bitmask). 0.1.0 set those fields on the `zug` builder instead of
 * its inner message (`builder.b`) and appended to the already-built
 * immutable list, so injection always threw and Gboard kept its stock
 * rules. This version mirrors `fsc.java` exactly and splices
 * [WRules.undoRules] BEFORE stock (repeat-toggle overrides) and
 * [WRules.forwardRules] AFTER stock (gap fill, no overlap).
 */
class XposedHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(p: XC_LoadPackage.LoadPackageParam) {
        if (p.packageName != TARGET) return
        runCatching {
            val loader = p.classLoader
            val rulesCls = loader.loadClass("fsc")
            val ygjCls = loader.loadClass("ygj")
            val ygiCls = loader.loadClass("ygi")
            val method =
                rulesCls.getDeclaredMethod("a", Context::class.java, Locale::class.java)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(h: MethodHookParam) {
                    try {
                        val locale = h.args[1] as? Locale ?: return
                        if (locale.language != "vi") return
                        val ruleset = h.result ?: return

                        fun buildRule(regex: String, replacement: String): Any {
                            val ygiDefault = ygiCls.getField("a").get(null)
                            val builder = ygiCls.getMethod("l").invoke(ygiDefault)
                            builder.javaClass.getMethod("s").invoke(builder)
                            val inner = builder.javaClass.getField("b").get(builder)
                            ygiCls.getField("b").setInt(inner, 3)
                            ygiCls.getField("c").set(inner, regex)
                            ygiCls.getField("d").set(inner, replacement)
                            return builder.javaClass.getMethod("p").invoke(builder)
                        }

                        val undos = WRules.undoRules.map { (re, rep) -> buildRule(re, rep) }
                        val forwards =
                            WRules.forwardRules.map { (re, rep) -> buildRule(re, rep) }

                        val ygjDefault = ygjCls.getField("a").get(null)
                        val outer = ygjCls.getMethod("l").invoke(ygjDefault)

                        // Start from an empty builder so undos land first;
                        // then copy the stock rules over one by one.
                        val outerInner = outer.javaClass.getField("b").get(outer)
                        val listField = ygjCls.getField("b")
                        val stockList = listField.get(ruleset) as List<*>
                        val freshCount = undos.size + stockList.size + forwards.size
                        // Grow like fsc.java does (size + size); +8 headroom.
                        var list = listField.get(outerInner)
                        val mutableCheck =
                            list.javaClass.getMethod("c").invoke(list) as Boolean
                        if (!mutableCheck) {
                            val size = (list as List<*>).size
                            val grown = list.javaClass
                                .getMethod("e", Int::class.javaPrimitiveType)
                                .invoke(list, size + size + freshCount + 8)
                            listField.set(outerInner, grown)
                            list = grown
                        }
                        @Suppress("UNCHECKED_CAST")
                        val target = list as MutableList<Any>
                        for (r in undos) target.add(r)
                        for (r in stockList) target.add(r as Any)
                        for (r in forwards) target.add(r)

                        h.result = outer.javaClass.getMethod("p").invoke(outer)
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
            })
            XposedBridge.log("GboardW: hooked Vietnamese combination rules")
        }.onFailure {
            XposedBridge.log("GboardW: unavailable; unchanged (" + it.javaClass.simpleName + ")")
        }
    }

    companion object {
        private const val TARGET = "com.google.android.inputmethod.latin"
    }
}
