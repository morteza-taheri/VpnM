package vn.unlimit.vpngate.data.remote

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import vn.unlimit.vpngate.parser.VpnGateHtmlParser

/** One resource a mirror was validated for. */
enum class MirrorResource {
    HTML, CSV
}

/**
 * Persistent last-known-good mirror metadata.
 *
 * The old cache was a bare `List<String>`: a URL had no memory of when it was
 * seen, what it served, or whether it had failed 20 times in a row. This entry
 * keeps that evidence without changing the Room schema — the registry lives in
 * SharedPreferences and is therefore migration-free.
 */
data class MirrorRegistryEntry(
    /** The URL exactly as published by the official directory (or cached). */
    val publishedUrl: String,
    /** Scheme + authority without the per-resource path. */
    val baseUrl: String,
    /** Normalized path from the published URL, e.g. `/en/`. */
    val publishedPath: String = "/",
    val discoveredAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    /** Resources that passed structural validation at least once. */
    val supportedResources: Set<String> = emptySet(),
    val lastStatus: Int = 0,
    val lastResponseTimeMs: Long = 0L,
    val failureCount: Int = 0,
    val lastResult: String = "",
    val discoveryOrigin: String? = null,
) {
    fun isKnownFor(resource: MirrorResource): Boolean =
        resource.name in supportedResources
}

/** Pure persistence and merge rules for the mirror registry. */
object MirrorRegistryStore {
    internal const val MAX_ENTRIES = 15
    internal const val REPEATED_FAILURE_LIMIT = 5

    private val gson = Gson()
    private val listType = object : TypeToken<List<MirrorRegistryEntry>>() {}.type

    fun load(json: String?): List<MirrorRegistryEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<MirrorRegistryEntry>>(json, listType).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun toJson(entries: List<MirrorRegistryEntry>): String =
        gson.toJson(entries.take(MAX_ENTRIES))

    /** Identity used for de-duplication: scheme + authority + normalized path. */
    fun keyOf(entry: MirrorRegistryEntry): String =
        entry.publishedUrl.trimEnd('/').lowercase()

    /**
     * Merge newly published mirrors into the saved registry.
     *
     * A failed directory fetch must never erase the saved list, so this only
     * adds/updates entries. Old entries stay until the repeated-failure policy
     * explicitly deactivates them, and even then they are kept as retry candidates.
     */
    fun merge(
        existing: List<MirrorRegistryEntry>,
        discovered: List<MirrorRegistryEntry>,
        now: Long,
    ): List<MirrorRegistryEntry> {
        val byKey = LinkedHashMap<String, MirrorRegistryEntry>()
        for (entry in existing) {
            val key = keyOf(entry)
            if (key.isNotBlank()) byKey[key] = entry
        }
        for (entry in discovered) {
            val key = keyOf(entry)
            if (key.isBlank()) continue
            val previous = byKey[key]
            byKey[key] = if (previous == null) {
                entry.copy(discoveredAt = entry.discoveredAt.takeIf { it > 0L } ?: now)
            } else {
                previous.copy(
                    // A mirror still published by the directory is freshly seen;
                    // its historical successes/failures stay intact.
                    discoveredAt = now,
                    publishedUrl = entry.publishedUrl,
                    baseUrl = entry.baseUrl,
                    publishedPath = entry.publishedPath,
                )
            }
        }
        // Known-good first, then most recently seen. Nothing is discarded here;
        // the cap is applied only when choosing candidates for one run.
        return byKey.values.sortedWith(
            compareByDescending<MirrorRegistryEntry> { it.lastSuccessAt }
                .thenByDescending { it.discoveredAt },
        ).take(MAX_ENTRIES)
    }

    fun recordResult(
        entry: MirrorRegistryEntry,
        resource: MirrorResource,
        accepted: Boolean,
        status: Int,
        responseTimeMs: Long,
        now: Long,
        detail: String,
    ): MirrorRegistryEntry {
        val resources = if (accepted) entry.supportedResources + resource.name else entry.supportedResources
        return if (accepted) {
            entry.copy(
                supportedResources = resources,
                lastSuccessAt = now,
                lastStatus = status,
                lastResponseTimeMs = responseTimeMs,
                failureCount = 0,
                lastFailureAt = 0L,
                lastResult = detail.take(200),
            )
        } else {
            entry.copy(
                supportedResources = resources,
                lastFailureAt = now,
                lastStatus = status,
                lastResponseTimeMs = responseTimeMs,
                failureCount = entry.failureCount + 1,
                lastResult = detail.take(200),
            )
        }
    }

    /** Deactivate after repeated failures, but keep the entry for later retry. */
    fun shouldDeactivate(entry: MirrorRegistryEntry): Boolean =
        entry.failureCount >= REPEATED_FAILURE_LIMIT && entry.lastSuccessAt == 0L
}

/**
 * Derive candidate resource URLs from a mirror's *published* URL.
 *
 * Nothing here is trusted until a fetch has passed [VpnGateSourceValidation].
 * The published path is always tried first, so a mirror with a non-root path
 * is not silently rewritten to `/en/` or `/api/iphone/`.
 */
object MirrorResourcePaths {
    data class Plan(
        val baseUrl: String,
        val htmlPaths: List<String>,
        val csvPaths: List<String>,
        val directoryPaths: List<String>,
    )

    fun planFor(publishedUrl: String): Plan? {
        val identity = SourceUrl.forMirror(
            publishedUrl,
            SourceKind.DISCOVERED_MIRROR,
            MirrorOrigin.CANONICAL_DIRECTORY,
        ) ?: return null
        val published = identity.path
        val html = linkedSetOf<String>()
        if (published != "/" && !published.endsWith("sites.aspx", ignoreCase = true)) {
            html.add(published)
        }
        html.add("/en/")
        html.add("/")

        val csv = linkedSetOf<String>()
        val replaced = when {
            published.contains("/en/", ignoreCase = true) ->
                published.replace("/en/", "/api/iphone/", ignoreCase = true)
            published == "/" -> "/api/iphone/"
            else -> null
        }
        if (replaced != null) csv.add(replaced)
        csv.add("/api/iphone/")

        val directory = linkedSetOf<String>()
        if (published == "/en/") directory.add("/en/sites.aspx")
        if (published.endsWith("/")) {
            directory.add(published + "sites.aspx")
        } else {
            val parent = published.substringBeforeLast('/', "")
            directory.add(if (parent.isBlank()) "/sites.aspx" else "$parent/sites.aspx")
        }
        directory.add("/en/sites.aspx")
        directory.add("/sites.aspx")

        return Plan(
            baseUrl = identity.baseUrl,
            htmlPaths = html.toList(),
            csvPaths = csv.toList(),
            directoryPaths = directory.toList(),
        )
    }
}

/** Extract official mirrors from the real `sites.aspx` page. */
object MirrorDirectoryParser {
    fun extract(html: String): List<MirrorRegistryEntry> {
        val now = System.currentTimeMillis()
        val doc = VpnGateHtmlParser.makeSoup(html)
        val seen = HashSet<String>()
        val result = mutableListOf<MirrorRegistryEntry>()
        for (anchor in doc.getElementsByTag("a")) {
            val href = anchor.attr("href").trim()
            if (!VpnGateSourceValidation.isAcceptableMirrorHref(href)) continue
            val identity = SourceUrl.forMirror(
                href,
                SourceKind.DISCOVERED_MIRROR,
                MirrorOrigin.CANONICAL_DIRECTORY,
            ) ?: continue
            val published = identity.url
            val key = published.trimEnd('/').lowercase()
            if (!seen.add(key)) continue
            result.add(
                MirrorRegistryEntry(
                    publishedUrl = published,
                    baseUrl = identity.baseUrl,
                    publishedPath = identity.path,
                    discoveredAt = now,
                    discoveryOrigin = MirrorOrigin.CANONICAL_DIRECTORY,
                ),
            )
        }
        return result
    }
}
