package com.vm.nornir.launcher.ui

import android.content.ComponentName
import android.os.Process
import android.os.UserHandle
import app.cash.turbine.test
import com.vm.nornir.launcher.catalog.FakeAppRepository
import com.vm.nornir.launcher.favorites.FakeFavoritesSource
import com.vm.nornir.launcher.launch.FakeLauncherInvoker
import com.vm.nornir.launcher.model.AppItem
import com.vm.nornir.launcher.model.NornirCategory
import com.vm.nornir.launcher.usage.FakeNornirUsageStore
import com.vm.nornir.launcher.usage.FakePersistence
import com.vm.nornir.launcher.usage.UsageRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Primary seam tests for [LauncherViewModel] (issue #18, ADR-0004 §1/§3/§8).
 *
 * Per issue #11's primary-seam strategy: the real fakes ([FakeAppRepository],
 * [FakeFavoritesSource], [FakeLauncherInvoker], [FakeNornirUsageStore]) drive the identical
 * production combine pipeline, and every acceptance assertion reads the published seam —
 * [LauncherViewModel.uiState] via Turbine **and** its current `value` — never private internals.
 *
 * Covered acceptance criteria: filtering (label + category fuzzy match, Favorites union),
 * empty-category hiding via `availableCategories`, focus clamping to the live results range,
 * and launch wiring that records through the invoker **and** increments usage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelTest {

    /** [viewModelScope] rides `Dispatchers.Main`; pin it to the virtual scheduler. */
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(mainDispatcher)
    private val usageStores = mutableListOf<FakeNornirUsageStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        usageStores.forEach { it.close() }
    }

    // ---- fixtures -----------------------------------------------------------

    private fun comp(id: String) = ComponentName("com.example.$id", "com.example.$id.MainActivity")

    /** Platform codes per CategoryMapping: 0=GAME 1=MULTIMEDIA 5=NEWS 6=MAPS 7=PRODUCTIVITY null=OTHER. */
    private fun item(
        id: String,
        label: String,
        platformCategory: Int?,
        user: UserHandle = Process.myUserHandle(),
    ) = AppItem(comp(id), user, label, platformCategory)

    private class Harness(
        val repo: FakeAppRepository,
        val favorites: FakeFavoritesSource,
        val launcher: FakeLauncherInvoker,
        val usage: FakeNornirUsageStore,
        val vm: LauncherViewModel,
    )

    private fun harness(apps: List<AppItem> = emptyList()): Harness {
        val repo = FakeAppRepository(apps)
        val favSource = FakeFavoritesSource(FakePersistence.inMemoryPrefsDataStore(scope))
        val invoker = FakeLauncherInvoker()
        val store = FakeNornirUsageStore(FakePersistence.inMemoryPrefsDataStore(scope))
        usageStores += store
        return Harness(repo, favSource, invoker, store, LauncherViewModel(repo, favSource, invoker, store))
    }

    /**
     * Assert the settled seam state. Subscribing through Turbine starts the shared
     * [StateFlow] pipeline (the `stateIn` upstream is `WhileSubscribed`, so it only runs
     * once observed); [extraSetup] fires the events, then the most recent emission — and
     * the live `uiState.value` — are both checked against [expected].
     */
    private suspend fun LauncherViewModel.assertState(
        expected: LauncherUiState,
        extraSetup: suspend () -> Unit = {},
    ) {
        uiState.test {
            extraSetup()
            assertEquals(expected, expectMostRecentItem())
            assertEquals(expected, uiState.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- initial state & combine wiring --------------------------------------

    @Test
    fun `catalog publishes into uiState - single app visible under All`() = runTest {
        val mail = item("mail", "Mail", null)
        val h = harness(apps = listOf(mail))
        h.vm.assertState(
            LauncherUiState(
                results = listOf(mail),
                availableCategories = listOf(NornirCategory.OTHER),
                focusedIndex = 0,
            ),
        )
    }

    @Test
    fun `empty catalog yields the default state`() = runTest {
        val h = harness()
        h.vm.assertState(LauncherUiState())
    }

    // ---- query filtering -----------------------------------------------------

    @Test
    fun `query filters by case-insensitive substring on the label`() = runTest {
        val vscode = item("vscode", "VS Code", 7)
        val brave = item("brave", "Brave", null)
        val h = harness(apps = listOf(vscode, brave))
        h.vm.assertState(
            LauncherUiState(
                query = "code",
                filter = FilterMode.All,
                results = listOf(vscode),
                availableCategories = listOf(NornirCategory.PRODUCTIVITY, NornirCategory.OTHER),
            ),
        ) { h.vm.handle(LauncherEvent.QueryChanged("code")) }
    }

    @Test
    fun `query matches category display name - games finds game apps`() = runTest {
        val steam = item("steam", "Steam Client", 0) // GAME
        val h = harness(apps = listOf(steam))
        h.vm.assertState(
            LauncherUiState(query = "games", results = listOf(steam), availableCategories = listOf(NornirCategory.GAME)),
        ) { h.vm.handle(LauncherEvent.QueryChanged("games")) }
    }

    @Test
    fun `fuzzy subsequence catches partial typing - brv finds Brave`() = runTest {
        val brave = item("brave", "Brave", null)
        val h = harness(apps = listOf(brave))
        h.vm.assertState(
            LauncherUiState(query = "brv", results = listOf(brave), availableCategories = listOf(NornirCategory.OTHER)),
        ) { h.vm.handle(LauncherEvent.QueryChanged("brv")) }
    }

    @Test
    fun `bounded edit distance catches single-char typos - sporify finds Spotify`() = runTest {
        val spotify = item("spotify", "Spotify", 1) // MULTIMEDIA
        val h = harness(apps = listOf(spotify))
        h.vm.assertState(
            LauncherUiState(
                query = "sporify",
                results = listOf(spotify),
                availableCategories = listOf(NornirCategory.MULTIMEDIA),
            ),
        ) { h.vm.handle(LauncherEvent.QueryChanged("sporify")) }
    }

    @Test
    fun `accent-insensitive matching - cafe finds Café`() = runTest {
        val cafe = item("cafe", "Café", null)
        val h = harness(apps = listOf(cafe))
        h.vm.assertState(
            LauncherUiState(query = "cafe", results = listOf(cafe), availableCategories = listOf(NornirCategory.OTHER)),
        ) { h.vm.handle(LauncherEvent.QueryChanged("cafe")) }
    }

    @Test
    fun `non-matching query empties results but keeps categories`() = runTest {
        val steam = item("steam", "Steam", 0)
        val h = harness(apps = listOf(steam))
        h.vm.assertState(
            LauncherUiState(query = "zzz", results = emptyList(), availableCategories = listOf(NornirCategory.GAME)),
        ) { h.vm.handle(LauncherEvent.QueryChanged("zzz")) }
    }

    // ---- FilterMode chips ----------------------------------------------------

    @Test
    fun `Favorites chip unions only pinned components`() = runTest {
        val mail = item("mail", "Mail", null)
        val maps = item("maps", "Maps", 6)
        val news = item("news", "News", 5)
        val h = harness(apps = listOf(mail, maps, news))
        h.vm.assertState(
            LauncherUiState(
                filter = FilterMode.Favorites,
                results = listOf(mail),
                availableCategories = listOf(NornirCategory.NEWS, NornirCategory.MAPS, NornirCategory.OTHER),
                hasFavorites = true,
            ),
        ) {
            h.favorites.addFavorite(mail.component)
            advanceUntilIdle()
            h.vm.handle(LauncherEvent.FilterSelected(FilterMode.Favorites))
        }
    }

    @Test
    fun `Favorites chip with no pins yields empty results and hides pin chip`() = runTest {
        val mail = item("mail", "Mail", null)
        val h = harness(apps = listOf(mail))
        h.vm.assertState(
            LauncherUiState(filter = FilterMode.Favorites, results = emptyList(), availableCategories = listOf(NornirCategory.OTHER)),
        ) { h.vm.handle(LauncherEvent.FilterSelected(FilterMode.Favorites)) }
    }

    @Test
    fun `Category chip filters to that category only`() = runTest {
        val steam = item("steam", "Steam", 0)
        val spotify = item("spotify", "Spotify", 1)
        val vscode = item("vscode", "VS Code", 7)
        val h = harness(apps = listOf(steam, spotify, vscode))
        h.vm.assertState(
            LauncherUiState(
                filter = FilterMode.Category(NornirCategory.MULTIMEDIA),
                results = listOf(spotify),
                availableCategories = listOf(NornirCategory.GAME, NornirCategory.MULTIMEDIA, NornirCategory.PRODUCTIVITY),
            ),
        ) { h.vm.handle(LauncherEvent.FilterSelected(FilterMode.Category(NornirCategory.MULTIMEDIA))) }
    }

    @Test
    fun `returning to All restores the full slice`() = runTest {
        val spotify = item("spotify", "Spotify", 1)
        val vscode = item("vscode", "VS Code", 7)
        val h = harness(apps = listOf(spotify, vscode))
        h.vm.assertState(
            LauncherUiState(results = listOf(spotify, vscode), availableCategories = listOf(NornirCategory.MULTIMEDIA, NornirCategory.PRODUCTIVITY)),
        ) {
            h.vm.handle(LauncherEvent.FilterSelected(FilterMode.Category(NornirCategory.PRODUCTIVITY)))
            h.vm.handle(LauncherEvent.FilterSelected(FilterMode.All))
        }
    }

    // ---- visibleCategories (empty hiding) ------------------------------------

    @Test
    fun `visibleCategories hides categories with no members - taxonomy order kept`() = runTest {
        val steam = item("steam", "Steam", 0)       // GAME
        val vscode = item("vscode", "VS Code", 7)   // PRODUCTIVITY
        val misc = item("misc", "Misc", null)       // OTHER
        val h = harness(apps = listOf(steam, vscode, misc))
        h.vm.assertState(
            LauncherUiState(
                results = listOf(steam, vscode, misc),
                availableCategories = listOf(NornirCategory.GAME, NornirCategory.PRODUCTIVITY, NornirCategory.OTHER),
            ),
        )
    }

    @Test
    fun `availableCategories recomputes when the catalog changes`() = runTest {
        val misc = item("misc", "Misc", null)
        val steam = item("steam", "Steam", 0)
        val h = harness(apps = listOf(misc))
        h.vm.uiState.test {
            h.repo.add(steam)
            scope.testScheduler.advanceUntilIdle()
            val s = expectMostRecentItem()
            assertEquals(listOf(NornirCategory.GAME, NornirCategory.OTHER), s.availableCategories)
            assertEquals(listOf(misc, steam), s.results)
            assertEquals(listOf(NornirCategory.GAME, NornirCategory.OTHER), h.vm.uiState.value.availableCategories)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- focus clamping --------------------------------------------------------

    @Test
    fun `focusedIndex clamps to live results range after results shrink`() = runTest {
        val alpha = item("a", "Alpha", null)
        val beta = item("b", "Beta", null)
        val gamma = item("c", "Gamma", null)
        val h = harness(apps = listOf(alpha, beta, gamma))
        h.vm.assertState(
            LauncherUiState(
                query = "Alp",
                results = listOf(alpha),
                availableCategories = listOf(NornirCategory.OTHER),
                focusedIndex = 0,
            ),
        ) {
            h.vm.handle(LauncherEvent.MoveFocus(FocusDir.DOWN))
            h.vm.handle(LauncherEvent.MoveFocus(FocusDir.DOWN)) // raw index 2 of 3 rows
            h.vm.handle(LauncherEvent.QueryChanged("Alp")) // 1 row -> clamp to 0
        }
    }

    @Test
    fun `MoveFocus UP at top stays clamped at zero`() = runTest {
        val alpha = item("a", "Alpha", null)
        val h = harness(apps = listOf(alpha))
        h.vm.assertState(
            LauncherUiState(results = listOf(alpha), availableCategories = listOf(NornirCategory.OTHER), focusedIndex = 0),
        ) { h.vm.handle(LauncherEvent.MoveFocus(FocusDir.UP)) }
    }

    @Test
    fun `MoveFocus DOWN past end clamps to lastIndex`() = runTest {
        val alpha = item("a", "Alpha", null)
        val beta = item("b", "Beta", null)
        val h = harness(apps = listOf(alpha, beta))
        h.vm.assertState(
            LauncherUiState(results = listOf(alpha, beta), availableCategories = listOf(NornirCategory.OTHER), focusedIndex = 1),
        ) { repeat(5) { h.vm.handle(LauncherEvent.MoveFocus(FocusDir.DOWN)) } }
    }

    @Test
    fun `focus survives empty results without going negative`() = runTest {
        val alpha = item("a", "Alpha", null)
        val h = harness(apps = listOf(alpha))
        h.vm.assertState(
            LauncherUiState(query = "zzz", results = emptyList(), availableCategories = listOf(NornirCategory.OTHER), focusedIndex = 0),
        ) {
            h.vm.handle(LauncherEvent.MoveFocus(FocusDir.DOWN))
            h.vm.handle(LauncherEvent.QueryChanged("zzz"))
        }
    }

    @Test
    fun `MoveFocus with no live collector clamps to zero - WhileSubscribed has no cached results`() = runTest {
        // ADR-0004 §2: uiState is WhileSubscribed(5000), so before any subscription the
        // combine has not run and `results` is empty. A keyboard move in that window must
        // clamp to 0 — never throw or go negative (ADR-0004 §1 focus invariant).
        val alpha = item("a", "Alpha", null)
        val h = harness(apps = listOf(alpha))
        assertEquals(0, h.vm.uiState.value.focusedIndex) // no collector yet
        h.vm.handle(LauncherEvent.MoveFocus(FocusDir.DOWN)) // sizes from empty results
        h.vm.assertState(
            LauncherUiState(results = listOf(alpha), availableCategories = listOf(NornirCategory.OTHER)),
        ) { scope.testScheduler.advanceUntilIdle() }
    }

    // ---- launch wiring ---------------------------------------------------------

    @Test
    fun `Launch delegates to invoker and increments usage once`() = runTest {
        val app = item("spotify", "Spotify", 1)
        val h = harness(apps = listOf(app))
        h.vm.uiState.test {
            scope.testScheduler.advanceUntilIdle()
            assertEquals(1, expectMostRecentItem().results.size) // grid shows the target
            val beforeLaunch = System.currentTimeMillis()
            h.vm.handle(LauncherEvent.Launch(app))
            assertTrue(h.launcher.wasLaunched(app))
            assertEquals(1, h.launcher.launchCount)
            assertEquals(app.user, h.launcher.lastLaunch?.user)
            val record = h.usage.usageFor(app.component, app.user)
            assertEquals(1, record.launchCount)
            // Timestamp is real (VM defaults nowMillis); pin it to the launch window.
            assertTrue(record.lastLaunchTimestamp >= beforeLaunch)
            assertTrue(record.hasLaunches)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Launch is a pure side effect - uiState unchanged`() = runTest {
        val app = item("mail", "Mail", null)
        val h = harness(apps = listOf(app))
        h.vm.uiState.test {
            scope.testScheduler.advanceUntilIdle()
            val before = expectMostRecentItem()
            h.vm.handle(LauncherEvent.Launch(app))
            scope.testScheduler.advanceUntilIdle()
            assertEquals(before, h.vm.uiState.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `launching a work-profile app records under that profile`() = runTest {
        val workUser = UserHandle.getUserHandleForUid(10 * 100_000 + 10_100)
        val workApp = AppItem(comp("docs"), workUser, "Docs", 7)
        val h = harness(apps = listOf(workApp))
        h.vm.uiState.test {
            scope.testScheduler.advanceUntilIdle()
            expectMostRecentItem()
            h.vm.handle(LauncherEvent.Launch(workApp))
            assertTrue(h.launcher.wasLaunched(workApp))
            assertEquals(workUser, h.launcher.lastLaunch?.user)
            assertEquals(1, h.usage.usageFor(workApp.component, workUser).launchCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- focused item ----------------------------------------------------------

    @Test
    fun `focusedItem returns the highlighted result and null when empty`() = runTest {
        val alpha = item("a", "Alpha", null)
        val brave = item("b", "Brave", null)
        val h = harness(apps = listOf(alpha, brave))
        h.vm.assertState(
            LauncherUiState(
                query = "alp",
                results = listOf(alpha),
                availableCategories = listOf(NornirCategory.OTHER),
                focusedIndex = 0,
            ),
        ) {
            h.vm.handle(LauncherEvent.QueryChanged("alp"))
        }
        assertEquals(alpha, h.vm.focusedItem())
    }

    @Test
    fun `focusedItem is null on an empty grid and never indexes out of bounds`() = runTest {
        val alpha = item("a", "Alpha", null)
        val h = harness(apps = listOf(alpha))
        h.vm.assertState(
            LauncherUiState(query = "zzz", availableCategories = listOf(NornirCategory.OTHER)),
        ) {
            h.vm.handle(LauncherEvent.QueryChanged("zzz"))
        }
        assertNull(h.vm.focusedItem())
    }
}
