package com.vm.nornir.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vm.nornir.launcher.catalog.AppRepository
import com.vm.nornir.launcher.catalog.RealAppRepository
import com.vm.nornir.launcher.favorites.DataStoreFavoritesSource
import com.vm.nornir.launcher.favorites.FavoritesSource
import com.vm.nornir.launcher.icon.IconLoader
import com.vm.nornir.launcher.icon.LruIconCache
import com.vm.nornir.launcher.icon.RealIconLoader
import com.vm.nornir.launcher.launch.LauncherInvoker
import com.vm.nornir.launcher.launch.RealLauncherInvoker
import com.vm.nornir.launcher.ui.LauncherScreen
import com.vm.nornir.launcher.ui.LauncherViewModel
import com.vm.nornir.launcher.usage.DataStoreNornirUsageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.vm.nornir.launcher.usage.FrequentSource
import com.vm.nornir.launcher.usage.NornirUsageStore
import com.vm.nornir.launcher.usage.UsageBackedFrequentSource

/**
 * Home screen entry point. Registered as the device default-home via
 * MAIN + HOME + DEFAULT (see AndroidManifest). Hosts the floating launcher card:
 * the activity builds the real seam graph (#17/#16/#15/#14) once, hands it to the
 * activity-scoped [LauncherViewModel] (ADR-0004 §1), and renders [LauncherScreen]
 * from `uiState`. Emulator end-to-end verification is #20; this activity owns
 * composition and the seam construction.
 */
class MainActivity : ComponentActivity() {

    /**
     * One shared Preferences DataStore for both persistence seams (usage + favorites).
     * A single file keeps the two seams' writes on one DataStore writer lock; the
     * delegate property survives configuration changes, so the stores are per-process.
     */
    private val prefsDataStore by preferencesDataStore(name = "nornir")

    private val appRepository: AppRepository by lazy { RealAppRepository(this) }

    private val favoritesSource: FavoritesSource by lazy { DataStoreFavoritesSource(prefsDataStore) }

    private val invoker: LauncherInvoker by lazy { RealLauncherInvoker(this) }

    private val usageStore: NornirUsageStore by lazy { DataStoreNornirUsageStore(prefsDataStore) }

    /**
     * The frequent-first read seam (ADR-0006 D6): a derived view over [usageStore] +
     * [appRepository], kept hot for the process lifetime so recomputes survive rotation.
     */
    private val frequentSource: FrequentSource by lazy {
        UsageBackedFrequentSource(
            apps = appRepository,
            usage = usageStore,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    /** The icon seam: cross-APK fetch behind the density-keyed LRU cache (#16). */
    private val iconLoader: IconLoader by lazy { LruIconCache(RealIconLoader(this)) }

    private val viewModel: LauncherViewModel by viewModels {
        LauncherViewModel.factory(appRepository, favoritesSource, frequentSource, invoker, usageStore)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // stateNotNeeded=true: the UI always rebuilds from repo.apps via the StateFlow —
        // never from savedInstanceState (plan.md Further Notes).
        setContent {
            NornirTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                LauncherScreen(
                    state = state,
                    onEvent = viewModel::handle,
                    iconLoader = iconLoader,
                    densityDpi = resources.displayMetrics.densityDpi,
                )
            }
        }
    }
}
