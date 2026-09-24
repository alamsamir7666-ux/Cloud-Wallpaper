package com.cloudimage.core.network

/**
 * Failure taxonomy for every network call in the app.
 *
 * A sealed hierarchy (a closed set the compiler knows exhaustively) instead of
 * raw exceptions: callers get compile-time safety when branching on failure
 * kinds, and no accidental `catch (Throwable)` can swallow bugs.
 */
sealed interface NetworkError {
    /** The server answered, but with a non-2xx status code. */
    data class Http(val code: Int, val url: String) : NetworkError

    /** The call exceeded its configured timeouts. */
    data object Timeout : NetworkError

    /** Connectivity problems: DNS, refused connections, SSL, IO... */
    data class Io(val cause: java.io.IOException) : NetworkError

    /** The body arrived but is not the JSON shape we expected. */
    data class Serialization(val cause: Exception) : NetworkError

    /**
     * The wallpaper source behind the call failed for its own reasons — it
     * crashed, its package is broken, or no loadable source exists.
     *
     * This is NOT a connectivity problem: the transport layer never failed.
     * Mapping source failures onto [Io] is what made the v1.0.0 release
     * tell users to "check your connection" while their internet was fine.
     */
    data class Source(val reason: String) : NetworkError
}

/**
 * Result wrapper for network calls. A failing source degrades to a
 * [NetworkResult.Failure] the UI can render, instead of an exception that
 * takes the feed down.
 */
sealed interface NetworkResult<out T> {
    data class Success<T>(val value: T) : NetworkResult<T>

    data class Failure(val error: NetworkError) : NetworkResult<Nothing>
}

/** Maps [NetworkResult.Success] while passing [NetworkResult.Failure] through. */
inline fun <T, R> NetworkResult<T>.map(transform: (T) -> R): NetworkResult<R> =
    when (this) {
        is NetworkResult.Success -> NetworkResult.Success(transform(value))
        is NetworkResult.Failure -> this
    }

/** Runs [block] only on success; returns the original result for chaining. */
inline fun <T> NetworkResult<T>.onSuccess(block: (T) -> Unit): NetworkResult<T> {
    if (this is NetworkResult.Success) block(value)
    return this
}

/** Runs [block] only on failure; returns the original result for chaining. */
inline fun <T> NetworkResult<T>.onFailure(block: (NetworkError) -> Unit): NetworkResult<T> {
    if (this is NetworkResult.Failure) block(error)
    return this
}
