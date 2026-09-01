package com.mitas.ppnam.station5aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * allowedTabs gating is fail-closed (MQTT base standard §5): a missing or empty list enables
 * no workflows. Station 5 defines no workflow tabs of its own yet, so canShow must return
 * false for anything until this station's contract defines tabs.
 */
class OperatorSessionTest {

    private fun session(tabs: List<String>) = OperatorSession(
        operatorSessionId = "s1",
        operatorId = "op1",
        operatorName = "Operator One",
        role = "Operator",
        allowedTabs = tabs,
    )

    @Test
    fun `an empty allowedTabs list enables nothing`() {
        val s = session(emptyList())
        assertFalse(s.canShow("tag_assignment"))
        assertFalse(s.canShow("offload"))
    }

    @Test
    fun `a missing allowedTabs list enables nothing`() {
        val s = OperatorSession(
            operatorSessionId = "s1",
            operatorId = "op1",
            operatorName = "Operator One",
            role = "Operator",
        )
        assertFalse(s.canShow("anything"))
    }

    @Test
    fun `only the listed workflows are enabled`() {
        val s = session(listOf("some_future_workflow"))
        assertTrue(s.canShow("some_future_workflow"))
        assertFalse(s.canShow("another_workflow"))
    }
}
