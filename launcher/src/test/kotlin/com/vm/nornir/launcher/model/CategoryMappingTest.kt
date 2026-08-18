package com.vm.nornir.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RED phase (issue #13): the model package and CategoryMapping do not exist yet.
 * These tests pin the ADR-0002/0003 mapping contract and should fail to compile/run
 * until AppItem.kt + CategoryMapping.kt are implemented.
 */
class CategoryMappingTest {

    // --- mapPlatformToNornir folds MULTIMEDIA (AUDIO|VIDEO|IMAGE) ---
    @Test fun audioFoldsToMultimedia() = assertEquals(NornirCategory.MULTIMEDIA, mapPlatformToNornir(1))
    @Test fun videoFoldsToMultimedia() = assertEquals(NornirCategory.MULTIMEDIA, mapPlatformToNornir(2))
    @Test fun imageFoldsToMultimedia() = assertEquals(NornirCategory.MULTIMEDIA, mapPlatformToNornir(3))

    // --- direct maps ---
    @Test fun gameMapsToGame() = assertEquals(NornirCategory.GAME, mapPlatformToNornir(0))
    @Test fun socialMapsToSocial() = assertEquals(NornirCategory.SOCIAL, mapPlatformToNornir(4))
    @Test fun newsMapsToNews() = assertEquals(NornirCategory.NEWS, mapPlatformToNornir(5))
    @Test fun mapsMapsToMaps() = assertEquals(NornirCategory.MAPS, mapPlatformToNornir(6))
    @Test fun productivityMapsToProductivity() = assertEquals(NornirCategory.PRODUCTIVITY, mapPlatformToNornir(7))

    // --- ACCESSIBILITY guarded behind API 31+ ---
    @Test fun accessibilityOnApi31Plus() = assertEquals(NornirCategory.ACCESSIBILITY, mapPlatformToNornir(8, sdkInt = 31))
    @Test fun accessibilityOnApi36() = assertEquals(NornirCategory.ACCESSIBILITY, mapPlatformToNornir(8, sdkInt = 36))
    @Test fun accessibilityBelowApi31FallsToOther() = assertEquals(NornirCategory.OTHER, mapPlatformToNornir(8, sdkInt = 30))
    @Test fun accessibilityAtApi30FallsToOther() = assertEquals(NornirCategory.OTHER, mapPlatformToNornir(8, sdkInt = 30))

    // --- UNDEFINED / unknown -> OTHER ---
    @Test fun undefinedFallsToOther() = assertEquals(NornirCategory.OTHER, mapPlatformToNornir(-1))
    @Test fun unknownPositiveFallsToOther() = assertEquals(NornirCategory.OTHER, mapPlatformToNornir(99))
    @Test fun unknownNegativeFallsToOther() = assertEquals(NornirCategory.OTHER, mapPlatformToNornir(-2))
}
