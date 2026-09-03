package com.lasnoches.neurochoice

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lasnoches.neurochoice.data.PythonBridge
import com.lasnoches.neurochoice.data.TasteResult
import com.lasnoches.neurochoice.data.TokenStore
import com.lasnoches.neurochoice.data.TrackInfo
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Login : Screen
    data object LoadingTaste : Screen
    data class Home(val taste: TasteResult) : Screen
    data class Working(val stage: String) : Screen
    data class Result(val playlistUrl: String, val trackCount: Int, val tracks: List<TrackInfo>) : Screen
    data class ErrorScreen(val message: String) : Screen
}

private const val DEFAULT_PLAYLIST_NAME = "Выбор нейронки"

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val tokenStore = TokenStore(app)
    private val appContext = app.applicationContext

    var token by mutableStateOf(tokenStore.getToken())
        private set

    var screen by mutableStateOf<Screen>(if (token != null) Screen.LoadingTaste else Screen.Login)
        private set

    var playlistName by mutableStateOf(DEFAULT_PLAYLIST_NAME)
    var perGenre by mutableIntStateOf(15)
    var maxPerArtist by mutableIntStateOf(2)
    var selectedGenreIds by mutableStateOf<Set<String>>(emptySet())
        private set

    private var lastTaste: TasteResult? = null

    init {
        if (token != null) loadTaste()
    }

    fun onLoginSuccess(newToken: String) {
        tokenStore.saveToken(newToken)
        token = newToken
        loadTaste()
    }

    fun logout() {
        tokenStore.clearToken()
        token = null
        lastTaste = null
        selectedGenreIds = emptySet()
        playlistName = DEFAULT_PLAYLIST_NAME
        screen = Screen.Login
    }

    fun toggleGenre(id: String) {
        selectedGenreIds = if (id in selectedGenreIds) selectedGenreIds - id else selectedGenreIds + id
    }

    fun loadTaste() {
        val currentToken = token ?: return
        screen = Screen.LoadingTaste
        viewModelScope.launch {
            val result = PythonBridge.analyzeTaste(appContext, currentToken)
            if (!result.ok) {
                screen = Screen.ErrorScreen(result.error ?: "Не удалось получить данные аккаунта")
                return@launch
            }
            lastTaste = result
            selectedGenreIds = result.genres.take(3).map { it.id }.toSet()
            screen = Screen.Home(result)
        }
    }

    fun startCuration() {
        val currentToken = token ?: return
        if (selectedGenreIds.isEmpty()) return

        viewModelScope.launch {
            screen = Screen.Working("Ищу новые треки в выбранных жанрах...")
            val picked = PythonBridge.pickTracks(
                appContext,
                currentToken,
                selectedGenreIds.toList(),
                perGenre,
                maxPerArtist,
            )
            if (!picked.ok) {
                screen = Screen.ErrorScreen(picked.error ?: "Не удалось подобрать треки")
                return@launch
            }
            if (picked.tracks.isEmpty()) {
                screen = Screen.ErrorScreen(
                    "Не нашлось подходящих новых треков. Попробуй увеличить «треков на жанр» " +
                        "или выбрать другие жанры."
                )
                return@launch
            }

            screen = Screen.Working("Создаю плейлист «$playlistName»...")
            val created = PythonBridge.createPlaylist(appContext, currentToken, playlistName, picked.tracks)
            if (!created.ok) {
                screen = Screen.ErrorScreen(created.error ?: "Не удалось создать плейлист")
                return@launch
            }

            screen = Screen.Result(created.url, created.trackCount, picked.tracks)
        }
    }

    fun backToHome() {
        val taste = lastTaste
        if (taste != null) {
            screen = Screen.Home(taste)
        } else {
            loadTaste()
        }
    }

    fun retryFromError() {
        when {
            token == null -> screen = Screen.Login
            lastTaste == null -> loadTaste()
            else -> screen = Screen.Home(lastTaste!!)
        }
    }
}
