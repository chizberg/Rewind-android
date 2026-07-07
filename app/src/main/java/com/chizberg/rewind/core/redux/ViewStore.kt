package com.chizberg.rewind.core.redux

import kotlinx.coroutines.flow.StateFlow

/**
 * Read/dispatch facade over a [Reducer] for the UI layer. Mirrors iOS `ViewStore`.
 *
 * iOS exposes state via `@dynamicMemberLookup`; here read through [current] (`store.current.foo`)
 * or collect [state] in Compose with `collectAsStateWithLifecycle()`.
 */
class ViewStore<State, Action>(
    val state: StateFlow<State>,
    private val actionPerformer: (Action) -> Unit,
) {
    constructor(reducer: Reducer<State, Action>) : this(reducer.state, reducer::invoke)

    /** Current state snapshot. */
    val current: State get() = state.value

    operator fun invoke(action: Action) = actionPerformer(action)

    /** iOS `bimap`: project [state] and lift actions into a child store. */
    fun <NewState, NewAction> bimap(
        state: (State) -> NewState,
        action: (NewAction) -> Action,
    ): ViewStore<NewState, NewAction> =
        ViewStore(
            state = MappedStateFlow(this.state, state),
            actionPerformer = { newAction -> this.invoke(action(newAction)) },
        )
}

/** iOS `Reducer.viewStore`. */
val <State, Action> Reducer<State, Action>.viewStore: ViewStore<State, Action>
    get() = ViewStore(this)
