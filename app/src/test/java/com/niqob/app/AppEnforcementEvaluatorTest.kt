package com.niqob.app

import com.niqob.app.data.repository.LocalAppEnforcementPolicyRepository
import com.niqob.app.domain.enforcement.AppEnforcementEvaluator
import com.niqob.app.domain.model.AppEnforcementAction
import com.niqob.app.domain.model.AppEnforcementPolicy
import com.niqob.app.domain.model.ScreenTimeEnforcementState
import com.niqob.app.domain.model.ScreenTimeEnforcementStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppEnforcementEvaluatorTest {

    private val disabledState = ScreenTimeEnforcementState(
        isEnforcementEnabled = false,
        isLimitReached = false,
        usedMinutes = 30,
        dailyLimitMinutes = 120,
        remainingMinutes = 90,
        usagePercentage = 25,
        usageDate = "2026-10-04",
        status = ScreenTimeEnforcementStatus.DISABLED
    )

    private val withinLimitState = ScreenTimeEnforcementState(
        isEnforcementEnabled = true,
        isLimitReached = false,
        usedMinutes = 30,
        dailyLimitMinutes = 120,
        remainingMinutes = 90,
        usagePercentage = 25,
        usageDate = "2026-10-04",
        status = ScreenTimeEnforcementStatus.WITHIN_LIMIT
    )

    private val limitReachedState = ScreenTimeEnforcementState(
        isEnforcementEnabled = true,
        isLimitReached = true,
        usedMinutes = 135,
        dailyLimitMinutes = 120,
        remainingMinutes = 0,
        usagePercentage = 112,
        usageDate = "2026-10-04",
        status = ScreenTimeEnforcementStatus.LIMIT_REACHED
    )

    @Before
    fun setUp() {
        LocalAppEnforcementPolicyRepository.resetForTesting()
    }

    @After
    fun tearDown() {
        LocalAppEnforcementPolicyRepository.resetForTesting()
    }

    @Test
    fun evaluate_whenEnforcementDisabled_allowsAnyPackage() {
        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.android.chrome",
            enforcementState = disabledState
        )

        assertEquals(AppEnforcementAction.ALLOW, decision.decision)
        assertTrue(decision.isAllowed)
        assertFalse(decision.isBlocked)
        assertFalse(decision.enforcementActive)
        assertEquals("Screen-time enforcement is not active", decision.reason)
    }

    @Test
    fun evaluate_whenEnforcementWithinLimit_allowsAnyPackage() {
        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.google.android.youtube",
            enforcementState = withinLimitState
        )

        assertEquals(AppEnforcementAction.ALLOW, decision.decision)
        assertTrue(decision.isAllowed)
        assertFalse(decision.isBlocked)
        assertFalse(decision.enforcementActive)
        assertEquals("Screen-time enforcement is not active", decision.reason)
    }

    @Test
    fun evaluate_whenEnforcementActive_allowsNiqobPackageAlways() {
        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.niqob.app",
            enforcementState = limitReachedState
        )

        assertEquals(AppEnforcementAction.ALLOW, decision.decision)
        assertTrue(decision.isAllowed)
        assertFalse(decision.isBlocked)
        assertTrue(decision.enforcementActive)
        assertEquals("NIQOB self application is always allowed", decision.reason)
    }

    @Test
    fun evaluate_whenEnforcementActive_allowsExplicitlyAllowedPackage() {
        val policy = AppEnforcementPolicy(
            allowedPackageNames = setOf("com.android.settings", "com.duolingo")
        )

        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.android.settings",
            enforcementState = limitReachedState,
            policy = policy
        )

        assertEquals(AppEnforcementAction.ALLOW, decision.decision)
        assertTrue(decision.isAllowed)
        assertFalse(decision.isBlocked)
        assertTrue(decision.enforcementActive)
        assertEquals("Package 'com.android.settings' is explicitly allowed by policy", decision.reason)
    }

    @Test
    fun evaluate_whenEnforcementActive_blocksNonAllowedPackage() {
        val policy = AppEnforcementPolicy(
            allowedPackageNames = setOf("com.android.settings")
        )

        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.google.android.youtube",
            enforcementState = limitReachedState,
            policy = policy
        )

        assertEquals(AppEnforcementAction.BLOCK, decision.decision)
        assertFalse(decision.isAllowed)
        assertTrue(decision.isBlocked)
        assertTrue(decision.enforcementActive)
        assertEquals(
            "Screen-time limit reached; package 'com.google.android.youtube' is not in allowed list",
            decision.reason
        )
    }

    @Test
    fun evaluate_whenEmptyAllowedListAndEnforcementActive_blocksThirdPartyPackage() {
        val policy = AppEnforcementPolicy(allowedPackageNames = emptySet())

        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.android.chrome",
            enforcementState = limitReachedState,
            policy = policy
        )

        assertEquals(AppEnforcementAction.BLOCK, decision.decision)
        assertTrue(decision.isBlocked)
        assertTrue(decision.enforcementActive)
    }

    @Test
    fun evaluate_packageMatchingIsExact_doesNotMatchPrefixesOrSuffixes() {
        val policy = AppEnforcementPolicy(
            allowedPackageNames = setOf("com.android.chrome")
        )

        // Similar package with suffix
        val decisionSuffix = AppEnforcementEvaluator.evaluate(
            packageName = "com.android.chrome.canary",
            enforcementState = limitReachedState,
            policy = policy
        )
        assertEquals(AppEnforcementAction.BLOCK, decisionSuffix.decision)

        // Substring / prefix of allowed package
        val decisionPrefix = AppEnforcementEvaluator.evaluate(
            packageName = "com.android",
            enforcementState = limitReachedState,
            policy = policy
        )
        assertEquals(AppEnforcementAction.BLOCK, decisionPrefix.decision)

        // Exact match
        val decisionExact = AppEnforcementEvaluator.evaluate(
            packageName = "com.android.chrome",
            enforcementState = limitReachedState,
            policy = policy
        )
        assertEquals(AppEnforcementAction.ALLOW, decisionExact.decision)
    }

    @Test
    fun evaluate_similarPackageNames_doNotAccidentallyMatchNiqob() {
        // Attack/impostor package: "com.niqob.app.malicious" or "com.niqob.app2"
        val decisionImpostor = AppEnforcementEvaluator.evaluate(
            packageName = "com.niqob.app.malicious",
            enforcementState = limitReachedState
        )
        assertEquals(AppEnforcementAction.BLOCK, decisionImpostor.decision)

        val decisionImpostor2 = AppEnforcementEvaluator.evaluate(
            packageName = "org.niqob.app",
            enforcementState = limitReachedState
        )
        assertEquals(AppEnforcementAction.BLOCK, decisionImpostor2.decision)
    }

    @Test
    fun evaluate_whenPackageInBlockedList_blocksEvenIfAllowedListContainsIt() {
        val policy = AppEnforcementPolicy(
            allowedPackageNames = setOf("com.android.chrome"),
            blockedPackageNames = setOf("com.android.chrome")
        )

        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.android.chrome",
            enforcementState = limitReachedState,
            policy = policy
        )

        assertEquals(AppEnforcementAction.BLOCK, decision.decision)
        assertEquals("Package 'com.android.chrome' is explicitly blocked by policy", decision.reason)
    }

    @Test
    fun evaluate_preservesDecisionTimestamp() {
        val fixedTimestamp = 1770000000000L
        val decision = AppEnforcementEvaluator.evaluate(
            packageName = "com.niqob.app",
            enforcementState = limitReachedState,
            timestampMillis = fixedTimestamp
        )

        assertEquals(fixedTimestamp, decision.timestampMillis)
    }

    @Test
    fun evaluate_handlesNullEnforcementStateUsingPolicyFlag() {
        val policyActive = AppEnforcementPolicy(
            isEnforcementActive = true,
            allowedPackageNames = setOf("com.android.dialer")
        )

        val decisionAllowed = AppEnforcementEvaluator.evaluate(
            packageName = "com.android.dialer",
            enforcementState = null,
            policy = policyActive
        )
        assertEquals(AppEnforcementAction.ALLOW, decisionAllowed.decision)

        val decisionBlocked = AppEnforcementEvaluator.evaluate(
            packageName = "com.instagram.android",
            enforcementState = null,
            policy = policyActive
        )
        assertEquals(AppEnforcementAction.BLOCK, decisionBlocked.decision)
    }

    @Test
    fun localRepository_defaultsAreSafe_andSupportsDynamicUpdates() {
        val repo = LocalAppEnforcementPolicyRepository.getInstance()
        val defaultPolicy = repo.getPolicy().value

        assertFalse(defaultPolicy.isEnforcementActive)
        assertTrue(defaultPolicy.allowedPackageNames.isEmpty())
        assertTrue(defaultPolicy.blockedPackageNames.isEmpty())

        repo.addAllowedPackage("com.android.settings")
        assertTrue(repo.getPolicy().value.allowedPackageNames.contains("com.android.settings"))

        repo.removeAllowedPackage("com.android.settings")
        assertFalse(repo.getPolicy().value.allowedPackageNames.contains("com.android.settings"))

        repo.addBlockedPackage("com.unwanted.app")
        assertTrue(repo.getPolicy().value.blockedPackageNames.contains("com.unwanted.app"))

        repo.clear()
        assertEquals(AppEnforcementPolicy.DEFAULT, repo.getPolicy().value)
    }
}
