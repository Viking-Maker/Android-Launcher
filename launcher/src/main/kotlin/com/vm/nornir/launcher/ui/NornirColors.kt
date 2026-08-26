package com.vm.nornir.launcher.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The exact spec palette from `launcher-UI.md` §2, pinned as named tokens (ADR-0004 §6).
 *
 * M3's `ColorScheme` roles mangle several of the spec's states — the mint focus fill, the
 * lavender active chip, and the copper search outline have no M3 equivalent — so they are
 * exposed here as named constants and surfaced to composables through [LocalNornirColors].
 * Every value is a verbatim hex from the spec; nothing is derived or tone-mapped.
 *
 * Window fill is `#161623` (§2 *Window Background*), per ADR-0004 §6's palette note: §1's
 * `#181825` is the same Catppuccin Mocha surface family; ADR-0007 pins `#161623`/`#252538`
 * pending on-device wallpaper contrast (flip is a one-constant cosmetic change).
 */
data class NornirColors(
    /** Container bg (§2 Window Background; ADR-0007 window-fill pin). */
    val window: Color = Color(0xFF161623),
    /** Card / chip background (§3 search field recess uses #12121C instead). */
    val surface: Color = Color(0xFF252538),
    /** Focused app-card fill; active text inverts onto it (§5). */
    val mint: Color = Color(0xFF72E5BE),
    /** Selected category pill (§4). */
    val lavender: Color = Color(0xFFD6A8FF),
    /** Search-bar border stroke (§3). */
    val copper: Color = Color(0xFFB47970),
    /** Search-bar recessed background (§3). */
    val searchBarBackground: Color = Color(0xFF12121C),
    /** Text on mint / lavender fills (§4, §5). */
    val textActive: Color = Color(0xFF11111B),
    /** Primary inactive text (§2). */
    val textPrimary: Color = Color(0xFFCDD6F4),
    /** Subtitle text (§5). */
    val textSubtitle: Color = Color(0xFF8A8AAB),
    /** Footer count text (§6). */
    val textFooter: Color = Color(0xFF6C7086),
)

/** Composition-local so stateless leaf composables read tokens without parameter drilling. */
val LocalNornirColors = staticCompositionLocalOf { NornirColors() }
