# Rewind for Android

An Android port of [Rewind](https://github.com/chizberg/Rewind) — an app for exploring retro photos
and paintings on a map, built on the [PastVu](https://pastvu.com) archive. The iOS app is the
reference: same features, same reducers, same quirks, rebuilt on Compose and Google Maps.

**This port is vibe-coded from end to end.** The Kotlin here was written by Claude Code, milestone
by milestone, against the iOS sources. I directed, reviewed and tested it — I did not type it.

### Features

Vintage photos and paintings on the map, coloured by year · favorites · a photo/painting filter and
a year-range selector · place search · comparing a place today against its past, through the camera
or Google Street View · sharing and saving.

Descriptions are shown as they come; translation is the one feature from the iOS app that has not
been ported yet.

### PastVu

All photos and paintings come from the PastVu API. PastVu is open source —
[PastVu/pastvu](https://github.com/PastVu/pastvu) — and it has its own
[rules](https://docs.pastvu.com/en/rules), worth a read.

### Stack

Compose + Material 3 · Google Maps SDK & Places · CameraX · Coil · OkHttp + kotlinx.serialization ·
DataStore · a TCA-inspired `Reducer` mirrored from the iOS app, with the manual composition root
instead of a DI framework.

Android 12+ (minSdk 31).

### Setup

1. Get two Google API keys: one for the Maps SDK for Android + Places (New), one for the REST APIs
   (Street View metadata).
2. Put them in `local.properties` (gitignored) as `MAPS_API_KEY` and `GOOGLE_REST_API_KEY` — see
   `secrets.defaults.properties` for the placeholders the project builds with otherwise.
3. Build and run.
