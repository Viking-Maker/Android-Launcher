package com.vm.nornir.launcher.usage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import java.io.File

/**
 * Test fakes for the persistence seams (issue #15).
 *
 * Per issue #11's primary-seam strategy and #15's acceptance criteria ("fakes run over an
 * in-memory DataStore"), each fake is the **real seam implementation over an in-memory**
 * [PreferenceDataStoreFactory] `DataStore` — same code path as production (serialize, edit,
 * read-back), no real file system and no Android storage. The scope's standard test dispatchers
 * run on the test thread, so `runBlocking` inside the store bridges without deadlock; a unique
 * scratch file per instance keeps DataStore's file bookkeeping happy while remaining isolated.
 */
object FakePersistence {

    /**
     * A fresh Preferences [DataStore] isolated per call — two tests (or two seams in one
     * test) never share state. Storage lives on the test scope's virtual dispatchers.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun inMemoryPrefsDataStore(scope: TestScope): DataStore<Preferences> {
        val scratch = File.createTempFile("nornir-fake-datastore", ".preferences_pb")
        scratch.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { scratch },
        )
    }
}
