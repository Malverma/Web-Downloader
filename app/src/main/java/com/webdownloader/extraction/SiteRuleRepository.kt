package com.webdownloader.extraction

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import java.io.File

/** Bundled rules from assets/site_rules, overridden by user rules in filesDir/site_rules. */
class SiteRuleRepository(private val context: Context, private val json: Json) {
    private val userDir = File(context.filesDir, "site_rules").apply { mkdirs() }
    private val pretty = Json(json) { prettyPrint = true }

    @Volatile
    private var cache: Map<String, SiteRule>? = null

    private fun parse(text: String): SiteRule? =
        runCatching { json.decodeFromString(SiteRule.serializer(), text) }.getOrNull()

    private fun bundledText(host: String): String? =
        runCatching { context.assets.open("site_rules/$host.json").bufferedReader().use { it.readText() } }.getOrNull()

    private fun bundled(): Map<String, SiteRule> =
        (context.assets.list("site_rules") ?: emptyArray())
            .filter { it.endsWith(".json") }
            .mapNotNull { bundledText(it.removeSuffix(".json"))?.let(::parse) }
            .associateBy { it.host }

    private fun user(): Map<String, SiteRule> =
        (userDir.listFiles() ?: emptyArray())
            .filter { it.extension == "json" }
            .mapNotNull { parse(it.readText()) }
            .associateBy { it.host }

    fun all(): Map<String, SiteRule> = cache ?: (bundled() + user()).also { cache = it }

    fun forUrl(url: String): SiteRule? {
        val host = Uri.parse(url).host?.lowercase()?.removePrefix("www.") ?: return null
        return all().values.firstOrNull { host == it.host || host.endsWith("." + it.host) }
    }

    fun isUserOverride(host: String) = File(userDir, "$host.json").exists()

    fun hasBundled(host: String) = bundledText(host) != null

    fun text(host: String): String {
        File(userDir, "$host.json").takeIf { it.exists() }?.let { return it.readText() }
        bundledText(host)?.let { return it }
        return pretty.encodeToString(SiteRule.serializer(), SiteRule(host = host))
    }

    fun template(): String = pretty.encodeToString(
        SiteRule.serializer(),
        SiteRule(
            host = "example.com",
            chapterLinkSelector = "a[href*='/chapter']",
            imageContainerSelector = ".reader-area",
            imageFilter = ImageFilter(minWidth = 300),
        ),
    )

    /** Validates and saves [text] as a user rule. Throws on invalid JSON. */
    fun save(text: String): SiteRule {
        val rule = json.decodeFromString(SiteRule.serializer(), text)
        require(rule.host.isNotBlank()) { "\"host\" is required" }
        File(userDir, "${rule.host.lowercase()}.json").writeText(text)
        cache = null
        return rule
    }

    fun reset(host: String) {
        File(userDir, "$host.json").delete()
        cache = null
    }
}
