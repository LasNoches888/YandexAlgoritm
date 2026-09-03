package com.lasnoches.neurochoice

import com.lasnoches.neurochoice.data.OAuthUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OAuthUtilsTest {

    @Test
    fun `extracts token from standard redirect fragment`() {
        val url = "https://music.yandex.ru/#access_token=AQAAAABc123xyz&token_type=bearer&expires_in=31535645"
        assertEquals("AQAAAABc123xyz", OAuthUtils.extractAccessToken(url))
    }

    @Test
    fun `returns null when there is no fragment`() {
        val url = "https://oauth.yandex.ru/authorize?response_type=token&client_id=abc"
        assertNull(OAuthUtils.extractAccessToken(url))
    }

    @Test
    fun `returns null when fragment has no access_token`() {
        val url = "https://music.yandex.ru/#error=access_denied"
        assertNull(OAuthUtils.extractAccessToken(url))
    }

    @Test
    fun `decodes url-encoded token value`() {
        val url = "https://music.yandex.ru/#access_token=abc%2Bdef&token_type=bearer"
        assertEquals("abc+def", OAuthUtils.extractAccessToken(url))
    }

    @Test
    fun `returns null for blank token value`() {
        val url = "https://music.yandex.ru/#access_token=&token_type=bearer"
        assertNull(OAuthUtils.extractAccessToken(url))
    }

    @Test
    fun `token can be the only fragment param`() {
        val url = "https://music.yandex.ru/#access_token=onlyvalue"
        assertEquals("onlyvalue", OAuthUtils.extractAccessToken(url))
    }
}
