package com.lasnoches.neurochoice.data

import kotlinx.serialization.Serializable

@Serializable
data class GenreInfo(
    val id: String,
    val title: String,
    val count: Int,
)

@Serializable
data class TasteResult(
    val ok: Boolean,
    val genres: List<GenreInfo> = emptyList(),
    val likedTrackCount: Int = 0,
    val knownArtistCount: Int = 0,
    val error: String? = null,
)

@Serializable
data class TrackInfo(
    val id: String,
    val albumId: String,
    val title: String,
    val artists: List<String>,
    val coverUrl: String? = null,
) {
    val artistsLabel: String get() = artists.joinToString(", ")
}

@Serializable
data class PickResult(
    val ok: Boolean,
    val tracks: List<TrackInfo> = emptyList(),
    val perGenreFound: Map<String, Int> = emptyMap(),
    val error: String? = null,
)

@Serializable
data class CreatePlaylistResult(
    val ok: Boolean,
    val url: String = "",
    val kind: Int = 0,
    val trackCount: Int = 0,
    val error: String? = null,
)
