package vn.unlimit.vpngate.utils

import android.content.Context

import de.blinkt.openvpn.core.OpenVPNService
import kittoku.osc.service.SstpVpnService
import vn.unlimit.softether.SoftEtherVpnService

/**
 * One policy boundary for optional notification content.
 *
 * Android's VPN foreground-service notification is mandatory while a tunnel
 * is active. This policy controls optional traffic statistics, disconnect/error
 * alerts, and Auto Mode's transient scan-progress notification.
 */
object NotificationPolicy {
    fun applyNotificationPreferences(dataUtil: DataUtil, context: Context) {
        val enabled = dataUtil.getBooleanSetting(
            DataUtil.SETTING_NOTIFY_SPEED,
            DataUtil.DEFAULT_NOTIFY_SPEED,
        )
        OpenVPNService.mDisplaySpeed = enabled
        SoftEtherVpnService.mDisplaySpeed = enabled
        SstpVpnService.mDisplaySpeed = enabled

        val disconnectAlerts = dataUtil.getDisconnectNotificationsEnabled()
        SoftEtherVpnService.mShowDisconnectNotifications = disconnectAlerts
        OpenVPNService.mShowDisconnectNotifications = disconnectAlerts
        SstpVpnService.mShowDisconnectNotifications = disconnectAlerts
        if (!disconnectAlerts) {
            val appContext = context.applicationContext
            SoftEtherVpnService.cancelOptionalNotifications(appContext)
            OpenVPNService.cancelDisconnectNotifications(appContext)
            SstpVpnService.cancelOptionalNotifications(appContext)
        }
    }

    fun autoModeProgressEnabled(dataUtil: DataUtil): Boolean =
        dataUtil.getBooleanSetting(
            DataUtil.SETTING_NOTIFY_AUTO_MODE_PROGRESS,
            DataUtil.DEFAULT_NOTIFY_AUTO_MODE_PROGRESS,
        )
}
