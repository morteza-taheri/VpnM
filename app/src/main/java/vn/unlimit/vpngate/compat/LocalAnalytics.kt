package vn.unlimit.vpngate.compat

import android.content.Context
import android.os.Bundle
import android.util.Log

/**
 * Drop-in, privacy-friendly replacement for `com.google.firebase.analytics.FirebaseAnalytics`.
 *
 * All Google/Firebase dependencies have been removed from this build. This object keeps the
 * same call shape (`FirebaseAnalytics.getInstance(context).logEvent(name, params)`) so the
 * original call sites did not need to be rewritten, but it never talks to any remote server -
 * it just writes a debug log line locally.
 *
 * See build.gradle / imports: this class is imported under the alias `FirebaseAnalytics`.
 */
class LocalAnalytics private constructor() {

    fun logEvent(name: String, params: Bundle?) {
        Log.d(TAG, "logEvent(local-only, not sent anywhere): $name ${params ?: Bundle()}")
    }

    object Param {
        const val SEARCH_TERM = "search_term"
    }

    object Event {
        const val SEARCH = "search"
    }

    companion object {
        private const val TAG = "LocalAnalytics"
        private val instance = LocalAnalytics()

        @JvmStatic
        fun getInstance(@Suppress("UNUSED_PARAMETER") context: Context): LocalAnalytics = instance
    }
}
