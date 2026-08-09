package com.chizberg.rewind.app

import com.chizberg.rewind.features.details.DetectedLanguage
import com.chizberg.rewind.features.details.LanguageDetector
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ML Kit's "no idea", the counterpart of a null `NLLanguageRecognizer.dominantLanguage`. */
private const val UNDETERMINED_LANGUAGE = "und"

/**
 * The real [LanguageDetector]: ML Kit's on-device language identification, Android's stand-in for
 * iOS `NLLanguageRecognizer` (`Screens/ImageDetails/LanguageDetection.swift`). The model travels
 * inside the app, so a description is classified offline and without a Play Services download.
 *
 * **`identifyPossibleLanguages`, not `identifyLanguage`.** The simple call answers with a tag alone
 * (or `"und"`), having already applied a confidence threshold of its own — there is no number left
 * to compare against the 0.9 the reducer ports from iOS. The plural call returns the hypotheses
 * with their confidences, i.e. exactly the `dominantLanguage` + `languageHypotheses` pair iOS
 * reads. `"und"` is its "no dominant language", which the reducer treats as iOS treats nil.
 *
 * One instance per graph (like [FusedLocationSource], unlike the per-screen Places provider — this
 * one holds no session). The client is built on the first description that needs classifying, not
 * at startup, and never closed: it lives as long as the process that may open another description.
 */
class MlKitLanguageDetector : LanguageDetector {
    private val client: LanguageIdentifier by lazy { LanguageIdentification.getClient() }

    override suspend fun detect(text: String): DetectedLanguage? =
        client
            .identifyPossibleLanguages(text)
            .await()
            .maxByOrNull { it.confidence }
            ?.takeIf { it.languageTag != UNDETERMINED_LANGUAGE }
            ?.let { DetectedLanguage(languageCode = it.languageTag, confidence = it.confidence) }
}

/**
 * Awaits a Play-services [Task] (the M12 Places wrapper, minus the cancellation token — ML Kit's
 * inference takes none, so an abandoned detection is merely ignored, not stopped).
 */
private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
