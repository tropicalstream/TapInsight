package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TapRadioTool — Gemini-callable tool for searching, playing, and managing
 * internet radio stations and podcasts via TapRadio.
 *
 * All playback routes through the native TapRadio player (ExoPlayer via
 * radio.html auto-play parameters) — NOT the generic browser media player.
 *
 * Actions:
 *   play    — play a station/podcast by name or direct URL
 *   search  — query Radio Browser API (30k+ stations) + iTunes (podcasts)
 *   podcast — search iTunes for podcasts and play the latest episode
 *   list    — return saved station names
 *   stop    — stop current playback
 *   add     — add a station to saved list
 */
class TapRadioTool(private val context: Context) : AiTapTool {
    override val name = "tapradio"

    private data class PlaybackMetadata(
        val name: String? = null,
        val genre: String? = null,
        val subtitle: String? = null,
        val artist: String? = null,
        val kind: String? = null
    ) {
        fun cleanName(): String? = name?.trim()?.takeIf { it.isNotBlank() }
        fun cleanGenre(): String? = genre?.trim()?.takeIf { it.isNotBlank() }
        fun cleanSubtitle(): String? = subtitle?.trim()?.takeIf { it.isNotBlank() }
        fun cleanArtist(): String? = artist?.trim()?.takeIf { it.isNotBlank() }
        fun cleanKind(): String? = kind?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "TapRadioTool"
        private const val RADIO_PREFS_KEY = "tapradio_stations"
        private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"
        private val RADIO_BROWSER_SERVERS = listOf(
            "https://de1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info",
            "https://at1.api.radio-browser.info"
        )
    }

    private val prefs by lazy {
        context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"]?.trim()?.lowercase() ?: "list"
        val query = args["query"]?.trim() ?: ""
        val metadata = PlaybackMetadata(
            name = args["name"],
            genre = args["genre"],
            subtitle = args["subtitle"],
            artist = args["artist"],
            kind = args["kind"]
        )

        Log.d(TAG, "action=$action query=$query")

        return when (action) {
            "play" -> playStation(query, metadata)
            "search" -> searchStations(query)
            "podcast" -> playPodcast(query)
            "list" -> listStations()
            "stop" -> stopPlayback()
            "add" -> addStation(query, args["name"] ?: "", args["genre"] ?: "")
            else -> Result.success("Unknown TapRadio action: $action. Use play, search, podcast, list, stop, or add.")
        }
    }

    // ── Play ────────────────────────────────────────────────────────

    /**
     * Build the open_taplink URL that routes through TapRadio's native
     * ExoPlayer via radio.html auto-play parameters.
     */
    private fun buildNativePlayUrl(
        streamUrl: String,
        name: String,
        genre: String,
        subtitle: String = "",
        artist: String = "",
        kind: String = ""
    ): String {
        val params = mutableListOf(
            "playUrl=${URLEncoder.encode(streamUrl, "UTF-8")}",
            "playName=${URLEncoder.encode(name, "UTF-8")}",
            "playGenre=${URLEncoder.encode(genre, "UTF-8")}"
        )
        if (subtitle.isNotBlank()) {
            params += "playSubtitle=${URLEncoder.encode(subtitle, "UTF-8")}"
        }
        if (artist.isNotBlank()) {
            params += "playArtist=${URLEncoder.encode(artist, "UTF-8")}"
        }
        if (kind.isNotBlank()) {
            params += "playKind=${URLEncoder.encode(kind, "UTF-8")}"
        }
        return "open_taplink:file:///android_asset/radio.html?${params.joinToString("&")}"
    }

    private suspend fun playStation(query: String, metadata: PlaybackMetadata): Result<String> {
        if (query.isBlank()) return Result.success("Please specify a station name or URL to play.")

        val savedByUrl = getSavedStations().firstOrNull { station ->
            station.optString("url", "").equals(query, ignoreCase = true)
        }

        // Direct URL — route through native player
        if (query.startsWith("http://") || query.startsWith("https://")) {
            clearNowPlaying()
            val stationName = metadata.cleanName()
                ?: savedByUrl?.optString("name", "")?.trim()?.takeIf { it.isNotBlank() }
                ?: "Stream"
            val genre = metadata.cleanGenre()
                ?: savedByUrl?.optString("genre", "")?.trim()?.takeIf { it.isNotBlank() }
                ?: "Mix"
            val playLink = buildNativePlayUrl(
                query,
                stationName,
                genre,
                subtitle = metadata.cleanSubtitle().orEmpty(),
                artist = metadata.cleanArtist().orEmpty(),
                kind = metadata.cleanKind().orEmpty()
            )
            val detail = buildString {
                append("Playing $stationName on TapRadio")
                if (genre.isNotBlank()) append(" ($genre)")
            }
            return Result.success("$playLink\n$detail")
        }

        // Fuzzy match against saved stations
        val stations = getSavedStations()
        val queryLower = query.lowercase()
        val match = stations.firstOrNull { station ->
            val name = station.optString("name", "").lowercase()
            val genre = station.optString("genre", "").lowercase()
            name.contains(queryLower) || queryLower.contains(name) || genre.contains(queryLower)
        }

        if (match != null) {
            val url = match.optString("url", "")
            val stationName = match.optString("name", "Unknown")
            val genre = match.optString("genre", "")
            if (url.isNotBlank()) {
                clearNowPlaying()
                val playLink = buildNativePlayUrl(
                    url,
                    stationName,
                    genre,
                    subtitle = metadata.cleanSubtitle().orEmpty(),
                    artist = metadata.cleanArtist().orEmpty(),
                    kind = metadata.cleanKind().orEmpty()
                )
                return Result.success("$playLink\nPlaying $stationName on TapRadio ($genre)")
            }
        }

        // Not found in saved — try Radio Browser search then play first result
        val searchResults = searchRadioBrowser(query, limit = 5)
        if (searchResults.isNotEmpty()) {
            // Try exact match first, then fuzzy
            val queryLowerRadio = query.lowercase()
            val exactMatch = searchResults.firstOrNull {
                it.optString("name", "").lowercase().contains(queryLowerRadio) ||
                    queryLowerRadio.contains(it.optString("name", "").lowercase())
            }
            val first = exactMatch ?: searchResults[0]
            val url = first.optString("url_resolved", first.optString("url", ""))
            val stationName = first.optString("name", "Unknown")
            val genre = first.optString("tags", "").split(",").firstOrNull()?.trim() ?: "Mix"
            if (url.isNotBlank()) {
                clearNowPlaying()
                val playLink = buildNativePlayUrl(
                    url,
                    stationName,
                    genre,
                    subtitle = metadata.cleanSubtitle().orEmpty(),
                    artist = metadata.cleanArtist().orEmpty(),
                    kind = metadata.cleanKind().orEmpty()
                )
                return Result.success("$playLink\nPlaying $stationName on TapRadio ($genre)")
            }
        }

        // Do NOT fall back to podcast search here — podcasts should only be
        // played via the explicit 'podcast' action. Falling through to iTunes
        // caused radio station selections to incorrectly play podcasts instead.
        return Result.success("No radio station found matching '$query'. Try 'search $query' to discover stations, or say 'podcast $query' to find podcasts.")
    }

    // ── Podcast ─────────────────────────────────────────────────────

    /**
     * Search iTunes for a podcast by name, parse its RSS feed to get
     * the latest episode audio URL, and play via native TapRadio player.
     */
    private suspend fun playPodcast(query: String): Result<String> {
        if (query.isBlank()) return Result.success("Please specify a podcast name to search for.")

        val result = searchAndPlayPodcast(query)
            ?: return Result.success("No podcast found matching '$query'. Try a different name.")
        return Result.success(result)
    }

    private suspend fun searchAndPlayPodcast(query: String): String? = withContext(Dispatchers.IO) {
        // 1) Search iTunes for the podcast
        val podcast = searchItunes(query) ?: return@withContext null
        val feedUrl = podcast.optString("feedUrl", "")
        val podcastName = podcast.optString("collectionName",
            podcast.optString("trackName", "Podcast"))
        val artist = podcast.optString("artistName", "")

        if (feedUrl.isBlank()) {
            return@withContext "Found '$podcastName' but no RSS feed available."
        }

        // 2) Parse RSS feed to get the latest episode audio URL
        val episode = parseRssFeedForLatestEpisode(feedUrl)
        if (episode == null) {
            return@withContext "Found '$podcastName' but could not load the latest episode. Feed: $feedUrl"
        }

        val episodeUrl = episode.first
        val episodeTitle = episode.second

        // 3) Play via native TapRadio player
        val displayName = if (episodeTitle.isNotBlank()) "$podcastName: $episodeTitle" else podcastName
        val playLink = buildNativePlayUrl(
            episodeUrl,
            podcastName,
            "Podcast",
            subtitle = episodeTitle,
            artist = artist,
            kind = "podcast"
        )
        "$playLink\nPlaying podcast: $displayName" + if (artist.isNotBlank()) " by $artist" else ""
    }

    /**
     * Search iTunes Search API for podcasts matching the query.
     * Returns the best match or null.
     */
    private fun searchItunes(query: String): JSONObject? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$ITUNES_SEARCH_URL?term=$encoded&media=podcast&entity=podcast&limit=5"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    results.getJSONObject(0)
                } else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "iTunes search failed: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Parse an RSS/Atom podcast feed and extract the audio URL and title
     * of the most recent episode. Uses simple XML parsing to avoid
     * pulling in a full XML library dependency.
     *
     * Returns Pair(audioUrl, episodeTitle) or null if no enclosure found.
     */
    private fun parseRssFeedForLatestEpisode(feedUrl: String): Pair<String, String>? {
        val conn = URL(feedUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode != 200) return null
            val xml = conn.inputStream.bufferedReader().readText()

            // Find the first <item> block (most recent episode)
            val itemStart = xml.indexOf("<item>").takeIf { it >= 0 }
                ?: xml.indexOf("<item ").takeIf { it >= 0 }
                ?: return null
            val itemEnd = xml.indexOf("</item>", itemStart).takeIf { it >= 0 }
                ?: xml.length
            val item = xml.substring(itemStart, itemEnd)

            // Extract enclosure URL (the actual audio file)
            val audioUrl = extractEnclosureUrl(item) ?: return null

            // Extract episode title
            val title = extractXmlTag(item, "title") ?: ""

            Pair(audioUrl, title)
        } catch (e: Exception) {
            Log.w(TAG, "RSS feed parse failed for $feedUrl: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Extract the url attribute from an <enclosure> tag. */
    private fun extractEnclosureUrl(itemXml: String): String? {
        // Match <enclosure ... url="..." .../>
        val encIdx = itemXml.indexOf("<enclosure").takeIf { it >= 0 } ?: return null
        val encEnd = itemXml.indexOf(">", encIdx).takeIf { it >= 0 } ?: return null
        val encTag = itemXml.substring(encIdx, encEnd + 1)

        // Extract url attribute value
        val urlAttr = Regex("""url\s*=\s*["']([^"']+)["']""").find(encTag)
        return urlAttr?.groupValues?.getOrNull(1)?.trim()
    }

    /** Extract text content from the first occurrence of an XML tag. */
    private fun extractXmlTag(xml: String, tag: String): String? {
        // Handle CDATA: <title><![CDATA[Episode Title]]></title>
        val openTag = "<$tag>"
        val closeTag = "</$tag>"
        val start = xml.indexOf(openTag).takeIf { it >= 0 } ?: return null
        val end = xml.indexOf(closeTag, start).takeIf { it >= 0 } ?: return null
        var content = xml.substring(start + openTag.length, end).trim()
        // Strip CDATA wrapper if present
        if (content.startsWith("<![CDATA[")) {
            content = content.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        }
        return content.ifBlank { null }
    }

    // ── Search ──────────────────────────────────────────────────────

    private suspend fun searchStations(query: String): Result<String> {
        if (query.isBlank()) return Result.success("Please provide a search term (e.g. genre, station name, or country).")

        val sb = StringBuilder()

        // Search Radio Browser for stations
        val radioResults = searchRadioBrowser(query, limit = 5)
        if (radioResults.isNotEmpty()) {
            sb.append("[RADIO STATIONS]:\n")
            for ((i, station) in radioResults.withIndex()) {
                val stationName = station.optString("name", "Unknown").take(40)
                val tags = station.optString("tags", "").take(30)
                val country = station.optString("country", "")
                val streamUrl = station.optString("url_resolved",
                    station.optString("url", ""))
                sb.append("${i + 1}. $stationName")
                if (tags.isNotBlank()) sb.append(" ($tags)")
                if (country.isNotBlank()) sb.append(" — $country")
                if (streamUrl.isNotBlank()) sb.append(" [URL: $streamUrl]")
                sb.append("\n")
            }
        }

        // Also search iTunes for podcasts
        val podcastResults = searchItunesMultiple(query, limit = 3)
        if (podcastResults.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[PODCASTS]:\n")
            for ((i, podcast) in podcastResults.withIndex()) {
                val podName = podcast.optString("collectionName",
                    podcast.optString("trackName", "Unknown")).take(40)
                val artist = podcast.optString("artistName", "").take(25)
                sb.append("${i + 1}. $podName")
                if (artist.isNotBlank()) sb.append(" by $artist")
                sb.append("\n")
            }
        }

        if (sb.isEmpty()) {
            return Result.success("No stations or podcasts found for '$query'. Try a different search term.")
        }

        sb.append("\nTo play a RADIO STATION: call tapradio with action='play', query set to the station's stream URL from the [URL: ...] field above, and include name='[station name]' plus genre='[genre]' when available.")
        sb.append("\nTo play a PODCAST: call tapradio with action='podcast' and query='[podcast name]'.")
        sb.append("\nIMPORTANT: Always use the stream URL (not the station name) when playing a radio station.")
        return Result.success(sb.toString())
    }

    /** Search iTunes for multiple podcast results. */
    private suspend fun searchItunesMultiple(query: String, limit: Int = 3): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$ITUNES_SEARCH_URL?term=$encoded&media=podcast&entity=podcast&limit=$limit"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results != null) {
                        (0 until results.length()).map { results.getJSONObject(it) }
                    } else emptyList()
                } else emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "iTunes multi-search failed: ${e.message}")
                emptyList()
            } finally {
                conn.disconnect()
            }
        }

    // ── List / Stop / Add ───────────────────────────────────────────

    private fun listStations(): Result<String> {
        val stations = getSavedStations()
        if (stations.isEmpty()) {
            return Result.success("No saved radio stations. Add stations in the TapRadio companion app, or say 'search [genre]' to discover new ones.")
        }

        val sb = StringBuilder("Saved stations (${stations.size}):\n")
        for (i in 0 until stations.size) {
            val s = stations[i]
            val stationName = s.optString("name", "Unknown")
            val genre = s.optString("genre", "")
            val fav = s.optBoolean("fav", false)
            sb.append(if (fav) "★ " else "• ")
            sb.append(stationName)
            if (genre.isNotBlank()) sb.append(" ($genre)")
            sb.append("\n")
        }
        sb.append("\nSay 'play [station name]' to start listening.")
        return Result.success(sb.toString())
    }

    private fun stopPlayback(): Result<String> {
        clearNowPlaying()
        return Result.success("TapRadio stopped.")
    }

    private fun addStation(url: String, stationName: String, genre: String): Result<String> {
        if (url.isBlank()) return Result.success("Please provide a stream URL to add.")
        val stations = getSavedStationsMutable()
        val newStation = JSONObject()
            .put("name", stationName.ifBlank { "New Station" })
            .put("url", url)
            .put("genre", genre.ifBlank { "Other" })
            .put("desc", "")
            .put("fav", false)
        stations.put(newStation)
        prefs.edit().putString(RADIO_PREFS_KEY, stations.toString()).apply()
        return Result.success("Added '${stationName.ifBlank { url }}' to TapRadio.")
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun setNowPlaying(stationName: String, genre: String) {
        prefs.edit()
            .putBoolean("tapradio_now_playing_active", true)
            .putString("tapradio_now_playing_name", stationName)
            .putString("tapradio_now_playing_genre", genre)
            .apply()
    }

    /** Clear radio HUD state — used when playback is delegated to browser. */
    private fun clearNowPlaying() {
        prefs.edit()
            .putBoolean("tapradio_now_playing_active", false)
            .remove("tapradio_now_playing_name")
            .remove("tapradio_now_playing_genre")
            .apply()
    }

    private fun getSavedStations(): List<JSONObject> {
        val raw = prefs.getString(RADIO_PREFS_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved stations: ${e.message}")
            emptyList()
        }
    }

    private fun getSavedStationsMutable(): JSONArray {
        val raw = prefs.getString(RADIO_PREFS_KEY, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    /**
     * Search Radio Browser using multiple strategies:
     *  1. Advanced search (name + tag combined) — broadest match
     *  2. By-name fallback — exact name substring
     *  3. By-tag fallback — matches genre/tag keywords
     * Deduplicates by station name and returns the union.
     */
    private suspend fun searchRadioBrowser(query: String, limit: Int = 5): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val seen = mutableSetOf<String>()
            val results = mutableListOf<JSONObject>()

            for (server in RADIO_BROWSER_SERVERS) {
                try {
                    // 1) Advanced search — matches name OR tag, ordered by votes
                    val advUrl = "$server/json/stations/search?name=$encoded&tag=$encoded" +
                        "&limit=$limit&order=votes&reverse=true"
                    val advResults = fetchStations(advUrl)
                    for (s in advResults) {
                        val key = s.optString("name", "").lowercase()
                        if (key.isNotBlank() && seen.add(key)) results.add(s)
                    }

                    // 2) By-name — in case advanced search missed substring matches
                    if (results.size < limit) {
                        val nameUrl = "$server/json/stations/byname/$encoded" +
                            "?limit=$limit&order=votes&reverse=true"
                        for (s in fetchStations(nameUrl)) {
                            val key = s.optString("name", "").lowercase()
                            if (key.isNotBlank() && seen.add(key)) results.add(s)
                        }
                    }

                    // 3) By-tag — catches genre searches like "jazz", "news", "comedy"
                    if (results.size < limit) {
                        val tagUrl = "$server/json/stations/bytag/$encoded" +
                            "?limit=$limit&order=votes&reverse=true"
                        for (s in fetchStations(tagUrl)) {
                            val key = s.optString("name", "").lowercase()
                            if (key.isNotBlank() && seen.add(key)) results.add(s)
                        }
                    }

                    if (results.isNotEmpty()) {
                        return@withContext results.take(limit)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Radio Browser search failed on $server: ${e.message}")
                }
            }
            emptyList()
        }

    /** Fetch station list from a single Radio Browser URL. */
    private fun fetchStations(urlStr: String): List<JSONObject> {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(body)
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } else emptyList()
        } finally {
            conn.disconnect()
        }
    }
}
