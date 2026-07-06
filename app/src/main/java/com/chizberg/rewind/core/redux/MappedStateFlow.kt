package com.chizberg.rewind.core.redux

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * A [StateFlow] view over [source] whose value is [transform] applied synchronously.
 *
 * Mirrors VGSL's synchronous `ObservableVariable.map`: [value] always reflects the current source
 * value, and collectors receive mapped updates. Used by [ViewStore.bimap].
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class MappedStateFlow<T, R>(private val source: StateFlow<T>, private val transform: (T) -> R) :
    StateFlow<R> {
    override val value: R get() = transform(source.value)
    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.collect { collector.emit(transform(it)) }
    }
}
