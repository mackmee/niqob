package com.niqob.app.domain.usecase

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.ConsumedOverrideResult
import com.niqob.app.domain.repository.RemoteOverrideRepository

/**
 * Use case mediating the consumption of an approved remote screen-time override.
 *
 * Validates identifiers, invokes the server-authoritative consume RPC, and returns
 * the verified [ConsumedOverrideResult] preserving server timestamps.
 *
 * Server-authoritative:
 * - Invoked by child ViewModel upon parent approval
 * - Atomically consumes the approved request on Supabase
 * - Only server-calculated timestamps and approved duration are returned
 */
open class ConsumeRemoteOverrideUseCase(
    private val repository: RemoteOverrideRepository
) {
    open suspend operator fun invoke(
        deviceId: String,
        requestId: String
    ): Result<ConsumedOverrideResult> {
        return invoke(deviceId, requestId, null)
    }

    open suspend operator fun invoke(
        deviceId: String,
        requestId: String,
        deviceSecret: String?
    ): Result<ConsumedOverrideResult> {
        if (deviceId.isBlank()) {
            return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
        }
        if (requestId.isBlank()) {
            return Result.Error(IllegalArgumentException("Request ID cannot be blank."))
        }

        return when (val result = repository.consumeOverride(deviceId.trim(), requestId.trim(), deviceSecret)) {
            is Result.Success -> {
                val consumed = result.data
                if (consumed.approvedDurationMinutes <= 0) {
                    Result.Error(IllegalStateException("Invalid approved duration received from server: ${consumed.approvedDurationMinutes}"))
                } else {
                    Result.Success(consumed)
                }
            }
            is Result.Error -> result
            is Result.Loading -> result
        }
    }
}
