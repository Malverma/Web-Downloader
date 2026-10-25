package com.webdownloader.extraction

import kotlinx.serialization.Serializable

@Serializable
data class SiteRule(
    val host: String,
    val seriesUrlPattern: String? = null,
    val chapterUrlPattern: String? = null,
    val chapterLinkSelector: String? = null,
    val chapterNumberRegex: String? = null,
    val titleSelector: String? = null,
    val coverSelector: String? = null,
    val imageContainerSelector: String? = null,
    val imageFilter: ImageFilter? = null,
    val pageNumberFrom: PageNumberFrom? = null,
    val expandSelector: String? = null,
    val requestDelayMs: Int? = null,
)

@Serializable
data class ImageFilter(
    val urlContains: String? = null,
    val altRegex: String? = null,
    val minWidth: Int? = null,
)

@Serializable
data class PageNumberFrom(
    val alt: String? = null,
    val url: String? = null,
)

@Serializable
data class ExtractResult(
    val type: String = "none",
    val title: String? = null,
    val cover: String? = null,
    val url: String = "",
    val seriesUrl: String? = null,
    val chapters: List<ExtractedChapter> = emptyList(),
    val images: List<ExtractedImage> = emptyList(),
    val challenge: Boolean = false,
    val scrollY: Double = 0.0,
    val scrollMax: Double = 0.0,
)

@Serializable
data class ExtractedChapter(
    val url: String,
    val label: String = "",
    val number: Double? = null,
)

@Serializable
data class ExtractedImage(
    val url: String,
    val page: Int? = null,
)

class ChallengeException(host: String) :
    Exception("$host is showing a browser check. Open it once in the Browse tab, then resume downloads.")
