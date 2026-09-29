package com.whitedevil.desktop

import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf

/**
 * Shaped like the real output of hub/media.py `library()`: groups with
 * id/title/kind/source/clips/updated/count, clips with name/idx/mtime/mb/source,
 * `idx: null` for un-numbered clips, a non-ASCII title (the hub joins pack number
 * and title with U+00B7), and awkward file names.
 */
internal const val SAMPLE_LIBRARY = """[
  {"id":"goon-p03","title":"Pack 3 · Neon Nights","kind":"pack","source":"thunder",
   "clips":[
     {"name":"smoke_goon_p03_c01_14b.mp4","idx":1,"mtime":1759176000.5,"mb":12.3,"source":"thunder"},
     {"name":"smoke_goon_p03_c02_14b.mp4","idx":2,"mtime":1759176100.25,"mb":11.9,"source":"thunder"}],
   "updated":1759176100.25,"count":2},
  {"id":"tests","title":"Tests & experiments","kind":"test","source":"vast",
   "clips":[
     {"name":"scratch test #1 (final).mp4","idx":null,"mtime":1750000000.0,"mb":0.4,"source":"vast"}],
   "updated":1750000000.0,"count":1}
]"""

internal val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
internal val jpegHeaders = headersOf(HttpHeaders.ContentType, "image/jpeg")

internal fun client(
    engine: MockEngine,
    hub: String = "https://hub.example",
    user: String = "anon3",
    pass: String = "secret",
    timeouts: MediaTimeouts = MediaTimeouts(),
) = MediaClient(hub, user, pass, engine, timeouts)

internal fun MediaResult<*>.failure(): MediaError =
    (this as? MediaResult.Failure)?.error ?: error("expected a Failure but got $this")

internal fun <T> MediaResult<T>.ok(): T =
    (this as? MediaResult.Ok)?.value ?: error("expected Ok but got $this")
