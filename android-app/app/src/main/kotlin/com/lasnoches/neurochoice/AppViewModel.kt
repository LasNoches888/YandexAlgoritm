package com.lasnoches.neurochoice

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lasnoches.neurochoice.data.PythonBridge
import com.lasnoches.neurochoice.data.RejectionStore
import com.lasnoches.neurochoice.data.TasteResult
import com.lasnoches.neurochoice.data.TokenStore
import com.lasnoches.neurochoice.data.TrackInfo
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Login : Screen
    data object LoadingTaste : Screen
    data class Home(val taste: TasteResult) : Screen
    data class Working(val stage: String) : Screen
    data class Review(val title: String, val tracks: List<TrackInfo>) : Screen
    data class Result(val playlistUrl: String, val trackCount: Int, val tracks: List<TrackInfo>) : Screen
    data class ErrorScreen(val message: String) : Screen
}

private const val DEFAULT_PLAYLIST_NAME = "Выбор нейронки"
private const val CHEERFUL_PLAYLIST_NAME = "Маргарите, чтоб не грустила"
private const val CHEERFUL_TRACK_COUNT = 20
private const val CHEERFUL_MAX_PER_ARTIST = 3

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val tokenStore = TokenStore(app)
    private val rejectionStore = RejectionStore(app)
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

    var selectedTrackIds by mutableStateOf<Set<String>>(emptySet())
        private set

    private var lastTaste: TasteResult? = null
    private var pendingTitle: String = ""
    private var pendingTracks: List<TrackInfo> = emptyList()

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
                rejectionStore.getRejectedIds(),
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

            showReview(playlistName, picked.tracks)
        }
    }

    fun startCheerfulPlaylist() {
        val currentToken = token ?: return

        viewModelScope.launch {
            screen = Screen.Working("Ищу весёлую музыку для Маргариты...")
            val picked = PythonBridge.pickCheerfulTracks(
                appContext,
                currentToken,
                CHEERFUL_TRACK_COUNT,
                CHEERFUL_MAX_PER_ARTIST,
                rejectionStore.getRejectedIds(),
            )
            if (!picked.ok) {
                screen = Screen.ErrorScreen(picked.error ?: "Не удалось подобрать весёлую музыку")
                return@launch
            }
            if (picked.tracks.isEmpty()) {
                screen = Screen.ErrorScreen("Не нашлось подходящих треков для весёлого плейлиста.")
                return@launch
            }

            showReview(CHEERFUL_PLAYLIST_NAME, picked.tracks)
        }
    }

    private fun showReview(title: String, tracks: List<TrackInfo>) {
        pendingTitle = title
        pendingTracks = tracks
        selectedTrackIds = tracks.map { it.id }.toSet()
        screen = Screen.Review(title, tracks)
    }

    fun toggleTrackSelection(id: String) {
        selectedTrackIds = if (id in selectedTrackIds) selectedTrackIds - id else selectedTrackIds + id
    }

    fun confirmReview() {
        val currentToken = token ?: return
        val chosen = pendingTracks.filter { it.id in selectedTrackIds }
        if (chosen.isEmpty()) return

        val rejected = pendingTracks.filterNot { it.id in selectedTrackIds }.map { it.id }
        rejectionStore.addRejected(rejected)

        viewModelScope.launch {
            createPlaylistAndShowResult(currentToken, pendingTitle, chosen)
        }
    }

    fun cancelReview() {
        pendingTracks = emptyList()
        backToHome()
    }

    private suspend fun createPlaylistAndShowResult(currentToken: String, title: String, tracks: List<TrackInfo>) {
        screen = Screen.Working("Создаю плейлист «$title»...")
        val created = PythonBridge.createPlaylist(appContext, currentToken, title, tracks)
        if (!created.ok) {
            screen = Screen.ErrorScreen(created.error ?: "Не удалось создать плейлист")
            return
        }
        screen = Screen.Result(created.url, created.trackCount, tracks)
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
