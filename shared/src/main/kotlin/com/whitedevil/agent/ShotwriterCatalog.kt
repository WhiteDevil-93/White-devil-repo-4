package com.whitedevil.agent

import kotlinx.serialization.Serializable

@Serializable
data class VideoModel(
    val id: String,
    val name: String,
    val description: String,
    val supportedResolutions: List<String>,
    val supportedRatios: List<String>,
    val supportedLengths: List<String>,
    val supportsAudio: Boolean
)

object ShotwriterCatalog {
    val models = listOf(
        VideoModel(
            id = "flux3-video",
            name = "FLUX.3 Video",
            description = "Black Forest Labs video with native audio, up...",
            supportedResolutions = listOf("720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1", "21:9"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = true
        ),
        VideoModel(
            id = "gemini-omni-flash-1-1",
            name = "Gemini Omni Flash 1.1",
            description = "Multimodal video with native audio",
            supportedResolutions = listOf("720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = true
        ),
        VideoModel(
            id = "seedance",
            name = "Seedance",
            description = "Flexible motion styles for creative storytelling.",
            supportedResolutions = listOf("720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1", "4:3"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = false
        ),
        VideoModel(
            id = "gemini-video",
            name = "Gemini",
            description = "Fast conversational video with native audio.",
            supportedResolutions = listOf("720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = true
        ),
        VideoModel(
            id = "wan",
            name = "Wan",
            description = "Fluid animation and versatile models.",
            supportedResolutions = listOf("480p", "720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1", "21:9"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = false
        ),
        VideoModel(
            id = "grok",
            name = "Grok",
            description = "Expansive creative range for dynamic video content.",
            supportedResolutions = listOf("720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1"),
            supportedLengths = listOf("5s"),
            supportsAudio = false
        ),
        VideoModel(
            id = "kling",
            name = "Kling",
            description = "High-consistency motion and camera control.",
            supportedResolutions = listOf("720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1", "4:3", "3:4"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = false
        ),
        VideoModel(
            id = "happyhorse",
            name = "HappyHorse",
            description = "Cinematic creative generation, ultimate dynamic detail.",
            supportedResolutions = listOf("720p", "1080p", "4K"),
            supportedRatios = listOf("16:9", "9:16", "21:9"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = false
        ),
        VideoModel(
            id = "minimax",
            name = "MiniMax",
            description = "Multimodal video with native audio.",
            supportedResolutions = listOf("720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = true
        ),
        VideoModel(
            id = "pixverse-v6",
            name = "PixVerse V6",
            description = "Cinematic 1080p video with native audio.",
            supportedResolutions = listOf("720p", "1080p", "4K"),
            supportedRatios = listOf("16:9", "9:16", "1:1", "21:9"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = true
        ),
        VideoModel(
            id = "veo-3-1-lite",
            name = "Veo 3.1 Lite",
            description = "Cost-effective video from Google.",
            supportedResolutions = listOf("720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = false
        ),
        VideoModel(
            id = "dreamactor-m2",
            name = "DreamActor M2.0",
            description = "Superior video motion transfer for crowds an...",
            supportedResolutions = listOf("720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = false
        ),
        VideoModel(
            id = "veo-3-1",
            name = "Veo 3.1",
            description = "Latest and greatest from Google.",
            supportedResolutions = listOf("720p", "1080p", "4K"),
            supportedRatios = listOf("16:9", "9:16", "1:1"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = false
        ),
        VideoModel(
            id = "veo-3-1-fast",
            name = "Veo 3.1 Fast",
            description = "Latest and most efficient from Google.",
            supportedResolutions = listOf("720p", "1080p"),
            supportedRatios = listOf("16:9", "9:16", "1:1"),
            supportedLengths = listOf("5s", "10s"),
            supportsAudio = false
        )
    )
}
