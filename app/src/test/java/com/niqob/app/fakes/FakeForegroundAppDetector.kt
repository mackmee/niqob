package com.niqob.app.fakes

import com.niqob.app.domain.enforcement.ForegroundAppDetector
import com.niqob.app.domain.model.ForegroundAppInfo

class FakeForegroundAppDetector(
    var currentApp: ForegroundAppInfo? = null,
    var previousApp: ForegroundAppInfo? = null,
    var isPermissionGranted: Boolean = true,
    var selfPackageName: String = "com.niqob.app",
    var shouldThrowException: Boolean = false
) : ForegroundAppDetector {

    override suspend fun getCurrentForegroundApp(excludeSelf: Boolean): ForegroundAppInfo? {
        if (shouldThrowException) {
            throw RuntimeException("Simulated UsageStatsManager failure")
        }
        if (!isPermissionGranted) {
            return null
        }
        val app = currentApp ?: return null

        if (excludeSelf && app.packageName == selfPackageName) {
            return previousApp
        }
        return app
    }
}
