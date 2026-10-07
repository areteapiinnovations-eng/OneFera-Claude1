package com.onefera.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    @Test
    fun `builds below the minimum must update`() {
        val state = UpdateRules.evaluate(currentVersionCode = 2, policy = UpdatePolicy(minVersionCode = 3, latestVersionCode = 3))
        assertTrue(state is UpdateState.Required)
    }

    @Test
    fun `a newer version from the server or Play is offered`() {
        assertTrue(UpdateRules.evaluate(2, UpdatePolicy(latestVersionCode = 3)) is UpdateState.Available)
        assertTrue(UpdateRules.evaluate(2, null, playVersionCode = 4) is UpdateState.Available)
    }

    @Test
    fun `nothing to do when up to date or the offer was dismissed`() {
        assertEquals(UpdateState.None, UpdateRules.evaluate(3, UpdatePolicy(minVersionCode = 2, latestVersionCode = 3)))
        assertEquals(UpdateState.None, UpdateRules.evaluate(2, UpdatePolicy(latestVersionCode = 3), dismissedVersionCode = 3))
        assertEquals(UpdateState.None, UpdateRules.evaluate(2, null))
    }

    @Test
    fun `dismissing never skips a required update`() {
        val state = UpdateRules.evaluate(2, UpdatePolicy(minVersionCode = 3, message = "Security fix"), dismissedVersionCode = 3)
        assertEquals(UpdateState.Required("Security fix"), state)
    }
}
