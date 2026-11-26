package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** User-visible stages of one source-discovery run. */
enum class SourceDiscoveryStage {
    CHECKING_OFFICIAL_SOURCES,
    DOWNLOADING_HTML,
    DOWNLOADING_CSV,
    DISCOVERING_MIRRORS,
    TESTING_MIRRORS,
    MERGING,
    COMPLETED,
    FAILED,
}

data class SourceDiscoverySummary(
    val officialHtmlAvailable: Boolean = false,
    val officialCsvAvailable: Boolean = false,
    val mirrorsDiscovered: Int = 0,
    val mirrorsTested: Int = 0,
    val mirrorsValidated: Int = 0,
    val htmlSourceUsed: String? = null,
    val csvSourceUsed: String? = null,
    val failedSources: List<String> = emptyList(),
    val recordsReceived: Int = 0,
)

data class SourceDiscoveryResult(
    val html: FetchedSource?,
    val csv: FetchedSource?,
    val summary: SourceDiscoverySummary,
    val registry: List<MirrorRegistryEntry>,
)

/**
 * Owns the health checks, mirror discovery and per-mirror validation for one
 * refresh. It never writes the server database: it only returns the bodies the
 * repository is allowed to parse.
 *
 * HTML and CSV are separate resources. A mirror that serves a valid hosts
 * table is not assumed to serve the canonical CSV schema, and vice versa.
 */
class SourceDiscoveryCoordinator(
    private val fetcher: HttpFetcher,
    private val loadRegistry: () -> List<MirrorRegistryEntry> = { emptyList() },
    private val saveRegistry: (List<MirrorRegistryEntry>) -> Unit = {},
    private val progress: (SourceDiscoveryStage, String) -> Unit = { _, _ -> Unit },
    private val timeoutMs: Long = DEFAULT_SOURCE_TIMEOUT_MS,
    private val directoryTimeoutMs: Long = DEFAULT_DIRECTORY_TIMEOUT_MS,
) {
    private val prober = SourceProber(fetcher)

    suspend fun discover(includeCsv: Boolean): SourceDiscoveryResult {
        val registry = loadRegistry().toMutableList()
        if (registry.isEmpty()) registry.addAll(bootstrapEntries())
        val failed = mutableListOf<String>()
        var officialHtml = false
        var officialCsv = false

        progress(SourceDiscoveryStage.CHECKING_OFFICIAL_SOURCES, "official sources")
        var selectedHtml: FetchedSource? = null
        var selectedCsv: FetchedSource? = null

        coroutineScope {
            val htmlDeferred = async {
                progress(SourceDiscoveryStage.DOWNLOADING_HTML, VpnGateUrls.MAIN_URL)
                probeCanonical(SourceResource.HTML, VpnGateUrls.MAIN_URL, SourceKind.CANONICAL_HTML)
            }
            val csvDeferred = if (includeCsv) {
                async {
                    progress(SourceDiscoveryStage.DOWNLOADING_CSV, VpnGateUrls.API_URL)
                    probeCanonical(SourceResource.CSV, VpnGateUrls.API_URL, SourceKind.CANONICAL_API)
                }
            } else null

            val htmlProbe = htmlDeferred.await()
            // When the setting is OFF we still owe the historical CSV fallback:
            // probe it once only if HTML produced no usable records.
            val csvProbe = csvDeferred?.await()
                ?: if (htmlProbe.accepted) null else probeCanonical(
                    SourceResource.CSV,
                    VpnGateUrls.API_URL,
                    SourceKind.CANONICAL_API,
                )
            officialHtml = htmlProbe.accepted
            selectedHtml = htmlProbe.fetched
            if (htmlProbe.accepted) {
                failed.removeAll { it.startsWith("HTML:") }
            } else {
                failed.add(htmlProbe.label)
            }
            if (csvProbe != null) {
                officialCsv = csvProbe.accepted
                selectedCsv = csvProbe.fetched
                if (csvProbe.accepted) {
                    failed.removeAll { it.startsWith("CSV:") }
                } else {
                    failed.add(csvProbe.label)
                }
            }
        }

        val needHtml = selectedHtml == null
        // The setting controls "CSV alongside HTML". It must not disable the
        // long-standing CSV fallback when HTML produced no usable records.
        val needCsv = selectedCsv == null && (includeCsv || selectedHtml == null)
        var discovered = emptyList<MirrorRegistryEntry>()

        if (needHtml || needCsv) {
            val directory = discoverDirectory(registry, failed)
            discovered = directory.second
            registry.addAll(
                discovered.filterNot { incoming ->
                    registry.any { MirrorRegistryStore.keyOf(it) == MirrorRegistryStore.keyOf(incoming) }
                },
            )
            val merged = MirrorRegistryStore.merge(loadRegistry().ifEmpty { registry }, discovered, System.currentTimeMillis())
            registry.clear()
            registry.addAll(merged)

            val mirrorResult = testMirrors(registry, needHtml, needCsv, failed)
            if (selectedHtml == null) selectedHtml = mirrorResult.first
            if (selectedCsv == null) selectedCsv = mirrorResult.second
        }

        saveRegistry(registry.toList())
        val htmlRows = selectedHtml?.let { src ->
            VpnGateSourceValidation.validateHtml(FetchedSource(src.identity, src.response)).rowCount
        } ?: 0
        val csvRows = selectedCsv?.let { src ->
            VpnGateSourceValidation.validateApi(FetchedSource(src.identity, src.response)).rowCount
        } ?: 0
        val summary = SourceDiscoverySummary(
            officialHtmlAvailable = officialHtml,
            officialCsvAvailable = officialCsv,
            mirrorsDiscovered = discovered.size,
            mirrorsTested = registry.size,
            mirrorsValidated = registry.count { it.supportedResources.isNotEmpty() },
            htmlSourceUsed = selectedHtml?.identity?.sourceTag,
            csvSourceUsed = selectedCsv?.identity?.sourceTag,
            failedSources = failed.distinct(),
            recordsReceived = htmlRows + csvRows,
        )
        if (selectedHtml != null || selectedCsv != null) {
            progress(SourceDiscoveryStage.COMPLETED, summary.toString())
        } else {
            progress(SourceDiscoveryStage.FAILED, "all sources failed")
        }
        return SourceDiscoveryResult(selectedHtml, selectedCsv, summary, registry.toList())
    }

    private suspend fun probeCanonical(
        resource: SourceResource,
        url: String,
        kind: SourceKind,
    ): SourceProbeResult {
        val identity = SourceUrl.parse(url, kind)
            ?: return rejectedIdentity(resource, url)
        return probe(identity, resource, timeoutMs) ?: timeoutResult(identity, resource)
    }

    private suspend fun discoverDirectory(
        registry: List<MirrorRegistryEntry>,
        failed: MutableList<String>,
    ): Pair<FetchedSource?, List<MirrorRegistryEntry>> {
        progress(SourceDiscoveryStage.DISCOVERING_MIRRORS, VpnGateUrls.MIRRORS_URL)
        var probe = probeCanonical(
            SourceResource.DIRECTORY,
            VpnGateUrls.MIRRORS_URL,
            SourceKind.CANONICAL_HTML,
        )
        if (!probe.accepted) {
            failed.add(probe.label)
            // A failed directory request must NOT erase the cached registry.
            for ((index, entry) in registry.take(3).withIndex()) {
                val plan = MirrorResourcePaths.planFor(entry.publishedUrl) ?: continue
                for (path in plan.directoryPaths) {
                    val identity = SourceUrl.forMirror(
                        entry.baseUrl,
                        SourceKind.CACHED_MIRROR,
                        MirrorOrigin.MIRROR_CACHE,
                    )?.withPath(path)?.withMirrorId("mirror_${index + 1}") ?: continue
                    probe = probe(identity, SourceResource.DIRECTORY, directoryTimeoutMs)
                        ?: timeoutResult(identity, SourceResource.DIRECTORY)
                    if (probe.accepted) break
                }
                if (probe.accepted) break
                failed.add(probe.label)
            }
        }
        val body = probe.fetched?.body ?: return null to emptyList()
        return probe.fetched to MirrorDirectoryParser.extract(body)
    }

    private suspend fun testMirrors(
        registry: MutableList<MirrorRegistryEntry>,
        needHtml: Boolean,
        needCsv: Boolean,
        failed: MutableList<String>,
    ): Pair<FetchedSource?, FetchedSource?> {
        val updated = registry.toMutableList()
        val candidates = registry.filterNot { MirrorRegistryStore.shouldDeactivate(it) }
            .take(VpnGateUrls.MAX_MIRRORS)
        var html: FetchedSource? = null
        var csv: FetchedSource? = null
        progress(SourceDiscoveryStage.TESTING_MIRRORS, "0/${candidates.size}")

        for ((index, entry) in candidates.withIndex()) {
            progress(SourceDiscoveryStage.TESTING_MIRRORS, "${index + 1}/${candidates.size} ${entry.baseUrl}")
            val plan = MirrorResourcePaths.planFor(entry.publishedUrl) ?: continue
            val kind = if (entry.discoveryOrigin == MirrorOrigin.MIRROR_CACHE) {
                SourceKind.CACHED_MIRROR
            } else {
                SourceKind.DISCOVERED_MIRROR
            }
            val resources = listOf(SourceResource.HTML, SourceResource.CSV)
            for (resource in resources) {
                if (resource == SourceResource.HTML && (!needHtml || html != null)) continue
                if (resource == SourceResource.CSV && (!needCsv || csv != null)) continue
                val paths = if (resource == SourceResource.HTML) plan.htmlPaths else plan.csvPaths
                var accepted = false
                for (path in paths) {
                    val identity = SourceUrl.forMirror(entry.baseUrl, kind, entry.discoveryOrigin)
                        ?.withPath(path)
                        ?.withMirrorId(VpnGateSourceCandidates.MIRROR_TAG_PREFIX + (index + 1))
                        ?: continue
                    val result = probe(identity, resource, timeoutMs)
                        ?: timeoutResult(identity, resource)
                    val at = updated.indexOfFirst {
                        MirrorRegistryStore.keyOf(it) == MirrorRegistryStore.keyOf(entry)
                    }
                    if (at >= 0) {
                        updated[at] = MirrorRegistryStore.recordResult(
                            updated[at],
                            MirrorResource.valueOf(resource.name),
                            result.accepted,
                            result.status,
                            result.responseTimeMs,
                            System.currentTimeMillis(),
                            result.detail,
                        )
                        // Commit each successful validation immediately: a later
                        // cancellation or process death must not lose it.
                        if (result.accepted) saveRegistry(updated.toList())
                    }
                    if (result.accepted) {
                        if (resource == SourceResource.HTML) html = result.fetched else csv = result.fetched
                        accepted = true
                        break
                    }
                    failed.add(result.label)
                }
                if (!accepted) failed.add("${resource.name}:${entry.baseUrl} (no valid path)")
            }
            if ((!needHtml || html != null) && (!needCsv || csv != null)) break
        }
        registry.clear()
        registry.addAll(updated)
        return html to csv
    }

    private suspend fun probe(
        identity: SourceIdentity,
        resource: SourceResource,
        budgetMs: Long,
    ): SourceProbeResult? = try {
        withTimeoutOrNull(budgetMs.coerceAtLeast(1L)) { prober.probe(identity, resource) }
    } catch (e: CancellationException) {
        throw e
    }

    private fun timeoutResult(identity: SourceIdentity, resource: SourceResource) = SourceProbeResult(
        identity = identity,
        resource = resource,
        accepted = false,
        validation = SourceValidation.reject(SourceRejectReason.NETWORK_FAILURE, "timeout"),
        status = 0,
        responseTimeMs = timeoutMs,
        contentType = null,
        finalUrl = null,
        redirectRejected = false,
        fetched = null,
        detail = "timeout",
    )

    private fun rejectedIdentity(resource: SourceResource, url: String) = SourceProbeResult(
        identity = SourceIdentity(SourceKind.DISCOVERED_MIRROR, "http", "invalid", path = "/"),
        resource = resource,
        accepted = false,
        validation = SourceValidation.reject(SourceRejectReason.NETWORK_FAILURE, "invalid URL: $url"),
        status = 0,
        responseTimeMs = 0L,
        contentType = null,
        finalUrl = null,
        redirectRejected = false,
        fetched = null,
        detail = "invalid URL: $url",
    )

    private fun bootstrapEntries(): List<MirrorRegistryEntry> =
        VpnGateUrls.BOOTSTRAP_MIRRORS.mapNotNull { url ->
            SourceUrl.forMirror(url, SourceKind.BOOTSTRAP_MIRROR, MirrorOrigin.BOOTSTRAP_LIST)
                ?.let { identity ->
                    MirrorRegistryEntry(
                        publishedUrl = identity.url,
                        baseUrl = identity.baseUrl,
                        publishedPath = identity.path,
                        discoveredAt = System.currentTimeMillis(),
                        discoveryOrigin = MirrorOrigin.BOOTSTRAP_LIST,
                    )
                }
        }
}
