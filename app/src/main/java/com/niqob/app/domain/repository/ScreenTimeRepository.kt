package com.niqob.app.domain.repository

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.ScreenTimeLimit

interface ScreenTimeRepository {
    suspend fun getScreenTimeLimit(deviceId: String): Result<ScreenTimeLimit>
    suspend fun setScreenTimeLimit(
        deviceId: String,
        dailyLimitMinutes: Int,
        enabled: Boolean = true
    ): Result<ScreenTimeLimit>
}
