package com.chizberg.rewind.network

/** Port of iOS `NetworkError`. */
sealed class NetworkError(
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    data object InvalidUrl : NetworkError("invalid URL")

    class ConnectionFailure(
        cause: Throwable,
    ) : NetworkError(cause = cause)

    class InvalidCode(
        val code: Int,
    ) : NetworkError("invalid status code: $code")

    class ParsingFailure(
        cause: Throwable? = null,
        val desc: String? = null,
    ) : NetworkError(desc, cause)
}
