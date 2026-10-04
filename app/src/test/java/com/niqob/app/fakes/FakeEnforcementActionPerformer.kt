package com.niqob.app.fakes

import com.niqob.app.domain.enforcement.EnforcementActionPerformer

class FakeEnforcementActionPerformer(
    var sendHomeResult: Boolean = true,
    var launchLimitScreenResult: Boolean = true
) : EnforcementActionPerformer {

    var sendHomeCallCount: Int = 0
    var launchLimitScreenCallCount: Int = 0
    val launchedTargets = mutableListOf<String>()

    override fun sendHome(): Boolean {
        sendHomeCallCount++
        return sendHomeResult
    }

    override fun launchLimitScreen(targetPackageName: String): Boolean {
        launchLimitScreenCallCount++
        launchedTargets.add(targetPackageName)
        return launchLimitScreenResult
    }

    fun reset() {
        sendHomeCallCount = 0
        launchLimitScreenCallCount = 0
        launchedTargets.clear()
    }
}
