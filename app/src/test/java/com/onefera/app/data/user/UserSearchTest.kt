package com.onefera.app.data.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSearchTest {
    @Test
    fun `keywords cover handle, full name and each word`() {
        val keywords = UserSearch.keywordsFor("Vineel Chalam", "vineel.chalam")
        assertTrue("vineel.ch" in keywords)
        assertTrue("vineel c" in keywords)
        assertTrue("chalam" in keywords)
        assertTrue("c" in keywords)
        assertTrue(keywords.none { it != it.lowercase() })
        assertTrue(keywords.size <= UserSearch.MAX_KEYWORDS)
    }

    @Test
    fun `typed queries match the stored form`() {
        assertEquals("vineel", UserSearch.termFor("  @Vineel "))
        assertEquals("a".repeat(20), UserSearch.termFor("A".repeat(30)))
    }
}
