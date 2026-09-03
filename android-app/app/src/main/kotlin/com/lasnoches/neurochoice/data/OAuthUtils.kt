package com.lasnoches.neurochoice.data

import java.net.URLDecoder

/** Разбор implicit OAuth flow Яндекса: токен приходит в fragment (#...) редиректа. */
object OAuthUtils {

    const val AUTH_URL =
        "https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d"

    /**
     * Ищет access_token в URL редиректа вида
     * "https://.../#access_token=XXX&token_type=bearer&expires_in=..."
     * Возвращает null, если токена в URL нет.
     */
    fun extractAccessToken(url: String): String? {
        val fragment = url.substringAfter('#', missingDelimiterValue = "")
        if (fragment.isEmpty()) return null

        val params = fragment.split('&').associate { part ->
            val idx = part.indexOf('=')
            if (idx == -1) part to "" else part.substring(0, idx) to part.substring(idx + 1)
        }

        val rawToken = params["access_token"] ?: return null
        if (rawToken.isBlank()) return null

        return runCatching { URLDecoder.decode(rawToken, "UTF-8") }.getOrDefault(rawToken)
    }
}
