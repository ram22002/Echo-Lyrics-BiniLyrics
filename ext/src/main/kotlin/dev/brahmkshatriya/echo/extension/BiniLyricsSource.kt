package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * LyricsPlus, the service behind BiniLyrics. Three public hosts exist; the first one was
 * unreachable during the audit, so they are tried in order like the reference client does.
 */
internal class BiniLyricsSource : LyricsSource {

    private val throttle = Throttle(350)
    private val headers = mapOf("Accept" to "application/json")

    override suspend fun search(track: Track, settings: Settings?): List<Lyrics> {
        val query = track.query()
        if (query.title.isBlank()) return emptyList()
        val hosts = hosts(settings)
        val term = listOf(track.title, query.artist).filter { it.isNotBlank() }.joinToString(" ")

        for (host in hosts) {
            val body = runCatching {
                throttle.await()
                Http.get("$host/v1/songlist/search?q=" + Http.encode(term), headers)
            }.getOrNull() ?: continue
            val results = runCatching {
                Http.json(body).obj()?.arr("results").objects()
            }.getOrNull() ?: continue
            val mapped = results.mapNotNull { item ->
                val title = item.str("title") ?: return@mapNotNull null
                val artist = item.str("artist").orEmpty()
                val album = item.str("album").orEmpty()
                val duration = (item.long("durationMs") ?: 0L) / 1000
                val id = item.obj("id")?.str("db") ?: item.str("isrc") ?: candidateId(title, artist, duration)
                Triple(id, Candidate(title, artist, album, duration), host)
            }.filter { Match.accept(query, it.second) }
                .sortedByDescending { Match.score(query, it.second) }
                .take(8)

            if (mapped.isNotEmpty()) {
                return mapped.map { (id, candidate, hostUsed) ->
                    Lyrics(
                        id = "binilyrics:$id",
                        title = candidate.title,
                        subtitle = candidate.artist + " • BiniLyrics",
                        lyrics = null,
                        extras = mapOf(
                            "host" to hostUsed,
                            "title" to candidate.title,
                            "artist" to candidate.artist,
                            "album" to candidate.album,
                            "duration" to candidate.durationSec.toString(),
                        ),
                    )
                }
            }
        }
        return emptyList()
    }

    override suspend fun load(lyrics: Lyrics, settings: Settings?): Lyrics {
        val host = lyrics.extras["host"] ?: throw LyricsException("BiniLyrics candidate is missing its host")
        val title = lyrics.extras["title"] ?: throw LyricsException("BiniLyrics candidate is missing its title")
        val artist = lyrics.extras["artist"].orEmpty()
        val album = lyrics.extras["album"].orEmpty()
        val duration = lyrics.extras["duration"]?.toLongOrNull() ?: 0L
        val url = buildString {
            append(host).append("/v1/ttml/get?title=").append(Http.encode(title))
            if (artist.isNotBlank()) append("&artist=").append(Http.encode(artist))
            if (album.isNotBlank()) append("&album=").append(Http.encode(album))
            if (duration > 0) append("&duration=").append(duration)
        }
        // The mirrors intermittently answer 404 for a complete query; retry the loose form once.
        val body = runCatching {
            throttle.await()
            Http.get(url, headers)
        }.getOrElse {
            val loose = buildString {
                append(host).append("/v1/ttml/get?title=").append(Http.encode(title))
                if (artist.isNotBlank()) append("&artist=").append(Http.encode(artist))
            }
            throttle.await()
            Http.get(loose, headers)
        }
        val root = Http.json(body).obj()
            ?: throw LyricsException("BiniLyrics returned an empty response")
        val ttml = root.str("ttml") ?: throw LyricsException("BiniLyrics has no TTML for this song")
        val lines = Ttml.parse(ttml)
        if (lines.isEmpty()) throw LyricsException("BiniLyrics TTML could not be parsed")
        return lyrics.copy(lyrics = timed(lines, wordLevel = lines.any { it.words.size > 1 }))
    }

    private fun hosts(settings: Settings?): List<String> {
        val override = settings?.getString(KEY_ENDPOINT)?.trim().orEmpty()
        return if (override.isNotEmpty()) listOf(override.trimEnd('/')) else HOSTS
    }

    companion object {
        const val KEY_ENDPOINT = "endpoint"
        private val HOSTS = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.prjktla.workers.dev",
        )

        fun settings(): List<Setting> = listOf(
            SettingTextInput(
                title = "API host",
                key = KEY_ENDPOINT,
                summary = "Leave blank to try the three public mirrors in order.",
                defaultValue = "",
            ),
        )
    }
}
