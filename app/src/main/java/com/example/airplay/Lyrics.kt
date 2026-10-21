package com.example.airplay

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val timeSec: Double, val text: String)

/** [synced] lines carry real timestamps; otherwise they are spread evenly over the track. */
data class Lyrics(val synced: Boolean, val lines: List<LyricLine>)

/**
 * Looks up lyrics on LRCLIB (https://lrclib.net), a free open lyrics database.
 * AirPlay itself never sends lyrics, only title/artist/album.
 */
object LyricsClient {
    private const val TAG = "LyricsClient"
    private const val BASE_URL = "https://lrclib.net/api"
    private const val MAX_ATTEMPTS = 3
    private const val USER_AGENT = "AirPlayReceiverTV/1.0 (Android TV)"
    private val TITLE_EXTRAS = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val LRC_LINE = Regex("""^\[(\d+):(\d+(?:\.\d+)?)]\s*(.*)$""")

    // Track key -> result; a null value records "not found" so we don't ask again.
    private val cache = HashMap<String, Lyrics?>()

    /** [force] skips the cache, for an explicit retry after "not found". */
    suspend fun fetch(artist: String, title: String, durationSec: Double, force: Boolean = false): Lyrics? {
        val key = "${artist.lowercase()}|${title.lowercase()}"
        synchronized(cache) {
            if (!force && cache.containsKey(key)) return cache[key]
        }
        val result = withContext(Dispatchers.IO) {
            // Network hiccups and server errors are retried; a clean "not found" is not.
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    return@withContext lookup(artist, title, durationSec)
                } catch (e: Exception) {
                    Log.w(TAG, "Lyrics lookup attempt $attempt/$MAX_ATTEMPTS failed for $artist - $title: $e")
                    if (attempt < MAX_ATTEMPTS) delay(attempt * 1000L)
                }
            }
            null
        }
        synchronized(cache) { cache[key] = result }
        Log.i(TAG, "Lyrics for $artist - $title: " +
                (result?.let { "${it.lines.size} lines, synced=${it.synced}" } ?: "not found"))
        return result
    }

    private fun lookup(artist: String, title: String, durationSec: Double): Lyrics? {
        var query = "artist_name=${encode(artist)}&track_name=${encode(title)}"
        if (durationSec > 0) query += "&duration=${durationSec.toInt()}"
        httpGet("$BASE_URL/get?$query")?.let { body ->
            toLyrics(JSONObject(body))?.let { return it }
        }
        // Exact match failed (different album/duration): take the best search hit, preferring synced text.
        searchBest("artist_name=${encode(artist)}&track_name=${encode(title)}")?.let { return it }
        // Collaborations come as "A и B" / "A, B" / "A & B"; LRCLIB often lists only the main artist.
        val mainArtist = artist.split(" и ", ",", " & ", " feat", " ft.", " x ").first().trim()
        // Streaming titles carry extras like "(prod X)", "(feat. Y)", "[Remix]" that LRCLIB entries lack.
        val cleanTitle = title.replace(TITLE_EXTRAS, "").replace(Regex("""\s+"""), " ").trim()
        val attempts = linkedSetOf(
            mainArtist to title,
            artist to cleanTitle,
            mainArtist to cleanTitle
        ).filter { (a, t) -> a.isNotEmpty() && t.isNotEmpty() && (a != artist || t != title) }
        for ((a, t) in attempts) {
            searchBest("artist_name=${encode(a)}&track_name=${encode(t)}")?.let { return it }
        }
        return null
    }

    private fun searchBest(query: String): Lyrics? {
        val hits = httpGet("$BASE_URL/search?$query")?.let { JSONArray(it) } ?: return null
        var plain: Lyrics? = null
        for (i in 0 until hits.length()) {
            val lyrics = toLyrics(hits.getJSONObject(i)) ?: continue
            if (lyrics.synced) return lyrics
            if (plain == null) plain = lyrics
        }
        return plain
    }

    private fun toLyrics(json: JSONObject): Lyrics? {
        val synced = json.optString("syncedLyrics", "")
        if (synced.isNotBlank()) {
            val lines = synced.lines().mapNotNull { line ->
                val match = LRC_LINE.find(line.trim()) ?: return@mapNotNull null
                val (min, sec, text) = match.destructured
                LyricLine(min.toInt() * 60 + sec.toDouble(), text.ifBlank { "♪" })
            }
            if (lines.isNotEmpty()) return Lyrics(synced = true, lines = lines)
        }
        val plain = json.optString("plainLyrics", "")
        if (plain.isNotBlank()) {
            val lines = plain.lines().filter { it.isNotBlank() }.map { LyricLine(0.0, it) }
            if (lines.isNotEmpty()) return Lyrics(synced = false, lines = lines)
        }
        return null
    }

    private fun httpGet(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                // Rate limits / server errors must not be cached as "no lyrics".
                else -> throw java.io.IOException("HTTP $code for $url")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/** Index of the line being sung at [positionSec]. */
fun currentLyricIndex(lyrics: Lyrics, positionSec: Double, durationSec: Double): Int {
    if (lyrics.synced) {
        val index = lyrics.lines.indexOfLast { it.timeSec <= positionSec }
        return index.coerceAtLeast(0)
    }
    if (durationSec <= 0) return 0
    return ((positionSec / durationSec) * lyrics.lines.size).toInt().coerceIn(0, lyrics.lines.lastIndex)
}

enum class LyricsStatus { NONE, LOADING, FOUND, NOT_FOUND }

/** Three dots pulsing in turn, for "searching" states. */
@Composable
fun LoadingDots(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "dots-phase"
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 3) {
            val active = phase.toInt() == i
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (active) 1f else 0.3f))
            )
        }
    }
}

/** Lines switch this much ahead of the reported position; reading lags behind hearing otherwise. */
const val LYRICS_LEAD_SEC = 0.5

private const val LINES_BEFORE = 2

/**
 * Karaoke-style list: scrolls smoothly so the current line sits near the top,
 * the current line grows and brightens while the others shrink and fade.
 */
@Composable
fun LyricsPanel(lyrics: Lyrics?, positionSec: Double, durationSec: Double, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val current = lyrics?.let { currentLyricIndex(it, positionSec + LYRICS_LEAD_SEC, durationSec) } ?: 0
    LaunchedEffect(lyrics, current) {
        if (lyrics != null) {
            listState.animateScrollToItem((current - LINES_BEFORE).coerceAtLeast(0))
        }
    }
    val panelAlpha by animateFloatAsState(
        targetValue = if (lyrics != null) 1f else 0f,
        animationSpec = tween(500),
        label = "lyrics-panel"
    )
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxHeight().alpha(panelAlpha)
    ) {
        val lines = lyrics?.lines.orEmpty()
        itemsIndexed(lines) { index, line ->
            LyricText(text = line.text, distance = index - current)
        }
        // Room below the last line so it can still scroll up to the reading position.
        item { Spacer(modifier = Modifier.height(240.dp)) }
    }
}

@Composable
private fun LyricText(text: String, distance: Int) {
    val isCurrent = distance == 0
    val color by animateColorAsState(
        targetValue = if (isCurrent) Color.White else Color(0xFF8A96A6),
        animationSpec = tween(400),
        label = "lyric-color"
    )
    val alpha by animateFloatAsState(
        targetValue = when {
            isCurrent -> 1f
            distance < 0 -> 0.35f
            else -> (1f - distance * 0.15f).coerceAtLeast(0.3f)
        },
        animationSpec = tween(400),
        label = "lyric-alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0.78f,
        animationSpec = tween(400),
        label = "lyric-scale"
    )
    Text(
        text = text,
        color = color,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                // Scale from the left edge so lines stay left-aligned while resizing.
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
                this.alpha = alpha
            }
    )
}
