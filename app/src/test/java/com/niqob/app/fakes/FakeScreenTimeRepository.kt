package com.niqob.app.fakes

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.ScreenTimeLimit
import com.niqob.app.domain.repository.ScreenTimeRepository

class FakeScreenTimeRepository(
    private val defaultFamilyId: String = "mock_family_1"
) : ScreenTimeRepository {

    private val limitsMap = mutableMapOf<String, ScreenTimeLimit>()
    var shouldFail: Boolean = false
    var failureMessage: String = "Simulated screen time repository error"

    override suspend fun getScreenTimeLimit(deviceId: String, deviceSecret: String?): Result<ScreenTimeLimit> {
        if (shouldFail) {
            return Result.Error(Exception(failureMessage))
        }

        val existing = limitsMap[deviceId]
        return if (existing != null) {
            Result.Success(existing)
        } else {
            val defaultLimit = ScreenTimeLimit(
                familyId = defaultFamilyId,
                deviceId = deviceId,
                dailyLimitMinutes = 120,
                enabled = true
            )
            Result.Success(defaultLimit)
        }
    }

    override suspend fun setScreenTimeLimit(
        deviceId: String,
        dailyLimitMinutes: Int,
        enabled: Boolean
    ): Result<ScreenTimeLimit> {
        if (shouldFail) {
            return Result.Error(Exception(failureMessage))
        }

        if (dailyLimitMinutes < 0 || dailyLimitMinutes > 1440) {
            return Result.Error(IllegalArgumentException("Daily limit must be between 0 and 1440 minutes."))
        }

        val now = System.currentTimeMillis()
        val existing = limitsMap[deviceId]
        val updated = ScreenTimeLimit(
            id = existing?.id ?: "limit_${System.nanoTime()}",
            familyId = existing?.familyId ?: defaultFamilyId,
            deviceId = deviceId,
            dailyLimitMinutes = dailyLimitMinutes,
            enabled = enabled,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        limitsMap[deviceId] = updated
        return Result.Success(updated)
    }

    fun setLimitDirectly(limit: ScreenTimeLimit) {
        limitsMap[limit.deviceId] = limit
    }

    fun getStoredLimit(deviceId: String): ScreenTimeLimit? = limitsMap[deviceId]

    fun clear() {
        limitsMap.clear()
        shouldFail = false
    }
}
