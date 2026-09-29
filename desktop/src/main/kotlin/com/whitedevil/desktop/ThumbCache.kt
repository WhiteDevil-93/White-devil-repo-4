package com.whitedevil.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Least-recently-used cache bounded by BOTH entry count and estimated bytes.
 *
 * An unbounded map of decoded bitmaps exhausts the heap on a large library (a
 * 480 px thumbnail is ~0.5 MB decoded, ten thousand of them is 5 GB), so every
 * decoded image goes through this. Thread-safe: the grid loads from several
 * coroutines at once.
 *
 * - [get] marks the entry most recently used.
 * - [put] evicts least-recently-used entries until both bounds hold again.
 * - A single value bigger than [maxBytes] is not stored at all (and any older
 *   value under that key is removed, so a stale image is never served).
 */
class LruCache<K : Any, V : Any>(
    val maxEntries: Int,
    val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    // accessOrder = true: iteration runs from least to most recently used.
    private val map = LinkedHashMap<K, Pair<V, Long>>(16, 0.75f, true)
    private var totalBytes = 0L
    private var evicted = 0L

    @Synchronized
    fun get(key: K): V? = map[key]?.first

    /** @return false when the value was too large to keep. */
    @Synchronized
    fun put(key: K, value: V): Boolean {
        map.remove(key)?.let { totalBytes -= it.second }
        val size = sizeOf(value).coerceAtLeast(0L)
        if (size > maxBytes) return false
        map[key] = value to size
        totalBytes += size
        val it = map.entries.iterator()
        while ((map.size > maxEntries || totalBytes > maxBytes) && it.hasNext()) {
            val eldest = it.next()
            totalBytes -= eldest.value.second
            it.remove()
            evicted++
        }
        return true
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)?.let { totalBytes -= it.second }
    }

    @Synchronized
    fun clear() {
        map.clear()
        totalBytes = 0
    }

    val size: Int @Synchronized get() = map.size
    val bytes: Long @Synchronized get() = totalBytes
    val evictions: Long @Synchronized get() = evicted

    /** Keys from least to most recently used. */
    @Synchronized
    fun keysOldestFirst(): List<K> = map.keys.toList()
}

sealed interface ThumbOutcome<out V> {
    data class Ready<out V>(val value: V) : ThumbOutcome<V>
    data class Failed(val error: MediaError) : ThumbOutcome<Nothing>
}

/**
 * Fetches, decodes and caches images for the gallery.
 *
 * - At most [permits] requests run at once; the rest wait in FIFO order.
 * - The caller's coroutine is the request: cancel it (the tile scrolled out of
 *   view) and the HTTP call is cancelled and the permit released. A cancelled
 *   load is not a failure and is never remembered as one.
 * - Real failures are remembered per key so a tile that scrolls out and back in
 *   does not silently hammer the hub again; they clear only on an explicit
 *   [retry] / [clearFailures] (the user pressing Retry or Refresh).
 * - Decoding runs on [decodeContext] (never the UI thread). A decode error is a
 *   failure like any other, so a corrupt image shows an error tile, not a blank one.
 */
class ThumbLoader<V : Any>(
    private val cache: LruCache<String, V>,
    private val fetch: suspend (name: String) -> MediaResult<ByteArray>,
    private val decode: (ByteArray) -> V,
    permits: Int = 4,
    private val decodeContext: CoroutineContext = Dispatchers.Default,
) {
    private val gate = Semaphore(permits)
    private val failures = LinkedHashMap<String, MediaError>()

    /** A ready value already in memory, if any. Cheap; safe to call from composition. */
    fun cached(key: String): V? = cache.get(key)

    /** A failure already recorded for [key], if any. */
    fun failure(key: String): MediaError? = synchronized(failures) { failures[key] }

    fun retry(key: String) {
        synchronized(failures) { failures.remove(key) }
    }

    fun clearFailures() {
        synchronized(failures) { failures.clear() }
    }

    suspend fun load(key: String, name: String): ThumbOutcome<V> {
        cache.get(key)?.let { return ThumbOutcome.Ready(it) }
        failure(key)?.let { return ThumbOutcome.Failed(it) }

        return gate.withPermit {
            // Another tile (e.g. the preview pane) may have finished this key while we queued.
            cache.get(key)?.let { return@withPermit ThumbOutcome.Ready(it) }

            val fetched = try {
                fetch(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // MediaClient never throws, but an exception escaping into a composition coroutine
                // would crash the window, so it becomes a visible failure like any other.
                return@withPermit record(
                    key,
                    MediaError(
                        MediaErrorKind.Network,
                        "Unexpected error while fetching the image.",
                        null,
                        e.message?.let { MediaParser.snippet(it, 160) } ?: e.javaClass.simpleName,
                    ),
                )
            }
            when (val r = fetched) {
                is MediaResult.Failure -> record(key, r.error)
                is MediaResult.Ok -> {
                    val bytes = r.value
                    val decoded = try {
                        withContext(decodeContext) { decode(bytes) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@withPermit record(
                            key,
                            MediaError(
                                MediaErrorKind.BadResponse,
                                "The hub sent ${bytes.size} bytes that could not be decoded as an image.",
                                null,
                                e.message?.let { MediaParser.snippet(it, 160) } ?: e.javaClass.simpleName,
                            ),
                        )
                    }
                    cache.put(key, decoded)
                    ThumbOutcome.Ready(decoded)
                }
            }
        }
    }

    private fun record(key: String, error: MediaError): ThumbOutcome<V> {
        synchronized(failures) {
            failures[key] = error
            // Bound the memo too: a library of many broken clips must not grow it forever.
            while (failures.size > MAX_REMEMBERED_FAILURES) {
                val it = failures.entries.iterator()
                it.next()
                it.remove()
            }
        }
        return ThumbOutcome.Failed(error)
    }

    private companion object {
        const val MAX_REMEMBERED_FAILURES = 2_000
    }
}

/**
 * Process-wide caches, so leaving the Gallery tab and coming back does not
 * re-download every thumbnail. Bounded: about 64 MB of thumbnails and 4 contact
 * sheets. Keys include the hub URL and the clip's mtime (see [mediaCacheKey]).
 */
object MediaCaches {
    val thumbs = LruCache<String, ImageBitmap>(maxEntries = 400, maxBytes = 64L * 1024 * 1024, sizeOf = ::bitmapBytes)
    val contactSheets = LruCache<String, ImageBitmap>(maxEntries = 4, maxBytes = 48L * 1024 * 1024, sizeOf = ::bitmapBytes)

    private fun bitmapBytes(b: ImageBitmap): Long = b.width.toLong() * b.height.toLong() * 4L
}

/**
 * Decodes JPEG/PNG bytes with Skia. Throws on data Skia cannot decode. Call off the UI thread.
 * The intermediate Skia image is closed at once: the bitmap owns its pixels, and waiting for
 * the garbage collector to release native memory is how a big library balloons the process.
 */
fun decodeToBitmap(bytes: ByteArray): ImageBitmap {
    val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    try {
        return image.toComposeImageBitmap()
    } finally {
        image.close()
    }
}

/**
 * Cache key: hub + clip name + mtime. The hub overwrites a clip's thumbnail when
 * the render is replaced under the same name, and the mtime in the library
 * response is what tells us, so a re-render is never shown as its stale thumbnail.
 */
fun mediaCacheKey(hubUrl: String, clip: MediaClip): String =
    "${hubUrl.trim().trimEnd('/')}\u0000${clip.name}\u0000${clip.mtime ?: "?"}"
