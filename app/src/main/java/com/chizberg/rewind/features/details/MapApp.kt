package com.chizberg.rewind.features.details

/**
 * External map apps the "find route" menu can hand a destination to. Port of iOS `MapApp`.
 *
 * Divergence: iOS also offers Apple Maps; there is no Apple Maps on Android, and its web link
 * (`maps.apple.com`) lands on a page that can't route, so the entry is dropped rather than shipped
 * as a dead option.
 */
enum class MapApp {
    Google,
    Yandex,
}

/**
 * A "route to this point" deep link for [this] app. Port of iOS `MapApp.coordinateLink`. iOS
 * returns an optional (its `URL(string:)` can fail); the same interpolations never fail to parse,
 * so this returns a plain [String] and the caller gates on `canOpenUrl` alone.
 *
 * Both links are `https` on purpose: each is an app link of the installed map app (it opens the app
 * directly) and degrades to the same route in the browser when the app isn't installed. Yandex uses
 * the `yandex.ru` host here rather than iOS's `yandex.com`: the Android app's app-link filter is
 * built around the regional hosts, and `.com` is the one most likely to fall through to the browser.
 */
fun MapApp.coordinateLink(
    latitude: Double,
    longitude: Double,
): String =
    when (this) {
        MapApp.Google -> "https://www.google.com/maps/dir//$latitude,$longitude"
        MapApp.Yandex ->
            "https://yandex.ru/maps/?mode=routes&rtext=~$latitude,$longitude&rtt=auto"
    }
