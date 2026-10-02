package com.niqob.app.core.common

/**
 * A generic sealed class representing the outcome of an operation.
 * Used across Domain and Data layers to handle success, loading, and error states uniformly.
 */
sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Error(val exception: Throwable, val message: String? = exception.message) : Result<Nothing>
    data object Loading : Result<Nothing>
}
