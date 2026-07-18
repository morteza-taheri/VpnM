package vn.unlimit.vpngate

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import androidx.room.Room
import de.blinkt.openvpn.core.OpenVPNService
import vn.unlimit.vpngate.activities.DetailActivity
import vn.unlimit.vpngate.activities.MainActivity
import vn.unlimit.vpngate.compat.LocalRemoteConfig as FirebaseRemoteConfig
import vn.unlimit.vpngate.compat.LocalTask as Task
import vn.unlimit.vpngate.db.AppDatabase
import vn.unlimit.vpngate.db.ExcludedAppDao
import vn.unlimit.vpngate.db.VPNGateItemDao
import vn.unlimit.vpngate.models.ExcludedApp
import vn.unlimit.vpngate.utils.DataUtil
import vn.unlimit.vpngate.utils.PaidServerUtil

class App : Application() {
    var dataUtil: DataUtil? = null
        private set
    @JvmField
    var paidServerUtil: PaidServerUtil? = null
    private lateinit var appDatabase: AppDatabase
    lateinit var vpnGateItemDao: VPNGateItemDao
    lateinit var excludedAppDao: ExcludedAppDao

    override fun onCreate() {
        super.onCreate()
        appDatabase = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "vpn_gate_connector")
            .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Migration from version 1 to 2: create excluded_apps table
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `excluded_apps` (" +
                                "`packageName` TEXT NOT NULL, " +
                                "`appName` TEXT NOT NULL, " +
                                "PRIMARY KEY(`packageName`))"
                    )
                }
            })
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries() // Allow main thread queries for VPN profile configuration
            .build()
        vpnGateItemDao = appDatabase.vpnGateItemDao()
        excludedAppDao = appDatabase.excludedAppDao()

        // Initialize default excluded apps
        initializeDefaultExcludedApps()
        // Crash reporting (Firebase Crashlytics) and ads (AdMob/AppOpenManager) have been
        // removed from this build - no Google service dependencies.
        instance = this
        dataUtil = DataUtil(this)
        // Make notification open DetailActivity
        OpenVPNService.setNotificationActivityClass(
            if (dataUtil!!.getIntSetting(
                    DataUtil.SETTING_STARTUP_SCREEN,
                    0
                ) == 0
            ) DetailActivity::class.java else MainActivity::class.java
        )
        paidServerUtil = PaidServerUtil(this)
        FirebaseRemoteConfig.getInstance().fetchAndActivate()
            .addOnCompleteListener { task: Task<Boolean> ->
                if (task.isSuccessful) {
                    val updated = task.result
                    Log.e(TAG, "RemoteConfigUpdated:$updated")
                    isImportToOpenVPN =
                        FirebaseRemoteConfig.getInstance().getBoolean("vpn_import_open_vpn")
                }
            }
    }

    private fun initializeDefaultExcludedApps() {
        // Add Android Auto as default excluded app
        val androidAuto = ExcludedApp(
            packageName = "com.google.android.projection.gearhead",
            appName = "Android Auto"
        )

        // Check if Android Auto is already added
        try {
            val existing = excludedAppDao.isAppExcluded(androidAuto.packageName)
            if (existing == 0) {
                // First time - add synchronously to ensure it's available immediately
                excludedAppDao.insertExcludedApp(androidAuto)
                Log.d(TAG, "Added Android Auto as default excluded app")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing default excluded apps", e)
            // Try to add on background thread as fallback
            Thread {
                try {
                    val existing = excludedAppDao.isAppExcluded(androidAuto.packageName)
                    if (existing == 0) {
                        excludedAppDao.insertExcludedApp(androidAuto)
                        Log.d(TAG, "Added Android Auto as default excluded app (fallback)")
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Error in fallback initialization", e2)
                }
            }.start()
        }
    }

    companion object {
        private const val TAG = "VpnGateApp"

        @JvmStatic
        var instance: App? = null
            private set
        var isImportToOpenVPN: Boolean = false
            private set

        fun getResourceString(resId: Int): String {
            return instance!!.getString(resId)
        }
        // Default VpnProfileCompat mode for openvpn2.4.x compatibility with softether vpn server
        const val VPN_PROFILE_COMPAT_MODE_24X = 20400
    }
}
