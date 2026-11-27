package vn.unlimit.vpngate.repository

import vn.unlimit.vpngate.models.VPNGateItem

/** Country-level admission policy shared by network, fallback and manual rows. */
internal object ServerCountryPolicy {
    fun excludeRussia(items: List<VPNGateItem>, enabled: Boolean): List<VPNGateItem> {
        if (!enabled) return items
        return items.filterNot { item ->
            item.countryShort.equals("RU", ignoreCase = true) ||
                item.countryShort.equals("RUS", ignoreCase = true) ||
                item.countryLong.equals("Russia", ignoreCase = true) ||
                item.countryLong.equals("Russian Federation", ignoreCase = true)
        }
    }
}
