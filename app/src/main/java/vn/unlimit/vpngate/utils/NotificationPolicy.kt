package vn.unlimit.vpngate.utils

import de.blinkt.openvpn.core.OpenVPNService
import kittoku.osc.service.SstpVpnService
import vn.unlimit.softether.SoftEtherVpnService

/**
 * One policy boundary for optional notification content.
 *
 * Android's VPN foreground-service notification is mandatory while a tunnel
 * is active; this policy only controls optional traffic statistics and
 * Auto Mode's transient scan-progress notification.
 */
object NotificationPolicy {
    fun applyTrafficStatistics(dataUtil: DataUtil) {
        val enabled = dataUtil.getBooleanSetting(
            DataUtil.SETTING_NOTIFY_SPEED,
            DataUtil.DEFAULT_NOTIFY_SPEED,
        )
        OpenVPNService.mDisplaySpeed = enabled
        SoftEtherVpnService.mDisplaySpeed = enabled
        SstpVpnService.mDisplaySpeed = enabled
    }

    fun autoModeProgressEnabled(dataUtil: DataUtil): Boolean =
        dataUtil.getBooleanSetting(
            DataUtil.SETTING_NOTIFY_AUTO_MODE_PROGRESS,
            DataUtil.DEFAULT_NOTIFY_AUTO_MODE_PROGRESS,
        )
}
