"""
Подбирает треки в твоих любимых жанрах, но от исполнителей, которых ты ещё
не лайкал(а) и не слушал(а) — чтобы не залипать на одних и тех же именах.

Логика:
  1. Берёт лайкнутые треки и артистов -> определяет любимые жанры
     и "уже известных" исполнителей (их избегаем).
  2. Для каждого жанра сперва ищет треки, похожие на уже лайкнутые в этом
     жанре (client.tracks_similar — персональный сигнал похожести от самого
     Яндекса), и добирает недостающее через жанровую радиостанцию
     (genre:<id>, с настройкой diversity=discover) как второй источник.
  3. Отбрасывает треки уже известных/дизлайкнутых исполнителей и дубликаты,
     ограничивает число треков на одного нового исполнителя (для разнообразия).
  4. Печатает результат и (если не --dry-run) создаёт приватный плейлист
     в Яндекс.Музыке с этой подборкой.

Запуск:
  python music_curator.py                       # авто-жанры, создать плейлист
  python music_curator.py --dry-run              # только показать список
  python music_curator.py --genres rock indie    # свои жанры (id из client.genres())
  python music_curator.py --list-genres          # показать доступные id жанров
  python music_curator.py --cheer-up             # весёлый плейлист "Маргарите, чтоб не грустила"
"""
from __future__ import annotations

import argparse
import os
import sys
from collections import Counter
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

from yandex_music import Client

TOKEN_FILE = Path(__file__).parent / "token.txt"
CHEERFUL_PLAYLIST_NAME = "Маргарите, чтоб не грустила"
CHEERFUL_TRACK_COUNT = 20
CHEERFUL_MAX_PER_ARTIST = 3


def load_token() -> str:
    token = os.environ.get("YANDEX_MUSIC_TOKEN")
    if token:
        return token
    if TOKEN_FILE.exists():
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if token:
            return token
    sys.exit(
        "Не найден токен Яндекс.Музыки.\n"
        "Сначала запусти: python get_token.py\n"
        "или задай переменную окружения YANDEX_MUSIC_TOKEN."
    )


def chunked(seq, size):
    for i in range(0, len(seq), size):
        yield seq[i : i + size]


def get_liked_full_tracks(client: Client):
    likes = client.users_likes_tracks()
    if not likes or not likes.tracks:
        return []
    track_ids = [t.track_id for t in likes.tracks]
    full = []
    for chunk in chunked(track_ids, 100):
        full.extend(client.tracks(chunk))
    return full


def track_genre(track) -> str | None:
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


def is_variant_track(track) -> bool:
    """True для ремиксов/каверов/лайвов и похожих "не оригинальных" версий."""
    haystack = f"{getattr(track, 'version', '') or ''} {track.title or ''}".lower()
    return any(keyword in haystack for keyword in _VARIANT_KEYWORDS)


def analyze_taste(client: Client, liked_full_tracks: list):
    genre_counts: Counter[str] = Counter()
    known_artist_ids: set[int] = set()

    for track in liked_full_tracks:
        genre = track_genre(track)
        if genre:
            genre_counts[genre] += 1
        for artist in track.artists or []:
            known_artist_ids.add(artist.id)

    for like in client.users_likes_artists() or []:
        if like.artist:
            known_artist_ids.add(like.artist.id)

    for artist in client.users_dislikes_artists() or []:
        known_artist_ids.add(artist.id)

    return genre_counts, known_artist_ids


def liked_tracks_by_genre(liked_full_tracks: list) -> dict[str, list]:
    by_genre: dict[str, list] = {}
    for track in liked_full_tracks:
        genre = track_genre(track)
        if genre:
            by_genre.setdefault(genre, []).append(track)
    return by_genre


SIMILAR_SEED_LIMIT = 5


def candidate_tracks_for_genre(client: Client, genre_id: str, seed_tracks: list) -> list:
    """Кандидаты для жанра из двух источников: сперва похожие на уже
    лайкнутые треки этого жанра (точный сигнал от рекомендательной системы
    Яндекса), затем жанровая радиостанция с упором на новое как добор."""
    candidates = []

    for seed in seed_tracks[:SIMILAR_SEED_LIMIT]:
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
    except Exception as exc:  # noqa: BLE001 - обходимся похожими треками
        print(f"  [{genre_id}] станция недоступна: {exc}")
        result = None
    if result and result.sequence:
        candidates.extend(seq.track for seq in result.sequence if seq.track)

    return candidates


def pick_new_tracks(
    client: Client,
    genre_ids: list[str],
    liked_by_genre: dict[str, list],
    known_artist_ids: set[int],
    already_seen_track_ids: set[int],
    per_genre: int,
    max_per_artist: int,
):
    picked = []
    seen_track_ids = set(already_seen_track_ids)
    artist_use_count: Counter[int] = Counter()

    for genre_id in genre_ids:
        candidates = candidate_tracks_for_genre(client, genre_id, liked_by_genre.get(genre_id, []))

        found = 0
        for track in candidates:
            if not track or track.id in seen_track_ids:
                continue
            if is_variant_track(track):
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

        print(f"  [{genre_id}] новых треков найдено: {found}")

    return picked


def pick_cheerful_tracks(
    client: Client, already_seen_track_ids: set[int], count: int, max_per_artist: int
) -> list:
    """Весёлая музыка с личной волны пользователя (mood_energy=fun).

    В отличие от pick_new_tracks, здесь НЕ исключаются уже известные
    исполнители — для настроения важнее узнаваемые бодрые любимые треки,
    а не новизна. Ремиксы/кавера/лайвы всё равно отфильтровываются, и
    дубли уже лайкнутых треков не повторяются.
    """
    seen_track_ids = set(already_seen_track_ids)

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
        print(f"Волна недоступна: {exc}")
        return []

    picked = []
    artist_use_count: Counter[int] = Counter()
    for seq in result.sequence if result else []:
        track = seq.track
        if not track or track.id in seen_track_ids or not track.albums:
            continue
        if is_variant_track(track):
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

    return picked


def create_playlist(client: Client, title: str, tracks: list) -> str:
    playlist = client.users_playlists_create(title, visibility="private")
    revision = playlist.revision or 1
    for track in tracks:
        if not track.albums:
            continue
        album_id = track.albums[0].id
        updated = client.users_playlists_insert_track(
            playlist.kind, track.id, album_id, at=0, revision=revision
        )
        if updated:
            playlist = updated
            revision = playlist.revision or (revision + 1)

    status = client.account_status()
    login = status.account.login if status and status.account else None
    if login:
        return f"https://music.yandex.ru/users/{login}/playlists/{playlist.kind}"
    return f"плейлист kind={playlist.kind} (в приложении Яндекс.Музыка -> Мои плейлисты)"


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--genres", nargs="+", default=None, help="id жанров вручную (см. --list-genres)")
    parser.add_argument("--top-genres", type=int, default=3, help="сколько любимых жанров брать автоматически")
    parser.add_argument("--per-genre", type=int, default=15, help="сколько новых треков брать с каждого жанра")
    parser.add_argument("--max-per-artist", type=int, default=2, help="максимум треков одного нового исполнителя")
    parser.add_argument("--playlist-name", default="Новые исполнители", help="название плейлиста")
    parser.add_argument("--dry-run", action="store_true", help="только показать список, не создавать плейлист")
    parser.add_argument("--list-genres", action="store_true", help="показать доступные id жанров и выйти")
    parser.add_argument(
        "--cheer-up",
        action="store_true",
        help='весёлый плейлист "Маргарите, чтоб не грустила" вместо обычного подбора по жанрам',
    )
    return parser.parse_args()


def main():
    args = parse_args()
    token = load_token()
    client = Client(token).init()

    if args.list_genres:
        for genre in client.genres():
            print(f"{genre.id:20s} {genre.title}")
        return

    print("Читаю лайки...")
    liked_full = get_liked_full_tracks(client)
    liked_track_ids = {t.id for t in liked_full}

    if args.cheer_up:
        print("Ищу весёлую музыку для Маргариты...")
        picked = pick_cheerful_tracks(client, liked_track_ids, CHEERFUL_TRACK_COUNT, CHEERFUL_MAX_PER_ARTIST)
        if not picked:
            sys.exit("Не нашлось подходящих треков для весёлого плейлиста.")

        print(f"\nВсего отобрано {len(picked)} треков:")
        for track in picked:
            artists = ", ".join(a.name for a in track.artists or [])
            print(f"  - {artists} — {track.title}")

        if args.dry_run:
            print("\n(--dry-run: плейлист не создавался)")
            return

        title = args.playlist_name if args.playlist_name != "Новые исполнители" else CHEERFUL_PLAYLIST_NAME
        print(f"\nСоздаю плейлист «{title}»...")
        url = create_playlist(client, title, picked)
        print(f"Готово: {url}")
        return

    genre_counts, known_artist_ids = analyze_taste(client, liked_full)

    if args.genres:
        target_genres = args.genres
    else:
        target_genres = [g for g, _ in genre_counts.most_common(args.top_genres)]

    if not target_genres:
        sys.exit(
            "Не удалось определить любимые жанры по лайкам (их мало или нет).\n"
            "Укажи жанры вручную: python music_curator.py --genres rock indie\n"
            "Список id: python music_curator.py --list-genres"
        )

    print(f"Любимые жанры: {', '.join(target_genres)}")
    print(f"Известных исполнителей (буду избегать): {len(known_artist_ids)}\n")

    picked = pick_new_tracks(
        client,
        target_genres,
        liked_tracks_by_genre(liked_full),
        known_artist_ids,
        liked_track_ids,
        args.per_genre,
        args.max_per_artist,
    )

    if not picked:
        sys.exit(
            "\nНе нашлось подходящих новых треков. "
            "Попробуй увеличить --per-genre или взять другие --genres."
        )

    print(f"\nВсего отобрано {len(picked)} треков:")
    for track in picked:
        artists = ", ".join(a.name for a in track.artists or [])
        print(f"  - {artists} — {track.title}")

    if args.dry_run:
        print("\n(--dry-run: плейлист не создавался)")
        return

    print(f"\nСоздаю плейлист «{args.playlist_name}»...")
    url = create_playlist(client, args.playlist_name, picked)
    print(f"Готово: {url}")


if __name__ == "__main__":
    main()
