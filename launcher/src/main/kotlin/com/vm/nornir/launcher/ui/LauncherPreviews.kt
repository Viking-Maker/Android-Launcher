package com.vm.nornir.launcher.ui

import android.content.ComponentName
import android.graphics.drawable.ColorDrawable
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.vm.nornir.launcher.NornirTheme
import com.vm.nornir.launcher.icon.IconLoader
import com.vm.nornir.launcher.model.AppItem

/**
 * A preview-local [IconLoader] stand-in: serves a flat placeholder drawable for every
 * identity, so previews never touch the real cross-APK seam. (The recording test fake
 * lives in the test sourceset; previews compile against main.)
 */
private object PlaceholderIconLoader : IconLoader {
    override fun get(component: ComponentName, user: android.os.UserHandle, density: Int) = ColorDrawable(0xFF252538.toInt())
}

/**
 * Deterministic fake [LauncherUiState]s for previews and screenshot-style tests (#19).
 *
 * The catalog mirrors the primary-seam fixtures (#18's tests): three apps across three
 * categories, one focused row, a live query — everything the card renders at once.
 * Previews stay pure: the same immutable snapshot, the recording [FakeIconLoader].
 */
class LauncherUiStateProvider : PreviewParameterProvider<LauncherUiState> {

    private fun app(id: String, label: String, platformCategory: Int?) =
        AppItem(ComponentName("com.example.$id", "com.example.$id.MainActivity"), Process.myUserHandle(), label, platformCategory)

    override val values = sequenceOf(
        // Populated: chips (Favorites hidden — no pins), 3 results, focus on row 0.
        LauncherUiState(
            query = "",
            filter = FilterMode.All,
            results = listOf(
                app("steam", "Steam", 0),
                app("spotify", "Spotify", 1),
                app("vscode", "VS Code", 7),
            ),
            availableCategories = listOf(
                com.vm.nornir.launcher.model.NornirCategory.GAME,
                com.vm.nornir.launcher.model.NornirCategory.MULTIMEDIA,
                com.vm.nornir.launcher.model.NornirCategory.PRODUCTIVITY,
            ),
            focusedIndex = 0,
            hasFavorites = false,
        ),
        // Query narrowed + Favorites active: 1 result, footer shows "1 result"-style count.
        LauncherUiState(
            query = "spot",
            filter = FilterMode.Category(com.vm.nornir.launcher.model.NornirCategory.MULTIMEDIA),
            results = listOf(app("spotify", "Spotify", 1)),
            availableCategories = listOf(com.vm.nornir.launcher.model.NornirCategory.MULTIMEDIA),
            focusedIndex = 0,
            hasFavorites = true,
        ),
        // Empty grid: footer reads 0, list collapses.
        LauncherUiState(query = "zzz", results = emptyList(), availableCategories = emptyList()),
    )
}

/** Card over the wallpaper in the app theme — the design-review surface. */
@Preview(name = "Launcher — populated", showBackground = true, widthDp = 412, heightDp = 720)
@Composable
private fun LauncherScreenPreview(@PreviewParameter(LauncherUiStateProvider::class) state: LauncherUiState) {
    NornirTheme {
        LauncherScreen(state = state, onEvent = {}, iconLoader = PlaceholderIconLoader)
    }
}

/** Isolated leaf previews for the row states (mint focus vs resting surface). */
@Preview(name = "AppCard — focused", showBackground = true)
@Composable
private fun AppCardFocusedPreview() {
    val item = AppItem(
        ComponentName("com.example.spotify", "com.example.spotify.MainActivity"),
        Process.myUserHandle(),
        "Spotify",
        1,
    )
    NornirTheme {
        AppCard(item = item, isFocused = true, iconLoader = PlaceholderIconLoader, onClick = {})
    }
}
