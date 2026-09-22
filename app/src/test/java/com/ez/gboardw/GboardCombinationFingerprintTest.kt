package com.ez.gboardw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM oracle for [GboardCombinationFingerprint.evaluate]: uniqueness
 * decides, names are only read, never matched.
 */
class GboardCombinationFingerprintTest {

    private fun hit(
        owner: String,
        static: Boolean,
        params: List<String> = GboardCombinationFingerprint.LOADER_PARAMS,
        returns: String = "some.Ruleset",
    ) = GboardBridgeRunner.LoaderHit(
        className = owner,
        methodName = "a",
        returnType = returns,
        paramTypes = params,
        isStatic = static,
        usedFields = emptyList(),
    )

    private fun query(vararg hits: GboardBridgeRunner.LoaderHit) =
        GboardBridgeRunner.LoaderQuery(hits.size, hits.toList(), "")

    @Test fun resolvesSingleShapedLoader() {
        val res = GboardCombinationFingerprint.evaluate(query(hit("x.Loader", true)))
        assertTrue(res.resolved())
        assertEquals("x.Loader", res.loaderClass)
        assertEquals("some.Ruleset", res.rulesetType)
        assertEquals("resolved", res.status)
    }

    @Test fun ignoresNonStaticAndWrongParams() {
        val res = GboardCombinationFingerprint.evaluate(
            query(
                hit("x.A", false),
                hit("x.B", true, listOf("android.content.Context")),
                hit("x.C", true),
            )
        )
        assertTrue(res.resolved())
        assertEquals("x.C", res.loaderClass)
    }

    @Test fun rejectsVoidReturn() {
        val res = GboardCombinationFingerprint.evaluate(
            query(hit("x.A", true, returns = "void"))
        )
        assertFalse(res.resolved())
        assertEquals("no_loader", res.status)
    }

    @Test fun ambiguousWhenTwoMatch() {
        val res = GboardCombinationFingerprint.evaluate(
            query(hit("x.A", true), hit("x.B", true))
        )
        assertFalse(res.resolved())
        assertEquals("ambiguous_loader", res.status)
    }

    @Test fun emptyStaysUnavailable() {
        val res = GboardCombinationFingerprint.evaluate(query())
        assertFalse(res.resolved())
        assertEquals("no_loader", res.status)
    }

    @Test fun queryFailurePassesThrough() {
        val res = GboardCombinationFingerprint.evaluate(
            GboardBridgeRunner.LoaderQuery(0, emptyList(), "boom")
        )
        assertFalse(res.resolved())
        assertEquals("query_failed", res.status)
    }

    @Test fun normalizeTypeHandlesDescriptors() {
        assertEquals("a.B", GboardBridgeRunner.normalizeType("La/B;"))
        assertEquals("int", GboardBridgeRunner.normalizeType("I"))
        assertEquals("void", GboardBridgeRunner.normalizeType("V"))
        assertEquals("java.lang.String", GboardBridgeRunner.normalizeType("java.lang.String"))
        assertEquals("", GboardBridgeRunner.normalizeType(null))
    }
}
