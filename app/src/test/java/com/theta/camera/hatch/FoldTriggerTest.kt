package com.theta.camera.hatch
import org.junit.Assert.*
import org.junit.Test
class FoldTriggerTest {
    @Test fun initialOpenDoesNotSwitch() { assertFalse(FoldTrigger().update(180f)) }
    @Test fun singleRequestPerCycle() {
        val f=FoldTrigger()
        assertFalse(f.update(0f)); assertFalse(f.update(90f)); assertTrue(f.update(160f))
        assertFalse(f.update(180f)); assertFalse(f.update(160f))
        f.update(0f); assertTrue(f.update(180f))
    }
    @Test fun halfOpenAndJitterDoNotTrigger() {
        val f=FoldTrigger(); f.update(3f)
        listOf(60f, 95f, 149f, 152f, 150f).forEach { assertFalse(f.update(it)) }
        assertTrue(f.update(155f))
    }
    @Test fun resetPreventsBackgroundTransitions() {
        val f=FoldTrigger(); f.update(0f); f.reset(); assertFalse(f.update(180f))
    }
}
