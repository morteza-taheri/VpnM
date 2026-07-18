package vn.unlimit.vpngate.compat

/**
 * Drop-in, offline replacement for `com.google.firebase.remoteconfig.FirebaseRemoteConfig`.
 *
 * The original app used Firebase Remote Config both for A/B-style feature flags AND, more
 * importantly, to hold the API base URLs the app talks to (vpn_paid_server_api,
 * vpn_udp_api, ...). This build no longer fetches anything from Google, so the values that used
 * to be the "remote defaults" (see the old res/xml/remote_config_defaults.xml) are now simply
 * the permanent local configuration. Ad-related flags are hardcoded to disabled since all ad
 * SDKs have been removed from this build.
 *
 * Imported under the alias `FirebaseRemoteConfig` at call sites, so no other code had to change.
 */
class LocalRemoteConfig private constructor() {

    fun fetchAndActivate(): LocalTask<Boolean> = LocalTask(isSuccessful = true, result = false)

    fun getString(key: String): String = (defaults[key] as? String) ?: ""

    fun getBoolean(key: String): Boolean = when (val value = defaults[key]) {
        is Boolean -> value
        is String -> value.toBoolean()
        else -> false
    }

    fun setDefaultsAsync(@Suppress("UNUSED_PARAMETER") resId: Int): LocalTask<Void?> =
        LocalTask(isSuccessful = true, result = null)

    fun setConfigSettingsAsync(@Suppress("UNUSED_PARAMETER") settings: LocalRemoteConfigSettings): LocalTask<Void?> =
        LocalTask(isSuccessful = true, result = null)

    companion object {
        @JvmStatic
        fun getInstance(): LocalRemoteConfig = instance

        private val instance = LocalRemoteConfig()

        private val defaults: Map<String, Any> = mapOf(
            "vpn_dns_block_ads_primary" to "176.103.130.130",
            "vpn_dns_block_ads_alternative" to "176.103.130.131",
            "vpn_detail_open_ads_interval" to "30",
            "vpn_alternative_api" to "https://www.vpngate.net",
            "vpn_udp_api" to "https://www.vpngate.net/api/iphone/",
            "vpn_check_ip_url" to "https://whatismyipaddress.com/",
            "vpn_paid_server_api" to "https://www.vpngate.net/api/",
            "vpn_import_open_vpn" to false,
            "vpn_header_session_name" to "vpn_header_session_name",
            "vpn_paid_skus" to
                "[\"vn.unlimit.vpngate.2gb\",\"vn.unlimit.vpngate.5gb\",\"vn.unlimit.vpngate.10gb\",\"vn.unlimit.vpngate.15gb\"]",
            "vpn_paid_skus_pro_ver" to
                "[\"vn.unlimit.vpngatepro.2gb\",\"vn.unlimit.vpngatepro.5gb\",\"vn.unlimit.vpngatepro.10gb\",\"vn.unlimit.vpngatepro.15gb\"]",
            "invite_paid_server" to true,
            // Ads have been removed entirely from this build.
            "vpn_show_native_ad" to false
        )
    }
}

/** Stand-in for `FirebaseRemoteConfigSettings` (only used via its Builder for a fetch interval). */
class LocalRemoteConfigSettings private constructor() {
    class Builder {
        fun setMinimumFetchIntervalInSeconds(@Suppress("UNUSED_PARAMETER") seconds: Long): Builder = this
        fun build(): LocalRemoteConfigSettings = LocalRemoteConfigSettings()
    }
}
