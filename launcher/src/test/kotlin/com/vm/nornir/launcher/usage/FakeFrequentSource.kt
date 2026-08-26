package com.vm.nornir.launcher.usage

import android.content.ComponentName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Test fake for [FrequentSource] (issue #20): a plain mutable state the harness can seed
 * per test — the VM treats it as an opaque hot set, exactly like the real derived view.
 */
class FakeFrequentSource(initial: Set<ComponentName> = emptySet()) : FrequentSource {
    private val _frequent = MutableStateFlow(initial)
    override val frequent: StateFlow<Set<ComponentName>> = _frequent
    fun set(values: Set<ComponentName>) { _frequent.value = values }
}
