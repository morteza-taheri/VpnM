package vn.unlimit.vpngate.automode

import android.os.SystemClock
import de.blinkt.openvpn.core.VpnStatus
import kittoku.osc.service.SstpTrafficSnapshot
import kittoku.osc.service.SstpVpnService
import vn.unlimit.softether.SoftEtherTrafficSnapshot
import vn.unlimit.softether.SoftEtherVpnService

/**
 * Observes real inbound bytes already flowing through the established VPN tunnel.
 * No synthetic download is generated: the app's own management sockets are
 * deliberately excluded from the SoftEther TUN, so a self-generated HTTP test
 * would not consistently measure the same path for every protocol. Actual
 * user traffic (video, messaging, browsing and downloads) supplies the samples.
 */
internal class AutoModeThroughputMonitor(
    private val onSample: (downloadBps: Long, durationMs: Long) -> Unit,
) : SoftEtherVpnService.TrafficListener,
    SstpVpnService.TrafficListener,
    VpnStatus.ByteCountListener {

    @Volatile private var activeProtocol: AutoModeProtocol? = null
    @Volatile private var serverIdentity: String? = null
    private var ignoreInitialSnapshot = true
    private var windowStartedAtMs = 0L
    private var lastCallbackAtMs = 0L
    private var windowBytes = 0L

    @Synchronized
    fun start(identity: String, protocol: AutoModeProtocol) {
        stop()
        serverIdentity = identity
        activeProtocol = protocol
        ignoreInitialSnapshot = true
        windowStartedAtMs = 0L
        lastCallbackAtMs = 0L
        when (protocol) {
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.SOFTETHER_UDP -> SoftEtherVpnService.addTrafficListener(this)
            AutoModeProtocol.OPENVPN_TCP,
            AutoModeProtocol.OPENVPN_UDP -> VpnStatus.addByteCountListener(this)
            AutoModeProtocol.MS_SSTP -> SstpVpnService.addTrafficListener(this)
            AutoModeProtocol.L2TP_IPSEC -> {
                activeProtocol = null
                serverIdentity = null
            }
        }
    }

    @Synchronized
    fun stop() {
        when (activeProtocol) {
            AutoModeProtocol.SOFTETHER_TCP,
            AutoModeProtocol.SOFTETHER_UDP -> SoftEtherVpnService.removeTrafficListener(this)
            AutoModeProtocol.OPENVPN_TCP,
            AutoModeProtocol.OPENVPN_UDP -> VpnStatus.removeByteCountListener(this)
            AutoModeProtocol.MS_SSTP -> SstpVpnService.removeTrafficListener(this)
            else -> Unit
        }
        activeProtocol = null
        serverIdentity = null
        ignoreInitialSnapshot = true
        windowStartedAtMs = 0L
        lastCallbackAtMs = 0L
        windowBytes = 0L
    }

    @Synchronized
    fun matches(identity: String, protocol: AutoModeProtocol): Boolean =
        serverIdentity == identity && activeProtocol == protocol

    override fun onSoftEtherTrafficUpdated(snapshot: SoftEtherTrafficSnapshot) {
        accept(snapshot.diffInBytes)
    }

    override fun onSstpTrafficUpdated(snapshot: SstpTrafficSnapshot) {
        accept(snapshot.diffInBytes)
    }

    override fun updateByteCount(totalIn: Long, totalOut: Long, diffIn: Long, diffOut: Long) {
        accept(diffIn)
    }

    @Synchronized
    private fun accept(inboundDeltaBytes: Long) {
        if (activeProtocol == null) return
        val now = SystemClock.elapsedRealtime()
        if (ignoreInitialSnapshot) {
            ignoreInitialSnapshot = false
            lastCallbackAtMs = now
            return
        }
        if (lastCallbackAtMs > 0L && now - lastCallbackAtMs > MAX_WINDOW_GAP_MS) {
            windowStartedAtMs = 0L
            windowBytes = 0L
        }
        lastCallbackAtMs = now
        if (inboundDeltaBytes <= 0L) return
        if (windowStartedAtMs == 0L) windowStartedAtMs = now
        windowBytes += inboundDeltaBytes
        val elapsedMs = now - windowStartedAtMs
        if (elapsedMs < WINDOW_MS) return
        val bytes = windowBytes
        if (elapsedMs <= MAX_WINDOW_GAP_MS && bytes >= MIN_SAMPLE_BYTES) {
            val bps = (bytes.toDouble() * 8_000.0 / elapsedMs.toDouble()).toLong()
            if (bps in MIN_REASONABLE_BPS..MAX_REASONABLE_BPS) onSample(bps, elapsedMs)
        }
        windowStartedAtMs = now
        windowBytes = 0L
    }

    private companion object {
        const val WINDOW_MS = 3_000L
        const val MAX_WINDOW_GAP_MS = 10_000L
        const val MIN_SAMPLE_BYTES = 64L * 1024L
        const val MIN_REASONABLE_BPS = 32_000L
        const val MAX_REASONABLE_BPS = 10_000_000_000L
    }
}
