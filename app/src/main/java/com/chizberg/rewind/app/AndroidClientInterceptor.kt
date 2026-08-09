package com.chizberg.rewind.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.chizberg.rewind.network.TRANSLATION_API_HOST
import okhttp3.Interceptor
import okhttp3.Response
import java.security.MessageDigest

private const val PACKAGE_HEADER = "X-Android-Package"
private const val CERT_HEADER = "X-Android-Cert"

/**
 * Tells Cloud Translation which Android app is calling it.
 *
 * The REST key ships inside the APK, so it is restricted in the Cloud console to this package and
 * its signing certificates. Google checks that restriction by reading two headers — the ones below
 * — which its own client libraries (the Maps SDK, Places) attach on their own; plain OkHttp does
 * not, so an unadorned request arrives as an anonymous one and comes back
 * `403 Requests from this Android client application <empty> are blocked`.
 *
 * Only the translation host gets them. The Street View metadata endpoint on `maps.googleapis.com`
 * answers the very same key with no headers at all (verified against the live API) — that family
 * doesn't apply the app restriction, and a header it never asked for could only start failing.
 *
 * Both values come from the running build, never from a constant: a debug build presents its own
 * debug certificate, a Play release presents the certificate Google re-signed it with. A
 * hard-coded fingerprint would be wrong for at least one of them.
 */
class AndroidClientInterceptor(
    context: Context,
) : Interceptor {
    private val appContext = context.applicationContext

    // Reading the signature costs a PackageManager round trip, and the answer cannot change while
    // the process lives.
    private val identity: Pair<String, String>? by lazy {
        signingCertificateSha1()?.let { appContext.packageName to it }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != TRANSLATION_API_HOST) return chain.proceed(request)
        val (packageName, certificate) = identity ?: return chain.proceed(request)
        return chain.proceed(
            request
                .newBuilder()
                .header(PACKAGE_HEADER, packageName)
                .header(CERT_HEADER, certificate)
                .build(),
        )
    }

    /**
     * SHA-1 of the certificate this build is signed with, as uppercase hex **without separators**
     * — the colon-grouped form `keytool` prints is not recognised and fails the check as if the
     * app were a stranger.
     */
    private fun signingCertificateSha1(): String? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val packageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    appContext.packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, flags)
            }
        val signer =
            packageInfo.signingInfo
                ?.apkContentsSigners
                ?.firstOrNull()
                ?: return null
        return MessageDigest
            .getInstance("SHA-1")
            .digest(signer.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }
}
