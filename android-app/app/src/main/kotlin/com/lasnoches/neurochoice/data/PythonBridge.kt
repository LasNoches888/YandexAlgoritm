package com.lasnoches.neurochoice.data

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Обёртка над Python-модулем curator_bridge.py (см. app/src/main/python).
 * Все вызовы блокирующие внутри Python (сетевые запросы к Яндекс.Музыке),
 * поэтому выполняются на Dispatchers.IO.
 */
object PythonBridge {

    private val json = Json { ignoreUnknownKeys = true }

    private fun ensureStarted(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
    }

    private fun module(context: Context) = run {
        ensureStarted(context)
        Python.getInstance().getModule("curator_bridge")
    }

    suspend fun analyzeTaste(context: Context, token: String): TasteResult =
        withContext(Dispatchers.IO) {
            val raw = module(context).callAttr("analyze_taste", token).toString()
            json.decodeFromString(raw)
        }

    suspend fun pickTracks(
        context: Context,
        token: String,
        genreIds: List<String>,
        perGenre: Int,
        maxPerArtist: Int,
        excludeTrackIds: Set<String>,
    ): PickResult = withContext(Dispatchers.IO) {
        val genreIdsJson = json.encodeToString(genreIds)
        val excludeJson = json.encodeToString(excludeTrackIds.toList())
        val raw = module(context)
            .callAttr("pick_tracks", token, genreIdsJson, perGenre, maxPerArtist, excludeJson)
            .toString()
        json.decodeFromString(raw)
    }

    suspend fun pickCheerfulTracks(
        context: Context,
        token: String,
        count: Int,
        maxPerArtist: Int,
        excludeTrackIds: Set<String>,
    ): PickResult = withContext(Dispatchers.IO) {
        val excludeJson = json.encodeToString(excludeTrackIds.toList())
        val raw = module(context)
            .callAttr("pick_cheerful_tracks", token, count, maxPerArtist, excludeJson)
            .toString()
        json.decodeFromString(raw)
    }

    suspend fun createPlaylist(
        context: Context,
        token: String,
        title: String,
        tracks: List<TrackInfo>,
    ): CreatePlaylistResult = withContext(Dispatchers.IO) {
        val tracksJson = json.encodeToString(tracks)
        val raw = module(context).callAttr("create_playlist", token, title, tracksJson).toString()
        json.decodeFromString(raw)
    }
}
