package com.vm.nornir.launcher.model

/**
 * Pure translation of the platform `ApplicationInfo.category` [Int] to a
 * [NornirCategory]. This is the single mapping site (ADR-0003 §1): [AppItem.category]
 * delegates here.
 *
 * The function is deliberately free of any `android.*` dependency so it can be
 * exercised by plain JVM unit tests; the only API-gated branch ([ACCESSIBILITY],
 * API 31+) is passed in as [sdkInt] rather than read from [android.os.Build.VERSION].
 *
 * Constant values mirror `ApplicationInfo` (verified against android.jar API 36):
 *   GAME=0, AUDIO=1, VIDEO=2, IMAGE=3, SOCIAL=4, NEWS=5, MAPS=6,
 *   PRODUCTIVITY=7, ACCESSIBILITY=8, UNDEFINED=-1.
 *
 * Note on the GAME/UNDEFINED collision: `CATEGORY_GAME == 0` while
 * `CATEGORY_UNDEFINED == -1`. The repository layer stores an UNDEFINED app as
 * `null` (ADR-0003), so a non-null `0` here is an unambiguous GAME and must map
 * to [NornirCategory.GAME], never [NornirCategory.OTHER].
 *
 * @param platformCategory raw `ApplicationInfo.category` value (or any [Int] the
 *   caller obtained); `null` is NOT handled here — callers pass `null` as
 *   [android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED] (-1) before calling.
 * @param sdkInt the device/SDK API level, used to gate [NornirCategory.ACCESSIBILITY].
 */
fun mapPlatformToNornir(platformCategory: Int, sdkInt: Int = 30): NornirCategory = when (platformCategory) {
    // MULTIMEDIA folds the three media channels (ADR-0002).
    1, 2, 3 -> NornirCategory.MULTIMEDIA
    0 -> NornirCategory.GAME
    4 -> NornirCategory.SOCIAL
    5 -> NornirCategory.NEWS
    6 -> NornirCategory.MAPS
    7 -> NornirCategory.PRODUCTIVITY
    // ACCESSIBILITY is only meaningful on API 31+ (ADR-0002); below that it is
    // treated as an unknown value -> OTHER.
    8 -> if (sdkInt >= 31) NornirCategory.ACCESSIBILITY else NornirCategory.OTHER
    // UNDEFINED (-1) and any unknown value -> OTHER.
    else -> NornirCategory.OTHER
}
