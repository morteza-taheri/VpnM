package vn.unlimit.vpngate.utils

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Existing installations must not lose mirrors when the registry is introduced. */
class MirrorRegistryMigrationTest {

    private class FakePrefs : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(values)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private class Editor(
            private val values: MutableMap<String, Any?>,
        ) : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                pending[key] = values
                return this
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                pending.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                return this
            }
            override fun commit(): Boolean {
                values.putAll(pending)
                return true
            }
            override fun apply() {
                values.putAll(pending)
            }
        }
    }

    @Test
    fun `legacy cached mirrors are imported and invalid entries rejected`() {
        val prefs = FakePrefs()
        prefs.edit().putString(
            "KEY_CACHED_MIRRORS",
            "[\"http://8.8.4.4:8080/en/\",\"http://127.0.0.1:9000/\",\"not a url\"]",
        ).apply()

        val util = DataUtil(prefs)
        val bases = util.getUnifiedMirrorBases()

        assertEquals(listOf("http://8.8.4.4:8080"), bases)
        assertFalse("a private legacy entry must not be trusted", bases.any { "127.0.0.1" in it })
        assertTrue(prefs.getString(DataUtil.KEY_MIRROR_REGISTRY, null)!!.contains("8.8.4.4"))
    }

    @Test
    fun `newly discovered mirrors join the same unified registry`() {
        val prefs = FakePrefs()
        val util = DataUtil(prefs)
        val legacy = vn.unlimit.vpngate.data.remote.MirrorRegistryEntry(
            publishedUrl = "http://8.8.4.4:8080/en/",
            baseUrl = "http://8.8.4.4:8080",
            publishedPath = "/en/",
        )
        util.setMirrorRegistry(listOf(legacy))

        val discovered = vn.unlimit.vpngate.data.remote.MirrorRegistryEntry(
            publishedUrl = "http://mirror-review.opengw.net:7777/en/",
            baseUrl = "http://mirror-review.opengw.net:7777",
            publishedPath = "/en/",
        )
        util.setMirrorRegistry(
            vn.unlimit.vpngate.data.remote.MirrorRegistryStore.merge(
                util.getMirrorRegistry(),
                listOf(discovered),
                System.currentTimeMillis(),
            ),
        )

        val bases = util.getUnifiedMirrorBases()
        assertTrue(bases.contains("http://8.8.4.4:8080"))
        assertTrue(bases.contains("http://mirror-review.opengw.net:7777"))
    }
}
