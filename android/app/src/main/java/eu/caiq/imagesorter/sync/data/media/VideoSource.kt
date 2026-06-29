package eu.caiq.imagesorter.sync.data.media

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * Authed media-item + HTTP `DataSource.Factory` builders for ExoPlayer video
 * streaming. Kept free of an [androidx.media3.exoplayer.ExoPlayer] instance so
 * the URL + bearer-header wiring is unit-testable without real playback.
 */

/** A Media3 [MediaItem] pointing at `/api/media/{id}/stream`. */
fun buildVideoMediaItem(urls: MediaUrls, id: Long): MediaItem =
    MediaItem.fromUri(urls.stream(id))

/** The request-header map (bearer token) for a video stream, empty when untrusted. */
fun videoRequestHeaders(token: String?): Map<String, String> = MediaUrls.authHeaders(token)

/**
 * An HTTP [DataSource.Factory] that sends the bearer [token] on every stream
 * request, so ExoPlayer's progressive reads (including Range requests) carry it.
 */
fun bearerDataSourceFactory(token: String?): DataSource.Factory =
    DefaultHttpDataSource.Factory().apply {
        val headers = videoRequestHeaders(token)
        if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
    }
