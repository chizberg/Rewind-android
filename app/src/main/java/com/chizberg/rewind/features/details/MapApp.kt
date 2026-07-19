package com.chizberg.rewind.features.details

/**
 * External map apps the "find route" menu can hand a destination to. Port of iOS `MapApp`.
 * `Apple` is kept for parity; on Android its link opens Apple Maps on the web (whether to offer it
 * is the route menu's call — [coordinateLink] just builds the URL).
 */
enum class MapApp {
    Apple,
    Google,
    Yandex,
}

/**
 * A "route to this point" deep link for [this] app. Port of iOS `MapApp.coordinateLink`. iOS
 * returns an optional (its `URL(string:)` can fail); the same interpolations never fail to parse,
 * so this returns a plain [String] and the caller gates on `canOpenUrl` alone.
 */
fun MapApp.coordinateLink(
    latitude: Double,
    longitude: Double,
): String =
    when (this) {
        MapApp.Apple -> "http://maps.apple.com/?daddr=$latitude,$longitude"
        MapApp.Google -> "https://www.google.com/maps/dir//$latitude,$longitude"
        MapApp.Yandex ->
            "https://yandex.com/maps/?mode=routes&rtext=~$latitude,$longitude&rtt=auto"
    }
