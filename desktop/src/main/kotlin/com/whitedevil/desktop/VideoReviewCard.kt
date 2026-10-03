package com.whitedevil.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SOURCE_LABEL = mapOf("ltx" to "LTX 2.5", "thunder" to "Thunder 14B", "vast" to "Vast / Colab Remix")

/**
 * "Video review": the contact sheet of the latest finished clip of one source with the other recent ones in a strip
 * (click one to look at it), and **AI review**, which sends the selected clip's contact sheet to a Venice model that can
 * see images and shows what it says. Port of the web page's Video review card and its "AI review latest" button.
 *
 * [promptFor] gives the text a clip was made from, when this screen knows it (the LTX builder does), so the review can
 * judge how well the frames match what was asked for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoReviewCard(
    settings: Settings,
    media: MediaClient,
    actions: ClipActions,
    source: String,
    promptFor: (String) -> String? = { null },
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    var clips by remember { mutableStateOf<List<ReviewClip>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(0) }

    LaunchedEffect(refresh, source) {
        when (val r = media.library()) {
            is MediaResult.Ok -> { clips = reviewClips(r.value.groups, source); problem = null; selected = 0 }
            is MediaResult.Failure -> problem = r.error.message
        }
    }
    val list = clips
    val current = list?.getOrNull(selected)

    // The big picture: the contact sheet (the hub makes it on demand with ffmpeg, so it can take a while).
    val sheetBytes by produceState<ByteArray?>(null, current?.name) {
        value = null
        val name = current?.name ?: return@produceState
        value = (media.contactSheet(name) as? MediaResult.Ok)?.value
    }
    val sheet by produceState<ImageBitmap?>(null, sheetBytes) { value = sheetBytes?.let { b -> runCatching { decodeToBitmap(b) }.getOrNull() } }

    var reviewing by remember { mutableStateOf(false) }
    var review by remember(current?.name) { mutableStateOf<String?>(null) }
    var reviewError by remember(current?.name) { mutableStateOf<String?>(null) }
    var reviewModel by remember(current?.name) { mutableStateOf<String?>(null) }

    Card("Video review", SOURCE_LABEL[source] ?: source) {
        when {
            problem != null && list == null -> Tip("Couldn't load the renders: $problem", Forge.Bad)
            list == null -> Tip("Loading recent renders…")
            list.isEmpty() -> Tip("No ${SOURCE_LABEL[source] ?: source} renders yet. Nothing to review.")
            else -> {
                val c = current ?: list.first()
                Tip("${list.size} recent · latest ${formatAge(list.first().mtime, System.currentTimeMillis())} · selected: ${prettyClipName(c.name)}")
                val shape = RoundedCornerShape(12.dp)
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(Forge.Well).border(1.dp, Forge.Line, shape), contentAlignment = Alignment.Center) {
                    val bmp = sheet
                    if (bmp != null) Image(bmp, "Contact sheet of ${c.name}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                    else Text(if (sheetBytes == null) "Making the contact sheet…" else "The contact sheet couldn't be shown.", color = Forge.Dim, fontSize = 12.sp)
                }
                // the other recent clips
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEachIndexed { i, clip -> ReviewThumb(clip, media, i == selected) { selected = i } }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(if (reviewing) "REVIEWING…" else "✦ AI REVIEW", true) {
                        if (reviewing) return@SmallButton
                        reviewing = true; review = null; reviewError = null
                        scope.launch {
                            val bytes = sheetBytes ?: (media.contactSheet(c.name) as? MediaResult.Ok)?.value
                            if (bytes == null) { reviewError = "The contact sheet isn't ready, so there is nothing to review yet."; reviewing = false; return@launch }
                            val dataUrl = withContext(Dispatchers.Default) { shrinkToJpegDataUrl(bytes, 1600, 3_800_000) }
                            if (dataUrl == null) { reviewError = "The contact sheet couldn't be prepared for the model."; reviewing = false; return@launch }
                            val models = (fetchVeniceModels(settings.veniceApiKey) as? MediaResult.Ok)?.value.orEmpty()
                            // The model you chose in Venice if it can see images; otherwise a vision model, named below.
                            val model = pickVisionModel(settings.model, models)?.id ?: settings.model
                            reviewModel = model
                            when (val r = reviewWithVision(settings.veniceApiKey, model, reviewPrompt(c, promptFor(c.name)), dataUrl)) {
                                is MediaResult.Ok -> review = r.value
                                is MediaResult.Failure -> reviewError = r.error.message
                            }
                            reviewing = false
                        }
                    }
                    ClipButtons(actions, c.name)
                    SmallButton("REFRESH", false) { refresh++ }
                }
                reviewError?.let { Tip(it, Forge.Bad) }
                review?.let { text ->
                    Label("Review by ${reviewModel ?: "Venice"}")
                    SelectionContainer {
                        Text(text, color = Forge.Fg, fontSize = 13.sp, lineHeight = 20.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Forge.Well).border(1.dp, Forge.Line, RoundedCornerShape(10.dp)).padding(12.dp))
                    }
                    Tip("A contact sheet is still frames: the review can't judge motion or sound.")
                }
            }
        }
    }
}

@Composable
private fun ReviewThumb(clip: ReviewClip, media: MediaClient, selected: Boolean, onClick: () -> Unit) {
    val bmp by produceState<ImageBitmap?>(null, clip.name) {
        value = (media.thumb(clip.name) as? MediaResult.Ok)?.let { r -> runCatching { decodeToBitmap(r.value) }.getOrNull() }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.width(110.dp).height(66.dp).clip(shape).background(Forge.Well)
            .border(2.dp, if (selected) Forge.Acc else Forge.Line, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b, prettyClipName(clip.name), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(66.dp))
        else Text("…", color = Forge.Dim, fontSize = 12.sp)
    }
}
