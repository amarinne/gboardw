package com.ez.gboardw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Buffer-level oracle for the injected rules. Expected values are Unikey
 * ground truth from a probe harness driving the real `UkEngine`
 * (`unikey/ukengine.cpp`, Telex, freeMarking on) plus the requested vectors
 * (`cwnw`). `null` means no injected rule matches and the buffer stays on
 * stock Gboard rules (whose behavior there matches Unikey: `uw`, `wW`->`uW`,
 * `cwnW`->`cunW`, toned-`u-horn`+`w` literal).
 * Production order is undos-first; [apply] tries undo patterns first.
 */
class WRulesTest {

    private val patterns =
        WRules.testPatterns(WRules.undoRules) + WRules.testPatterns(WRules.forwardRules)

    /** First matching injected rule applied, or null (stock owns buffer). */
    private fun apply(buffer: String): String? {
        for ((re, rep) in patterns) {
            if (re.containsMatchIn(buffer)) return re.replace(buffer, rep)
        }
        return null
    }

    @Test fun forwardInitialMaps() {
        assertEquals("\u01B0", apply("w"))
        assertEquals("t\u01B0", apply("tw"))
        assertEquals("th\u01B0", apply("thw"))
        assertEquals("ng\u01B0", apply("ngw"))
        assertEquals("c\u01B0", apply("cw"))
        assertEquals("\u01AF", apply("W"))
        assertEquals("T\u01AF", apply("TW"))
    }

    @Test fun forwardLeavesModifiersAndInvalidToStock() {
        assertNull(apply("uw"))
        assertNull(apply("aw"))
        assertNull(apply("ow"))
        assertNull(apply("uow"))
        assertNull(apply("qw"))
        assertNull(apply("quw"))
        assertNull(apply("QW"))
    }

    @Test fun repeatToggleRestoresW() {
        assertEquals("w", apply("\u01B0w"))
        assertEquals("cw", apply("c\u01B0w"))
        assertEquals("tw", apply("t\u01B0w"))
        assertEquals("ngw", apply("ng\u01B0w"))
        assertEquals("W", apply("\u01AFW"))
        assertEquals("cW", apply("c\u01AFW"))
    }

    @Test fun codaToggleRestoresW() {
        assertEquals("cwnw", apply("c\u01B0nw"))
    }

    @Test fun diphthongTailsStayOnStock() {
        // horn-u + vowel glide + w is a diphthong-undo the stock rules own
        // (probe: uowww->uow via (uo)-undo, wuw->uuw, wiw->uiw via u-undo).
        // R2 intentionally matches consonant codas only.
        assertNull(apply("\u01B0\u01A1w"))
        assertNull(apply("\u01B0uw"))
        assertNull(apply("\u01B0iw"))
        assertNull(apply("th\u01B0\u01A1w"))
    }

    @Test fun crossCaseAndTonedStayOnStock() {
        assertNull(apply("\u01B0W"))
        assertNull(apply("c\u01B0nW"))
        assertNull(apply("\u01AFw"))
        assertNull(apply("c\u1EE8ngw"))
    }

    @Test fun ruleSetsAreOrderedAndWellFormed() {
        assertEquals(3, WRules.undoRules.size)
        assertEquals(2, WRules.forwardRules.size)
        // R2 (coda) must precede R1 (bare).
        assert(WRules.undoRules[0].first.contains("([cgmnpt"))
        for ((re, _) in WRules.rules) {
            assert(re.endsWith("\$")) { "not anchored: $re" }
        }
    }
}
