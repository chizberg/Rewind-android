package com.chizberg.rewind.core.redux

/**
 * A synchronous getter/setter pair over an externally-owned value. Mirrors VGSL's `Property<T>`
 * (`VGSLFundamentals/Property.swift`), used throughout the iOS reducer factories to inject
 * persistence as a plain dependency without the reducer knowing what backs it — e.g. iOS
 * `FavoritesModel`'s `storage: Property<[Model.Image]>` is, in production, a thin wrapper over
 * `UserDefaults`, but the reducer only ever sees a synchronous get/set.
 *
 * On Android the same shape decouples `FavoritesModel` (a synchronous `effect`, mirroring iOS 1:1)
 * from `JsonPreference`/DataStore, which is asynchronous underneath but exposes the same
 * synchronous `value` contract at this boundary.
 */
class Property<T>(
    private val getter: () -> T,
    private val setter: (T) -> Unit,
) {
    var value: T
        get() = getter()
        set(newValue) = setter(newValue)
}
