package com.vm.nornir.launcher.model

/**
 * Nornir's own category taxonomy for the filter bar.
 *
 * Per ADR-0002 this is a sealed, launcher-owned type — not a passthrough of the
 * mostly-empty platform [android.content.pm.ApplicationInfo.category] field.
 * [MULTIMEDIA] folds the platform AUDIO|VIDEO|IMAGE channels into one chip;
 * [OTHER] is the fallback for [android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED]
 * and any unknown value. [ACCESSIBILITY] is only meaningful on API 31+.
 *
 * [ALL] and [FAVORITES] are intentionally NOT members — they live on a separate
 * `FilterMode` axis (ADR-0002 §4).
 */
enum class NornirCategory {
    GAME,
    MULTIMEDIA,
    SOCIAL,
    NEWS,
    PRODUCTIVITY,
    MAPS,
    ACCESSIBILITY,
    OTHER,
}
