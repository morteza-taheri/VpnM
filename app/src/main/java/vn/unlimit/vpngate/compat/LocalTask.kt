package vn.unlimit.vpngate.compat

/**
 * Minimal, synchronous stand-in for `com.google.android.gms.tasks.Task`.
 *
 * The original code called Firebase/Play Services APIs that returned an async `Task<T>` and
 * then chained `.addOnCompleteListener { task -> ... }`. Since those Google libraries are no
 * longer part of this build, this class provides the same shape but resolves immediately and
 * synchronously (there is no network round-trip anymore, so there is nothing to wait for).
 */
class LocalTask<T> internal constructor(
    val isSuccessful: Boolean,
    val result: T,
    val exception: Exception? = null
) {
    fun addOnCompleteListener(listener: (LocalTask<T>) -> Unit): LocalTask<T> {
        listener(this)
        return this
    }
}
