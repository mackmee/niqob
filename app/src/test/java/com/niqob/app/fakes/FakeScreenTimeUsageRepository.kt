package com.niqob.app.fakes

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.ScreenTimeDailyUsage
import com.niqob.app.domain.repository.ScreenTimeUsageRepository
import java.util.UUID

class FakeScreenTimeUsageRepository : ScreenTimeUsageRepository {

    private val storage = mutableMapOf<Pair<String, String>, ScreenTimeDailyUsage>()
    var shouldFail: Boolean = false
    var failureException: Exception = Exception("Simulated network failure")
    var lastUpsertedUsage: ScreenTimeDailyUsage? = null
    var upsertCount: Int = 0

    override suspend fun upsertDailyUsage(
        deviceId: String,
        usageDate: String,
        totalUsageMinutes: Int,
        dailyLimitMinutes: Int?,
        remainingMinutes: Int?,
        usagePercentage: Int?,
        isLimitReached: Boolean?
    ): Result<ScreenTimeDailyUsage> {
        if (shouldFail) {
            return Result.Error(failureException)
        }
        if (deviceId.isBlank()) {
            return Result.Error(IllegalArgumentException("Device ID cannot be blank."))
        }
        if (totalUsageMinutes < 0) {
            return Result.Error(IllegalArgumentException("Total usage minutes cannot be negative."))
        }

        val key = Pair(deviceId, usageDate)
        val existing = storage[key]
        val effectiveLimit = dailyLimitMinutes ?: existing?.dailyLimitMinutes ?: 120
        val effectiveRemaining = remainingMinutes ?: maxOf(0, effectiveLimit - totalUsageMinutes)
        val effectivePercentage = usagePercentage ?: if (effectiveLimit > 0) {
            ((totalUsageMinutes.toDouble() / effectiveLimit) * 100).toInt()
        } else 100
        val effectiveLimitReached = isLimitReached ?: (totalUsageMinutes >= effectiveLimit)

        val record = ScreenTimeDailyUsage(
            id = existing?.id ?: UUID.randomUUID().toString(),
            familyId = existing?.familyId ?: "family-test-123",
            deviceId = deviceId,
            usageDate = usageDate,
            totalUsageMinutes = totalUsageMinutes,
            dailyLimitMinutes = effectiveLimit,
            remainingMinutes = effectiveRemaining,
            usagePercentage = effectivePercentage,
            isLimitReached = effectiveLimitReached,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        storage[key] = record
        lastUpsertedUsage = record
        upsertCount++
        return Result.Success(record)
    }

    override suspend fun getDailyUsage(
        deviceId: String,
        usageDate: String
    ): Result<ScreenTimeDailyUsage?> {
        if (shouldFail) {
            return Result.Error(failureException)
        }
        val key = Pair(deviceId, usageDate)
        return Result.Success(storage[key])
    }

    fun clear() {
        storage.clear()
        lastUpsertedUsage = null
        upsertCount = 0
        shouldFail = false
    }

    fun getStoredCount(): Int = storage.size

    fun getStoredUsage(deviceId: String, usageDate: String): ScreenTimeDailyUsage? =
        storage[Pair(deviceId, usageDate)]
}
