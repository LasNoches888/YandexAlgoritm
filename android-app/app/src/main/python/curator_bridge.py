"""
Мост между Kotlin (Chaquopy) и библиотекой yandex-music.

Каждая функция самодостаточна: получает токен, сама логинится и сама
делает нужные запросы. Все функции принимают/возвращают строки (JSON),
чтобы Kotlin мог однозначно распарсить результат через kotlinx.serialization
без работы с "сырыми" Python-объектами через Chaquopy.

Логика подбора треков идентична десктопному music_curator.py: любимые
жанры считаются по лайкам, известные исполнители (лайкнутые, дизлайкнутые,
исполнители лайкнутых треков) исключаются из выдачи. Кандидаты на трек
берутся в первую очередь через "похожие на уже лайкнутое" (tracks_similar),
жанровая радиостанция — второй источник, чтобы добрать недостающее.
"""
import json
from collections import Counter

from yandex_music import Client


def _chunked(seq, size):
    for i in range(0, len(seq), size):
        yield seq[i : i + size]


def _get_liked_full_tracks(client):
    likes = client.users_likes_tracks()
    if not likes or not likes.tracks:
        return []
    track_ids = [t.track_id for t in likes.tracks]
    full = []
    for chunk in _chunked(track_ids, 100):
        full.extend(client.tracks(chunk))
    return full


def _track_genre(track):
    if track.albums:
        return track.albums[0].genre
    return None


_VARIANT_KEYWORDS = (
    "remix",
    "rmx",
    "mashup",
    "bootleg",
    "cover",
    "tribute",
    "karaoke",
    "acapella",
    "a cappella",
    "instrumental",
    "nightcore",
    "sped up",
    "speed up",
    "slowed",
    "8d audio",
    "extended mix",
    "vip mix",
    "dub mix",
    "club mix",
    "night mix",
    "acoustic",
    "live",
    "unplugged",
    "radio edit",
    "chipmunk",
    "reversed",
    "dj edit",
)


def _is_variant_track(track):
    """True для ремиксов/каверов/лайвов и похожих "не оригинальных" версий."""
    haystack = f"{getattr(track, 'version', '') or ''} {track.title or ''}".lower()
    return any(keyword in haystack for keyword in _VARIANT_KEYWORDS)


def _track_to_dict(track):
    return {
        "id": str(track.id),
        "albumId": str(track.albums[0].id),
        "title": track.title,
        "artists": [a.name for a in (track.artists or [])],
        "coverUrl": track.get_cover_url("300x300") if track.cover_uri else None,
    }


def _known_artist_ids(client, liked_tracks):
    known = set()
    for track in liked_tracks:
        for artist in track.artists or []:
            known.add(artist.id)
    for like in client.users_likes_artists() or []:
        if like.artist:
            known.add(like.artist.id)
    for artist in client.users_dislikes_artists() or []:
        known.add(artist.id)
    return known


def _liked_tracks_by_genre(liked_tracks):
    by_genre = {}
    for track in liked_tracks:
        genre = _track_genre(track)
        if genre:
            by_genre.setdefault(genre, []).append(track)
    return by_genre


_SIMILAR_SEED_LIMIT = 5


def _candidate_tracks_for_genre(client, genre_id, seed_tracks):
    """Кандидаты для жанра из двух источников: сперва похожие на уже
    лайкнутые треки этого жанра (точный сигнал от рекомендательной системы
    Яндекса), затем жанровая радиостанция с упором на новое как добор."""
    candidates = []

    for seed in seed_tracks[:_SIMILAR_SEED_LIMIT]:
        try:
            similar = client.tracks_similar(seed.id)
        except Exception:  # noqa: BLE001 - трек без похожих, пропускаем
            continue
        if similar and similar.similar_tracks:
            candidates.extend(similar.similar_tracks)

    station = f"genre:{genre_id}"
    try:
        client.rotor_station_settings2(
            station=station, mood_energy="all", diversity="discover", language="any"
        )
    except Exception:  # noqa: BLE001 - настройки необязательны
        pass
    try:
        result = client.rotor_station_tracks(station=station)
    except Exception:  # noqa: BLE001 - станция недоступна, обходимся похожими
        result = None
    if result and result.sequence:
        candidates.extend(seq.track for seq in result.sequence if seq.track)

    return candidates


def analyze_taste(token: str) -> str:
    """Определяет любимые жанры пользователя и число известных исполнителей."""
    try:
        client = Client(token).init()
        liked = _get_liked_full_tracks(client)

        genre_counts = Counter()
        for track in liked:
            genre = _track_genre(track)
            if genre:
                genre_counts[genre] += 1

        genre_titles = {g.id: g.title for g in client.genres()}
        genres = [
            {"id": genre_id, "title": genre_titles.get(genre_id, genre_id), "count": count}
            for genre_id, count in genre_counts.most_common(12)
        ]

        known = _known_artist_ids(client, liked)

        return json.dumps(
            {
                "ok": True,
                "genres": genres,
                "likedTrackCount": len(liked),
                "knownArtistCount": len(known),
            }
        )
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"ok": False, "error": str(exc)})


def pick_tracks(token: str, genre_ids_json: str, per_genre: int, max_per_artist: int) -> str:
    """Подбирает новые треки в заданных жанрах, избегая уже известных исполнителей.

    Кандидаты берутся в первую очередь через client.tracks_similar() от уже
    лайкнутых треков этого жанра — это персональный сигнал "похоже на то,
    что тебе уже нравится" от самого Яндекса, а не общий жанровый список.
    Жанровая радиостанция (с настройкой diversity=discover) идёт вторым
    источником и просто добирает треки, если похожих не хватило.
    """
    try:
        client = Client(token).init()
        liked = _get_liked_full_tracks(client)
        seen_track_ids = {t.id for t in liked}
        known_artist_ids = _known_artist_ids(client, liked)
        liked_by_genre = _liked_tracks_by_genre(liked)

        genre_ids = json.loads(genre_ids_json)
        picked = []
        artist_use_count = Counter()
        per_genre_found = {}

        for genre_id in genre_ids:
            found = 0
            candidates = _candidate_tracks_for_genre(client, genre_id, liked_by_genre.get(genre_id, []))

            for track in candidates:
                if not track or track.id in seen_track_ids or not track.albums:
                    continue
                if _is_variant_track(track):
                    continue

                artist_ids = {a.id for a in (track.artists or [])}
                if artist_ids & known_artist_ids:
                    continue
                if any(artist_use_count[a] >= max_per_artist for a in artist_ids):
                    continue

                picked.append(track)
                seen_track_ids.add(track.id)
                for a in artist_ids:
                    artist_use_count[a] += 1

                found += 1
                if found >= per_genre:
                    break

            per_genre_found[genre_id] = found

        tracks = [_track_to_dict(track) for track in picked]

        return json.dumps({"ok": True, "tracks": tracks, "perGenreFound": per_genre_found})
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"ok": False, "error": str(exc)})


def pick_cheerful_tracks(token: str, count: int, max_per_artist: int) -> str:
    """Весёлая музыка с личной волны пользователя (mood_energy=fun).

    В отличие от pick_tracks, здесь НЕ исключаются уже известные
    исполнители — для настроения важнее узнаваемые бодрые любимые треки,
    а не новизна. Ремиксы/кавера/лайвы всё равно отфильтровываются, и
    дубли уже лайкнутых треков не повторяются.
    """
    try:
        client = Client(token).init()
        liked = _get_liked_full_tracks(client)
        seen_track_ids = {t.id for t in liked}

        station = "user:onyourwave"
        try:
            client.rotor_station_settings2(
                station=station, mood_energy="fun", diversity="popular", language="any"
            )
        except Exception:  # noqa: BLE001 - настройки необязательны
            pass

        try:
            result = client.rotor_station_tracks(station=station)
        except Exception as exc:  # noqa: BLE001
            return json.dumps({"ok": False, "error": str(exc)})

        picked = []
        artist_use_count = Counter()
        for seq in result.sequence if result else []:
            track = seq.track
            if not track or track.id in seen_track_ids or not track.albums:
                continue
            if _is_variant_track(track):
                continue

            artist_ids = {a.id for a in (track.artists or [])}
            if any(artist_use_count[a] >= max_per_artist for a in artist_ids):
                continue

            picked.append(track)
            seen_track_ids.add(track.id)
            for a in artist_ids:
                artist_use_count[a] += 1

            if len(picked) >= count:
                break

        tracks = [_track_to_dict(track) for track in picked]

        return json.dumps({"ok": True, "tracks": tracks})
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"ok": False, "error": str(exc)})


def create_playlist(token: str, title: str, tracks_json: str) -> str:
    """Создаёт приватный плейлист и наполняет его переданными треками."""
    try:
        client = Client(token).init()
        tracks = json.loads(tracks_json)

        playlist = client.users_playlists_create(title, visibility="private")
        revision = playlist.revision or 1

        inserted = 0
        for track in tracks:
            album_id = track.get("albumId")
            if not album_id:
                continue
            updated = client.users_playlists_insert_track(
                playlist.kind, track["id"], album_id, at=0, revision=revision
            )
            if updated:
                playlist = updated
                revision = playlist.revision or (revision + 1)
                inserted += 1

        status = client.account_status()
        login = status.account.login if status and status.account else None
        url = (
            f"https://music.yandex.ru/users/{login}/playlists/{playlist.kind}"
            if login
            else ""
        )

        return json.dumps({"ok": True, "url": url, "kind": playlist.kind, "trackCount": inserted})
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"ok": False, "error": str(exc)})
