package vn.unlimit.vpngate.data.remote

import kotlinx.coroutines.CancellationException

/** The resource kind being health-checked. */
enum class SourceResource {
    HTML, CSV, DIRECTORY
}

/**
 * One bounded health check. A successful HTTP response is NOT a successful
 * source: [validation] must also accept and, for HTML/CSV, produce usable rows.
 */
data class SourceProbeResult(
    val identity: SourceIdentity,
    val resource: SourceResource,
    val accepted: Boolean,
    val validation: SourceValidation,
    val status: Int,
    val responseTimeMs: Long,
    val contentType: String?,
    val finalUrl: String?,
    val redirectRejected: Boolean,
    val fetched: FetchedSource?,
    val detail: String,
    val rowCount: Int = 0,
) {
    val label: String get() = "${resource.name}:${identity.baseUrl}${identity.path}"
}

/** Performs one request and records the evidence, without guessing. */
class SourceProber(private val fetcher: HttpFetcher) {

    suspend fun probe(identity: SourceIdentity, resource: SourceResource): SourceProbeResult {
        val started = System.nanoTime()
        var response: HttpResponse? = null
        var detail = ""
        try {
            response = fetcher.get(identity.url, identity.redirectPolicy())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ordinary network/parse failures are source rejections. Fatal JVM
            // errors (OOM, StackOverflow, ...) must still propagate.
            detail = e.message?.take(200) ?: e::class.java.simpleName
        }
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)

        val actual = response ?: return SourceProbeResult(
            identity = identity,
            resource = resource,
            accepted = false,
            validation = SourceValidation.reject(
                SourceRejectReason.NETWORK_FAILURE,
                detail.ifBlank { "no response" },
            ),
            status = 0,
            responseTimeMs = elapsed,
            contentType = null,
            finalUrl = null,
            redirectRejected = false,
            fetched = null,
            detail = detail.ifBlank { "no response" },
            rowCount = 0,
        )

        val fetched = FetchedSource(identity, actual)
        val validation = when (resource) {
            SourceResource.HTML -> VpnGateSourceValidation.validateHtml(fetched)
            SourceResource.CSV -> VpnGateSourceValidation.validateApi(fetched)
            SourceResource.DIRECTORY -> VpnGateSourceValidation.validateDirectory(fetched)
        }
        return SourceProbeResult(
            identity = identity,
            resource = resource,
            accepted = validation.accepted,
            validation = validation,
            status = actual.status,
            responseTimeMs = elapsed,
            contentType = actual.contentType,
            finalUrl = actual.finalUrl,
            redirectRejected = actual.redirectRejected,
            fetched = fetched.takeIf { validation.accepted },
            detail = validation.detail.ifBlank { "HTTP ${actual.status}" },
            rowCount = validation.rowCount,
        )
    }
}
