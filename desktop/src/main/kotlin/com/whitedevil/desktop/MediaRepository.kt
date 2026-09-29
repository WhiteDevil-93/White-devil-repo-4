package com.whitedevil.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// ---- wire types for hub/media.py ---------------------------------------------------

/** One finished render. `mtime` is epoch seconds; `idx` is the clip's number within a pack or chain, when it has one. */
@Serializable
data class MediaClip(
    val name: String,
    val idx: Int? = null,
    val mtime: Double = 0.0,
    val mb: Double = 0.0,
    val source: String = "vast",
)

/** A pack, chain, or the keepers/tests buckets. `kind` is pack | chain | keeper | test; `source` is ltx | thunder | vast. */
@Serializable
data class MediaGroup(
    val id: String,
    val title: String,
    val kind: String = "test",
    val source: String = "vast",
    val clips: List<MediaClip> = emptyList(),
    val updated: Double = 0.0,
    val count: Int = 0,
)

/** A clip together with the group it belongs to — what the gallery lays out. */
data class GalleryClip(val clip: MediaClip, val groupId: String, val groupTitle: String, val source: String)

/**
 * The hub's media routes (`hub/media.py`): the library, per-clip thumbnails and
 * contact sheets. Read-only.
 */
class MediaClient(private val http: RelayHttp) : AutoCloseable {

    /** Every group, newest first. An empty list is a real answer: the hub has no renders. */
    suspend fun library(): List<MediaGroup> {
        val body = http.getText("/api/media/library", timeoutMs = LIBRARY_TIMEOUT_MS)
        return try {
            json.decodeFromString(ListSerializer(MediaGroup.serializer()), body)
        } catch (_: SerializationException) {
            throw RelayException(
                RelayException.Kind.BadResponse,
                "The hub answered with something that is not a render library. A proxy or login page may be in the way.",
            )
        } catch (_: IllegalArgumentException) {
            throw RelayException(RelayException.Kind.BadResponse, "The hub's render library was not readable.")
        }
    }

    /** JPEG bytes. The hub makes the thumbnail with ffmpeg on first request, so this can be slow once. */
    suspend fun thumb(name: String): ByteArray =
        http.getBytes("/api/media/thumb/${segment(name)}", timeoutMs = THUMB_TIMEOUT_MS)

    /** JPEG bytes of a dense multi-frame sheet. First request per clip runs ffmpeg once per frame, hence the long timeout. */
    suspend fun contact(name: String): ByteArray =
        http.getBytes("/api/media/contact/${segment(name)}", timeoutMs = CONTACT_TIMEOUT_MS)

    override fun close() = http.close()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        const val LIBRARY_TIMEOUT_MS = 30_000L
        const val THUMB_TIMEOUT_MS = 60_000L
        const val CONTACT_TIMEOUT_MS = 180_000L

        /** A clip name as one path segment. Names never contain '/', so nothing can escape /api/media/thumb/. */
        internal fun segment(name: String): String {
            require(name.isNotEmpty() && '/' !in name && '\\' !in name) { "not a clip name: $name" }
            return name.encodeURLPathPart()
        }
    }
}

/**
 * An LRU cache bounded by *weight* (bytes), not entry count. Thumbnails vary in
 * size and a decoded bitmap is what costs memory, so counting entries would let a
 * large library exhaust the heap; this evicts least-recently-used entries until the
 * total is under the limit.
 */
class BoundedCache<K : Any, V : Any>(
    private val maxWeight: Long,
    private val weigh: (V) -> Long,
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true) // access order
    private var total = 0L

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map.remove(key)?.let { total -= weigh(it) }
        val w = weigh(value)
        // A single value bigger than the whole budget is not cached at all, rather
        // than evicting everything else to hold it.
        if (w > maxWeight) return
        map[key] = value
        total += w
        val it = map.entries.iterator()
        while (total > maxWeight && it.hasNext()) {
            val eldest = it.next()
            if (eldest.key == key) continue
            total -= weigh(eldest.value)
            it.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        total = 0
    }

    val size: Int @Synchronized get() = map.size
    val weight: Long @Synchronized get() = total
}

/** A decoded image and what it costs to keep. */
class Decoded<I : Any>(val image: I, val bytes: Long)

/**
 * The media data the Renders and Gallery screens share: the library, and decoded
 * thumbnails and contact sheets behind bounded caches.
 *
 * Owned by the window rather than a screen, so switching tabs keeps what was
 * loaded. Generic over the decoded image type so the logic is testable without a
 * graphics stack; the app instantiates it with Compose's `ImageBitmap`.
 *
 * Failure is always explicit. [error] is set whenever the last refresh failed, and
 * [groups] is then either the previous good data or null — never an empty list
 * standing in for "the request failed".
 */
class MediaRepository<I : Any>(
    private val client: MediaClient,
    private val decode: (ByteArray) -> Decoded<I>,
    thumbBudgetBytes: Long = 96L * 1024 * 1024,
    contactBudgetBytes: Long = 48L * 1024 * 1024,
    thumbConcurrency: Int = 4,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** When set, nothing is fetched and this is reported instead — e.g. no hub URL configured yet. */
    private val unavailableReason: String? = null,
) : AutoCloseable {

    /** The last successfully loaded library, or null if none has loaded yet. */
    var groups: List<MediaGroup>? by mutableStateOf(null)
        private set

    /** Why the last refresh failed, or null if it did not. */
    var error: String? by mutableStateOf(null)
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    /** When [groups] was loaded, epoch millis. */
    var loadedAtMs: Long? by mutableStateOf(null)
        private set

    private val thumbs = BoundedCache<String, Decoded<I>>(thumbBudgetBytes) { it.bytes }
    private val contacts = BoundedCache<String, Decoded<I>>(contactBudgetBytes) { it.bytes }

    // The hub runs ffmpeg for a thumbnail nobody has asked for yet, and only two at
    // once. Bounding requests here keeps a fast scroll from queueing hundreds of
    // fetches for tiles that have already scrolled away.
    private val fetchSlots = Semaphore(thumbConcurrency)
    private val refreshGate = Mutex()

    /** Reloads the library. Overlapping calls are coalesced: a second one returns while the first runs. */
    suspend fun refresh() {
        if (unavailableReason != null) {
            error = unavailableReason
            return
        }
        if (!refreshGate.tryLock()) return
        try {
            loading = true
            groups = try {
                client.library().also {
                    error = null
                    loadedAtMs = nowMs()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                error = e.message
                groups
            }
        } finally {
            loading = false
            refreshGate.unlock()
        }
    }

    /** The decoded thumbnail, from cache when possible. Failure is returned, not thrown, so a tile can show why. */
    suspend fun thumb(name: String): Result<I> = load(name, thumbs) { client.thumb(name) }

    /** The decoded contact sheet. */
    suspend fun contact(name: String): Result<I> = load(name, contacts) { client.contact(name) }

    fun cachedThumb(name: String): I? = thumbs[name]?.image

    /** Every clip flattened with its group, newest first. */
    fun allClips(): List<GalleryClip> =
        groups.orEmpty()
            .flatMap { g -> g.clips.map { GalleryClip(it, g.id, g.title, it.source.ifBlank { g.source }) } }
            .sortedByDescending { it.clip.mtime }

    override fun close() {
        thumbs.clear()
        contacts.clear()
        client.close()
    }

    private suspend fun load(
        name: String,
        cache: BoundedCache<String, Decoded<I>>,
        fetch: suspend () -> ByteArray,
    ): Result<I> {
        cache[name]?.let { return Result.success(it.image) }
        if (unavailableReason != null) return Result.failure(RelayException(RelayException.Kind.Unreachable, unavailableReason))
        return try {
            val bytes = fetchSlots.withPermit { fetch() }
            val decoded = try {
                decode(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw RelayException(RelayException.Kind.BadResponse, "The hub sent an image that could not be decoded.")
            }
            cache[name] = decoded
            Result.success(decoded.image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(RelayException(RelayException.Kind.Rejected, e.message ?: "Not a valid clip name."))
        }
    }

    companion object {
        /** Builds a repository for the given settings. */
        fun <I : Any> create(
            settings: Settings,
            decode: (ByteArray) -> Decoded<I>,
            engine: HttpClientEngine? = null,
        ): MediaRepository<I> =
            MediaRepository(
                MediaClient(RelayHttp(settings.hubUrl, settings.relayUser, settings.relayPass, engine)),
                decode,
                unavailableReason = if (settings.hubUrl.isBlank()) "Set the hub URL in Settings." else null,
            )
    }
}

/** "just now", "5m ago", "3h ago", "2d ago", "6w ago" — how long since [epochSeconds]. */
fun formatAge(nowMs: Long, epochSeconds: Double): String {
    if (epochSeconds <= 0.0) return "unknown"
    val seconds = ((nowMs / 1000.0) - epochSeconds).toLong()
    return when {
        seconds < 45 -> "just now" // also covers clock skew making it negative
        seconds < 3_600 -> "${maxOf(1, seconds / 60)}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        seconds < 14 * 86_400 -> "${seconds / 86_400}d ago"
        else -> "${seconds / (7 * 86_400)}w ago"
    }
}
