package com.blazemuzix.app

import com.blazemuzix.app.cloud.PasswordRules
import com.blazemuzix.app.data.models.MediaType
import com.blazemuzix.app.data.models.SearchFilter
import com.blazemuzix.app.data.models.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRulesTest {

    @Test
    fun passwordRequiresLetterAndNumber() {
        assertNotNull(PasswordRules.validate("short", "short"))
        assertNotNull(PasswordRules.validate("abcdefgh", "abcdefgh"))
        assertNotNull(PasswordRules.validate("12345678", "12345678"))
        assertNotNull(PasswordRules.validate("abc12345", "different"))
        assertNull(PasswordRules.validate("abc12345", "abc12345"))
    }

    @Test
    fun usernameAndEmail() {
        assertNotNull(PasswordRules.username("ab"))
        assertNotNull(PasswordRules.username("bad name"))
        assertNull(PasswordRules.username("blaze_user"))
        assertNotNull(PasswordRules.email("nope"))
        assertNull(PasswordRules.email("user@example.com"))
    }

    @Test
    fun strengthIncreasesWithComplexity() {
        assertTrue(PasswordRules.strength("a") < PasswordRules.strength("Abcdef12!"))
    }

    @Test
    fun searchFiltersIncludeCloudAndUsers() {
        assertEquals(Source.CLOUD, SearchFilter.CLOUD.source)
        assertEquals(MediaType.USER, SearchFilter.USERS.type)
        assertEquals(Source.CLOUD, SearchFilter.USERS.source)
    }
}
