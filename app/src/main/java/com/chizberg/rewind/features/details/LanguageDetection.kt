package com.chizberg.rewind.features.details

/**
 * What language a piece of text is written in, and how sure we are. Port of iOS `DetectedLanguage`
 * (`Screens/ImageDetails/LanguageDetection.swift`).
 *
 * [languageCode] is whatever the recogniser calls the language — `NLLanguage.rawValue` on iOS
 * (`"en"`, `"zh-Hans"`), a BCP-47 tag from ML Kit here (`"en"`, `"zh-Latn"`). Neither side
 * normalises it before comparing it with the app's own language, so both compare the same way.
 */
data class DetectedLanguage(
    val languageCode: String,
    val confidence: Float,
)

/**
 * Tells the language a description is written in. Port of iOS's free `detectLanguage(_:)`, which
 * wraps `NLLanguageRecognizer`; here it is injected (the M12 `PlacesSuggestProvider` / M13.5
 * `LocationSource` shape) because the recogniser's Android counterpart is ML Kit — a platform
 * dependency this JVM-only feature must not import, and an asynchronous one on top (`Task`, hence
 * `suspend`; iOS answers inline). Single-method, so a test detector is a lambda.
 *
 * Null means "couldn't tell", iOS's `dominantLanguage == nil` branch: the reducer then offers the
 * translation rather than hiding it. Implementations may throw — the reducer treats a failure as
 * the same "couldn't tell".
 */
fun interface LanguageDetector {
    suspend fun detect(text: String): DetectedLanguage?
}
