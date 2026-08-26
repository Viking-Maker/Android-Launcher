package com.vm.nornir.launcher.usage

import android.content.ComponentName
import android.content.Context
import android.os.UserHandle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Real [NornirUsageStore] over Preferences [DataStore] (ADR-0006 D6, issue #15).
 *
 * Storage shape: one `Preferences` DataStore whose keys are
 * `"usage/" + component.flattenToString() + "#" + user` — the ADR-0006 D6 key string with
 * `user.serialize()` (an `@hide` API, not callable from app code) realized as
 * [UserHandle.toString], which renders the unique per-user handle id (`UserHandle{10}`).
 * Including `user` in the key makes multi-profile correct by construction: the same
 * component in two profiles gets independent records.
 *
 * Values are the two aggregate counters serialized as `"count;lastLaunchTimestamp"` —
 * a stable, dependency-free text encoding of [UsageRecord] (Preferences has no built-in
 * data-class values, and a Proto schema is out of proportion for two scalars).
 *
 * Threading: [edit] is a `suspend fun`, but the launch flow calls this seam synchronously
 * (write point: `LauncherViewModel.handle(Launch)`, success-conditional — #31). The bridge runs on a private
 * single-thread dispatcher so callers never block on the app's main/default dispatchers,
 * and DataStore's writer lock is contended only against itself.
 */
class DataStoreNornirUsageStore(
    private val dataStore: DataStore<Preferences>,
) : NornirUsageStore {

    private val ioDispatcherDelegate = lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "nornir-usage-store") }.asCoroutineDispatcher()
    }
    private val ioDispatcher get() = ioDispatcherDelegate.value

    override fun recordLaunch(component: ComponentName, user: UserHandle, nowMillis: Long) {
        val key = usageKey(component, user)
        runBlocking(ioDispatcher) {
            dataStore.edit { prefs ->
                val current = prefs[key]?.let(::decodeRecord) ?: UsageRecord()
                prefs[key] = encodeRecord(current.plusLaunch(nowMillis))
            }
        }
    }

    override fun usageFor(component: ComponentName, user: UserHandle): UsageRecord {
        val key = usageKey(component, user)
        return runBlocking(ioDispatcher) {
            // .first() reads one consistent snapshot; no flow collection, no leak.
            prefs(dataStore)[key]?.let(::decodeRecord) ?: UsageRecord()
        }
    }

    override fun records(): Flow<Map<ComponentName, UsageRecord>> =
        dataStore.data.map { prefs ->
            val folded = mutableMapOf<ComponentName, UsageRecord>()
            for ((key, value) in prefs.asMap()) {
                val component = decodeUsageKeyName(key.name) ?: continue
                val record = (value as? String)?.let(::decodeRecord) ?: continue
                if (!record.hasLaunches) continue
                // Same component in two profiles: fold to the stronger evidence (max count,
                // then latest timestamp). The grid ranks components (ADR-0006 D3/D5).
                val existing = folded[component]
                if (existing == null ||
                    record.launchCount > existing.launchCount ||
                    (record.launchCount == existing.launchCount &&
                        record.lastLaunchTimestamp > existing.lastLaunchTimestamp)
                ) {
                    folded[component] = record
                }
            }
            folded
        }

    private suspend fun prefs(dataStore: DataStore<Preferences>): Preferences = dataStore.data.first()

    /** Release the store's private IO thread (tests and teardown). */
    fun close() {
        if (ioDispatcherDelegate.isInitialized()) ioDispatcher.close()
    }

    companion object {
        /** Key prefix namespaces usage entries away from any other DataStore user.
         *  Public so the read-only [UsageBackedFrequentSource] can select the same namespace. */
        const val PREFIX_PUBLIC = "usage/"

        /**
         * The ADR-0006 D6 identity key name: `usage/<component>#<user>`. Public so fakes
         * and tests construct identical keys (and future reconcile/prune can reuse it).
         */
        fun usageKeyName(component: ComponentName, user: UserHandle) =
            "$PREFIX_PUBLIC${component.flattenToString()}#$user"

        private fun usageKey(component: ComponentName, user: UserHandle) =
            stringPreferencesKey(usageKeyName(component, user))

        /**
         * The value encoding: `"launchCount;lastLaunchTimestamp"`. Public so tests can
         * assert on (or seed) the exact stored form.
         */
        fun encodeRecord(record: UsageRecord) = "${record.launchCount};${record.lastLaunchTimestamp}"

        /**
         * Inverse of [usageKeyName]'s naming: `"usage/<flattened>#<user>"` gives the component.
         * Null for keys outside the usage namespace or with a malformed component part.
         * Public so the read-only FrequentSource path can reuse it.
         */
        fun decodeUsageKeyName(keyName: String): ComponentName? {
            if (!keyName.startsWith(PREFIX_PUBLIC)) return null
            val body = keyName.removePrefix(PREFIX_PUBLIC)
            val sep = body.lastIndexOf('#')
            if (sep <= 0) return null
            return ComponentName.unflattenFromString(body.substring(0, sep))
        }

        /** Inverse of [encodeRecord]; a malformed value decodes to an empty record. */
        fun decodeRecord(value: String): UsageRecord = runCatching {
            val parts = value.split(';')
            UsageRecord(launchCount = parts[0].toInt(), lastLaunchTimestamp = parts[1].toLong())
        }.getOrDefault(UsageRecord())
    }
}
