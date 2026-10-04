package com.niqob.app.fakes

import com.niqob.app.domain.enforcement.ParentOverrideAuthenticator

class FakeParentOverrideAuthenticator(
    var validPin: String = "1234",
    var forcedResult: Boolean? = null
) : ParentOverrideAuthenticator {

    var attempts: Int = 0
    var lastEnteredPin: String? = null

    override suspend fun authenticate(pin: String): Boolean {
        attempts++
        lastEnteredPin = pin
        forcedResult?.let { return it }
        return pin.trim() == validPin.trim()
    }
}
