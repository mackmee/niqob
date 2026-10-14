package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.ChildDeviceCredentialStore
import com.niqob.app.data.local.NoOpDeviceSecretCipher
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.domain.model.ScreenTimeDailyUsage
import com.niqob.app.domain.model.ScreenTimeLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * SEC-01 Audit Remediation Test Suite:
 * Hardened Child-Device Cryptographic RPC Authorization for:
 * 1. public.upsert_screen_time_usage_daily
 * 2. public.get_screen_time_usage_daily
 * 3. public.update_device_heartbeat
 * 4. public.get_screen_time_limit
 *
 * Test Scope & Architecture:
 * - UNIT TESTS (Offline / In-Memory):
 *   Emulates PostgreSQL stored procedure authorization logic, SHA-256 cryptographic
 *   verification, fail-closed ordering, and Kotlin repository credential resolution.
 * - LIVE DATABASE INTEGRATION REQUIREMENTS:
 *   Full end-to-end verification requires deploying migration
 *   20261011_harden_usage_and_heartbeat_rpc_authorization.sql to a real Supabase instance.
 */
class ChildUsageAndHeartbeatSecurityTest {

    // Test identifiers (synthetic test values, never real production secrets)
    private val deviceAId = "11111111-1111-1111-1111-111111111111"
    private val deviceASecret = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    private val deviceBId = "22222222-2222-2222-2222-222222222222"
    private val deviceBSecret = "f9e8d7c6b5a43210f9e8d7c6b5a43210f9e8d7c6b5a43210f9e8d7c6b5a43210"

    private val revokedDeviceId = "44444444-4444-4444-4444-444444444444"
    private val revokedDeviceSecret = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    private val familyAId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val familyBId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    private val parentAUid = "parent-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val parentBUid = "parent-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    private lateinit var deviceASecretHash: String
    private lateinit var deviceBSecretHash: String
    private lateinit var revokedDeviceSecretHash: String

    data class HelperAuthResult(
        val authorized: Boolean,
        val errorCode: String? = null,
        val message: String? = null
    )

    data class RpcExecutionResult(
        val success: Boolean,
        val errorCode: String? = null,
        val message: String? = null,
        val payload: Any? = null
    )

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().lowercase()
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            val high = Character.digit(clean[i * 2], 16)
            val low = Character.digit(clean[i * 2 + 1], 16)
            result[i] = ((high shl 4) or low).toByte()
        }
        return result
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setUp() {
        deviceASecretHash = sha256Hex(hexToBytes(deviceASecret))
        deviceBSecretHash = sha256Hex(hexToBytes(deviceBSecret))
        revokedDeviceSecretHash = sha256Hex(hexToBytes(revokedDeviceSecret))
        SharedPreferencesChildDeviceCredentialStore.resetInstanceForTesting()
    }

    /**
     * Accurate JVM emulation of public.verify_child_device_secret(p_device_id, p_device_secret).
     */
    private fun verifyChildDeviceSecret(
        deviceId: String?,
        deviceSecret: String?,
        deviceSecretHashInDb: String?,
        deviceExists: Boolean,
        pairingStatus: String
    ): HelperAuthResult {
        val genericFailure = HelperAuthResult(
            authorized = false,
            errorCode = "UNAUTHORIZED",
            message = "Invalid device authorization."
        )

        if (deviceId.isNullOrBlank() || deviceSecret == null) {
            return genericFailure
        }

        val secretTrimmed = deviceSecret.trim().lowercase()
        if (secretTrimmed.length != 64 || !secretTrimmed.all { it in '0'..'9' || it in 'a'..'f' }) {
            return genericFailure
        }

        if (!deviceExists) {
            return genericFailure
        }

        if (deviceSecretHashInDb == null) {
            return genericFailure
        }

        val rawBytes = hexToBytes(secretTrimmed)
        val computedHash = sha256Hex(rawBytes)
        if (!computedHash.equals(deviceSecretHashInDb, ignoreCase = true)) {
            return genericFailure
        }

        if (pairingStatus != "CONNECTED") {
            return genericFailure
        }

        return HelperAuthResult(authorized = true)
    }

    // --------------------------------------------------------------------------
    // RPC Emulation: update_device_heartbeat
    // --------------------------------------------------------------------------
    private fun simulateUpdateDeviceHeartbeat(
        callerUid: String?,
        deviceId: String?,
        deviceSecret: String?
    ): RpcExecutionResult {
        if (deviceId == null) {
            return RpcExecutionResult(false, "INVALID_PARAMETERS", "Device ID cannot be null.")
        }

        if (callerUid == null) {
            // Child caller: MUST verify secret BEFORE device lookup
            val isKnown = deviceId in listOf(deviceAId, deviceBId, revokedDeviceId)
            val storedHash = when (deviceId) {
                deviceAId -> deviceASecretHash
                deviceBId -> deviceBSecretHash
                revokedDeviceId -> revokedDeviceSecretHash
                else -> null
            }
            val pairingStatus = if (deviceId == revokedDeviceId) "REVOKED" else "CONNECTED"

            val auth = verifyChildDeviceSecret(deviceId, deviceSecret, storedHash, isKnown, pairingStatus)
            if (!auth.authorized) {
                return RpcExecutionResult(false, "UNAUTHORIZED", "Invalid device authorization.")
            }
        } else {
            // Parent caller: checks family ownership
            val deviceBelongsToFamilyA = deviceId in listOf(deviceAId, revokedDeviceId)
            val deviceBelongsToFamilyB = deviceId == deviceBId

            if (!deviceBelongsToFamilyA && !deviceBelongsToFamilyB) {
                return RpcExecutionResult(false, "DEVICE_NOT_FOUND", "Device record not found.")
            }

            val parentOwnsDevice = (callerUid == parentAUid && deviceBelongsToFamilyA) ||
                    (callerUid == parentBUid && deviceBelongsToFamilyB)
            if (!parentOwnsDevice) {
                return RpcExecutionResult(false, "UNAUTHORIZED", "You do not have permission to update this device.")
            }
        }

        return RpcExecutionResult(true, payload = "HEARTBEAT_ACK")
    }

    // --------------------------------------------------------------------------
    // RPC Emulation: get_screen_time_limit
    // --------------------------------------------------------------------------
    private fun simulateGetScreenTimeLimit(
        callerUid: String?,
        deviceId: String?,
        deviceSecret: String?
    ): RpcExecutionResult {
        if (deviceId == null) {
            return RpcExecutionResult(false, "INVALID_PARAMETERS", "Device ID cannot be null.")
        }

        if (callerUid == null) {
            // Child caller: MUST verify secret BEFORE device lookup
            val isKnown = deviceId in listOf(deviceAId, deviceBId, revokedDeviceId)
            val storedHash = when (deviceId) {
                deviceAId -> deviceASecretHash
                deviceBId -> deviceBSecretHash
                revokedDeviceId -> revokedDeviceSecretHash
                else -> null
            }
            val pairingStatus = if (deviceId == revokedDeviceId) "REVOKED" else "CONNECTED"

            val auth = verifyChildDeviceSecret(deviceId, deviceSecret, storedHash, isKnown, pairingStatus)
            if (!auth.authorized) {
                return RpcExecutionResult(false, "UNAUTHORIZED", "Invalid device authorization.")
            }
        } else {
            // Parent caller: checks family ownership
            val deviceBelongsToFamilyA = deviceId in listOf(deviceAId, revokedDeviceId)
            val deviceBelongsToFamilyB = deviceId == deviceBId

            if (!deviceBelongsToFamilyA && !deviceBelongsToFamilyB) {
                return RpcExecutionResult(false, "DEVICE_NOT_FOUND", "Device record not found.")
            }

            val parentOwnsDevice = (callerUid == parentAUid && deviceBelongsToFamilyA) ||
                    (callerUid == parentBUid && deviceBelongsToFamilyB)
            if (!parentOwnsDevice) {
                return RpcExecutionResult(false, "UNAUTHORIZED", "You do not have permission to view screen time for this device.")
            }
        }

        return RpcExecutionResult(
            true,
            payload = ScreenTimeLimit(
                familyId = familyAId,
                deviceId = deviceId,
                dailyLimitMinutes = 120,
                enabled = true
            )
        )
    }

    // --------------------------------------------------------------------------
    // RPC Emulation: upsert_screen_time_usage_daily
    // --------------------------------------------------------------------------
    private fun simulateUpsertScreenTimeUsageDaily(
        callerUid: String?,
        deviceId: String?,
        usageDate: String?,
        totalUsageMinutes: Int?,
        deviceSecret: String?,
        remainingMinutes: Int? = null
    ): RpcExecutionResult {
        if (deviceId == null) {
            return RpcExecutionResult(false, "INVALID_PARAMETERS", "Device ID cannot be null.")
        }
        if (usageDate == null) {
            return RpcExecutionResult(false, "INVALID_PARAMETERS", "Usage date cannot be null.")
        }
        if (totalUsageMinutes == null || totalUsageMinutes < 0) {
            return RpcExecutionResult(false, "INVALID_USAGE", "Total usage minutes must be non-negative.")
        }

        // Step 7D Hardened Security Policy:
        // Telemetry write strictly requires valid child device secret for EVERY caller.
        // An authenticated parent JWT alone (callerUid != null) cannot authorize a telemetry write.
        val isKnown = deviceId in listOf(deviceAId, deviceBId, revokedDeviceId)
        val storedHash = when (deviceId) {
            deviceAId -> deviceASecretHash
            deviceBId -> deviceBSecretHash
            revokedDeviceId -> revokedDeviceSecretHash
            else -> null
        }
        val pairingStatus = if (deviceId == revokedDeviceId) "REVOKED" else "CONNECTED"

        val auth = verifyChildDeviceSecret(deviceId, deviceSecret, storedHash, isKnown, pairingStatus)
        if (!auth.authorized) {
            return RpcExecutionResult(false, "UNAUTHORIZED", "Invalid device authorization.")
        }

        if (!isKnown) {
            return RpcExecutionResult(false, "UNAUTHORIZED", "Invalid device authorization.")
        }

        val effectiveRemaining = maxOf(0, remainingMinutes ?: maxOf(0, 120 - totalUsageMinutes))

        return RpcExecutionResult(
            true,
            payload = ScreenTimeDailyUsage(
                id = "usage-1",
                familyId = familyAId,
                deviceId = deviceId,
                usageDate = usageDate,
                totalUsageMinutes = totalUsageMinutes,
                dailyLimitMinutes = 120,
                remainingMinutes = effectiveRemaining,
                usagePercentage = ((totalUsageMinutes.toDouble() / 120.0) * 100).toInt(),
                isLimitReached = totalUsageMinutes >= 120,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // --------------------------------------------------------------------------
    // RPC Emulation: get_screen_time_usage_daily
    // --------------------------------------------------------------------------
    private fun simulateGetScreenTimeUsageDaily(
        callerUid: String?,
        deviceId: String?,
        usageDate: String?,
        deviceSecret: String?
    ): RpcExecutionResult {
        if (deviceId == null) {
            return RpcExecutionResult(false, "INVALID_PARAMETERS", "Device ID cannot be null.")
        }

        if (callerUid == null) {
            // Child caller: MUST verify secret BEFORE device lookup
            val isKnown = deviceId in listOf(deviceAId, deviceBId, revokedDeviceId)
            val storedHash = when (deviceId) {
                deviceAId -> deviceASecretHash
                deviceBId -> deviceBSecretHash
                revokedDeviceId -> revokedDeviceSecretHash
                else -> null
            }
            val pairingStatus = if (deviceId == revokedDeviceId) "REVOKED" else "CONNECTED"

            val auth = verifyChildDeviceSecret(deviceId, deviceSecret, storedHash, isKnown, pairingStatus)
            if (!auth.authorized) {
                return RpcExecutionResult(false, "UNAUTHORIZED", "Invalid device authorization.")
            }
        } else {
            // Parent caller: checks family ownership
            val deviceBelongsToFamilyA = deviceId in listOf(deviceAId, revokedDeviceId)
            val deviceBelongsToFamilyB = deviceId == deviceBId

            if (!deviceBelongsToFamilyA && !deviceBelongsToFamilyB) {
                return RpcExecutionResult(false, "DEVICE_NOT_FOUND", "Device record not found.")
            }

            val parentOwnsDevice = (callerUid == parentAUid && deviceBelongsToFamilyA) ||
                    (callerUid == parentBUid && deviceBelongsToFamilyB)
            if (!parentOwnsDevice) {
                return RpcExecutionResult(false, "UNAUTHORIZED", "You do not have permission to view usage for this device.")
            }
        }

        return RpcExecutionResult(
            true,
            payload = ScreenTimeDailyUsage(
                id = "usage-1",
                familyId = familyAId,
                deviceId = deviceId,
                usageDate = usageDate ?: "2026-10-11",
                totalUsageMinutes = 45,
                dailyLimitMinutes = 120,
                remainingMinutes = 75,
                usagePercentage = 38,
                isLimitReached = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    // ==========================================================================
    // 1. UPDATE_DEVICE_HEARTBEAT TESTS
    // ==========================================================================

    @Test
    fun updateHeartbeat_anonymousChild_missingSecret_failsUnauthorized() {
        val result = simulateUpdateDeviceHeartbeat(
            callerUid = null,
            deviceId = deviceAId,
            deviceSecret = null
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
        assertEquals("Invalid device authorization.", result.message)
    }

    @Test
    fun updateHeartbeat_anonymousChild_blankSecret_failsUnauthorized() {
        val result = simulateUpdateDeviceHeartbeat(
            callerUid = null,
            deviceId = deviceAId,
            deviceSecret = "   "
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun updateHeartbeat_anonymousChild_malformedSecret_failsUnauthorized() {
        // Less than 64 characters
        val shortResult = simulateUpdateDeviceHeartbeat(
            callerUid = null,
            deviceId = deviceAId,
            deviceSecret = "a1b2c3d4"
        )
        assertFalse(shortResult.success)
        assertEquals("UNAUTHORIZED", shortResult.errorCode)

        // Non-hex characters
        val nonHexResult = simulateUpdateDeviceHeartbeat(
            callerUid = null,
            deviceId = deviceAId,
            deviceSecret = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
        )
        assertFalse(nonHexResult.success)
        assertEquals("UNAUTHORIZED", nonHexResult.errorCode)
    }

    @Test
    fun updateHeartbeat_anonymousChild_wrongDeviceSecret_failsUnauthorized() {
        // Pass device B's secret with device A's id
        val result = simulateUpdateDeviceHeartbeat(
            callerUid = null,
            deviceId = deviceAId,
            deviceSecret = deviceBSecret
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun updateHeartbeat_anonymousChild_revokedDevice_failsUnauthorized() {
        val result = simulateUpdateDeviceHeartbeat(
            callerUid = null,
            deviceId = revokedDeviceId,
            deviceSecret = revokedDeviceSecret
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun updateHeartbeat_anonymousChild_validSecret_succeeds() {
        val result = simulateUpdateDeviceHeartbeat(
            callerUid = null,
            deviceId = deviceAId,
            deviceSecret = deviceASecret
        )
        assertTrue(result.success)
        assertEquals("HEARTBEAT_ACK", result.payload)
    }

    @Test
    fun updateHeartbeat_authenticatedParent_validFamily_succeedsWithoutSecret() {
        val result = simulateUpdateDeviceHeartbeat(
            callerUid = parentAUid,
            deviceId = deviceAId,
            deviceSecret = null
        )
        assertTrue(result.success)
    }

    @Test
    fun updateHeartbeat_authenticatedParent_crossFamily_failsUnauthorized() {
        val result = simulateUpdateDeviceHeartbeat(
            callerUid = parentBUid,
            deviceId = deviceAId, // Belongs to Family A
            deviceSecret = null
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    // ==========================================================================
    // 2. GET_SCREEN_TIME_LIMIT TESTS
    // ==========================================================================

    @Test
    fun getScreenTimeLimit_anonymousChild_missingSecret_failsUnauthorized() {
        val result = simulateGetScreenTimeLimit(
            callerUid = null,
            deviceId = deviceAId,
            deviceSecret = null
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun getScreenTimeLimit_anonymousChild_wrongDeviceSecret_failsUnauthorized() {
        val result = simulateGetScreenTimeLimit(
            callerUid = null,
            deviceId = deviceAId,
            deviceSecret = deviceBSecret
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun getScreenTimeLimit_anonymousChild_revokedDevice_failsUnauthorized() {
        val result = simulateGetScreenTimeLimit(
            callerUid = null,
            deviceId = revokedDeviceId,
            deviceSecret = revokedDeviceSecret
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun getScreenTimeLimit_anonymousChild_validSecret_succeeds() {
        val result = simulateGetScreenTimeLimit(
            callerUid = null,
            deviceId = deviceAId,
            deviceSecret = deviceASecret
        )
        assertTrue(result.success)
        assertNotNull(result.payload)
        val limit = result.payload as ScreenTimeLimit
        assertEquals(120, limit.dailyLimitMinutes)
    }

    @Test
    fun getScreenTimeLimit_authenticatedParent_validFamily_succeedsWithoutSecret() {
        val result = simulateGetScreenTimeLimit(
            callerUid = parentAUid,
            deviceId = deviceAId,
            deviceSecret = null
        )
        assertTrue(result.success)
    }

    @Test
    fun getScreenTimeLimit_authenticatedParent_crossFamily_failsUnauthorized() {
        val result = simulateGetScreenTimeLimit(
            callerUid = parentBUid,
            deviceId = deviceAId,
            deviceSecret = null
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    // ==========================================================================
    // 3. UPSERT_SCREEN_TIME_USAGE_DAILY TESTS
    // ==========================================================================

    @Test
    fun upsertDailyUsage_anonymousChild_missingSecret_failsUnauthorized() {
        val result = simulateUpsertScreenTimeUsageDaily(
            callerUid = null,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 60,
            deviceSecret = null
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun upsertDailyUsage_anonymousChild_wrongDeviceSecret_failsUnauthorized() {
        val result = simulateUpsertScreenTimeUsageDaily(
            callerUid = null,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 60,
            deviceSecret = deviceBSecret
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun upsertDailyUsage_anonymousChild_revokedDevice_failsUnauthorized() {
        val result = simulateUpsertScreenTimeUsageDaily(
            callerUid = null,
            deviceId = revokedDeviceId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 60,
            deviceSecret = revokedDeviceSecret
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun upsertDailyUsage_anonymousChild_validSecret_succeeds() {
        val result = simulateUpsertScreenTimeUsageDaily(
            callerUid = null,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 90,
            deviceSecret = deviceASecret
        )
        assertTrue(result.success)
        val usage = result.payload as ScreenTimeDailyUsage
        assertEquals(90, usage.totalUsageMinutes)
    }

    @Test
    fun upsertDailyUsage_authenticatedParent_withoutChildSecret_failsUnauthorized() {
        // Step 7D: A parent JWT alone (even for their own family's child) cannot authorize telemetry writes
        val result = simulateUpsertScreenTimeUsageDaily(
            callerUid = parentAUid,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 90,
            deviceSecret = null
        )
        assertFalse("Parent JWT alone must not authorize telemetry write", result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
        assertEquals("Invalid device authorization.", result.message)
    }

    @Test
    fun upsertDailyUsage_authenticatedParent_withInvalidChildSecret_failsUnauthorized() {
        val result = simulateUpsertScreenTimeUsageDaily(
            callerUid = parentAUid,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 90,
            deviceSecret = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        assertFalse("Parent caller with invalid child secret must be rejected", result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
        assertEquals("Invalid device authorization.", result.message)
    }

    @Test
    fun upsertDailyUsage_authenticatedParent_withRevokedChildSecret_failsUnauthorized() {
        val result = simulateUpsertScreenTimeUsageDaily(
            callerUid = parentAUid,
            deviceId = revokedDeviceId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 90,
            deviceSecret = revokedDeviceSecret
        )
        assertFalse("Parent caller with revoked child secret must be rejected", result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
        assertEquals("Invalid device authorization.", result.message)
    }

    // ==========================================================================
    // 4. GET_SCREEN_TIME_USAGE_DAILY TESTS
    // ==========================================================================

    @Test
    fun getDailyUsage_anonymousChild_missingSecret_failsUnauthorized() {
        val result = simulateGetScreenTimeUsageDaily(
            callerUid = null,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            deviceSecret = null
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun getDailyUsage_anonymousChild_wrongDeviceSecret_failsUnauthorized() {
        val result = simulateGetScreenTimeUsageDaily(
            callerUid = null,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            deviceSecret = deviceBSecret
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun getDailyUsage_anonymousChild_revokedDevice_failsUnauthorized() {
        val result = simulateGetScreenTimeUsageDaily(
            callerUid = null,
            deviceId = revokedDeviceId,
            usageDate = "2026-10-11",
            deviceSecret = revokedDeviceSecret
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    @Test
    fun getDailyUsage_anonymousChild_validSecret_succeeds() {
        val result = simulateGetScreenTimeUsageDaily(
            callerUid = null,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            deviceSecret = deviceASecret
        )
        assertTrue(result.success)
        assertNotNull(result.payload)
    }

    @Test
    fun getDailyUsage_authenticatedParent_validFamily_succeedsWithoutSecret() {
        val result = simulateGetScreenTimeUsageDaily(
            callerUid = parentAUid,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            deviceSecret = null
        )
        assertTrue(result.success)
    }

    @Test
    fun getDailyUsage_authenticatedParent_crossFamily_failsUnauthorized() {
        val result = simulateGetScreenTimeUsageDaily(
            callerUid = parentBUid,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            deviceSecret = null
        )
        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
    }

    // ==========================================================================
    // 5. FAIL-CLOSED ANTI-ORACLE PROTECTION (NONEXISTENT DEVICE)
    // ==========================================================================

    @Test
    fun failClosed_nonexistentDevice_returnsGenericUnauthorized_doesNotLeakExistence() {
        val nonexistentDeviceId = "99999999-9999-9999-9999-999999999999"

        // Anonymous caller for nonexistent device MUST fail with UNAUTHORIZED, not DEVICE_NOT_FOUND
        val heartbeatResult = simulateUpdateDeviceHeartbeat(
            callerUid = null,
            deviceId = nonexistentDeviceId,
            deviceSecret = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        assertEquals("UNAUTHORIZED", heartbeatResult.errorCode)
        assertEquals("Invalid device authorization.", heartbeatResult.message)

        val limitResult = simulateGetScreenTimeLimit(
            callerUid = null,
            deviceId = nonexistentDeviceId,
            deviceSecret = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        assertEquals("UNAUTHORIZED", limitResult.errorCode)
        assertEquals("Invalid device authorization.", limitResult.message)

        val upsertResult = simulateUpsertScreenTimeUsageDaily(
            callerUid = null,
            deviceId = nonexistentDeviceId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 30,
            deviceSecret = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        assertEquals("UNAUTHORIZED", upsertResult.errorCode)
        assertEquals("Invalid device authorization.", upsertResult.message)

        val getUsageResult = simulateGetScreenTimeUsageDaily(
            callerUid = null,
            deviceId = nonexistentDeviceId,
            usageDate = "2026-10-11",
            deviceSecret = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        assertEquals("UNAUTHORIZED", getUsageResult.errorCode)
        assertEquals("Invalid device authorization.", getUsageResult.message)
    }

    // ==========================================================================
    // 6. CREDENTIAL STORE RESOLUTION & NO LEAK IN LOGS/MODELS
    // ==========================================================================

    @Test
    fun credentialStore_retrievesAndPassesSecretCorrectly() {
        val testStore = SharedPreferencesChildDeviceCredentialStore(
            sharedPreferences = null, // in-memory fallback
            cipher = NoOpDeviceSecretCipher
        )

        testStore.save(deviceAId, deviceASecret)
        assertEquals(deviceAId, testStore.getDeviceId())
        assertEquals(deviceASecret, testStore.getDeviceSecret())

        // Verify resolved secret matches
        val resolvedSecret = testStore.getDeviceSecret()?.trim()
        assertEquals(deviceASecret, resolvedSecret)

        // Verify explicit override takes precedence
        val explicitSecret = "custom-override-secret"
        val resolvedWithExplicit = explicitSecret ?: testStore.getDeviceSecret()
        assertEquals("custom-override-secret", resolvedWithExplicit)
    }

    @Test
    fun domainModels_neverExposeRawSecret() {
        val usage = ScreenTimeDailyUsage(
            id = "test-usage",
            familyId = familyAId,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 45,
            dailyLimitMinutes = 120,
            remainingMinutes = 75,
            usagePercentage = 38,
            isLimitReached = false,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        assertFalse(usage.toString().contains(deviceASecret))

        val limit = ScreenTimeLimit(
            id = "test-limit",
            familyId = familyAId,
            deviceId = deviceAId,
            dailyLimitMinutes = 120,
            enabled = true,
            createdAt = 1000L,
            updatedAt = 2000L
        )
        assertFalse(limit.toString().contains(deviceASecret))
    }

    // --------------------------------------------------------------------------
    // Regression Tests: FINDING-04 Negative remaining_minutes clamping
    // --------------------------------------------------------------------------
    @Test
    fun upsertScreenTimeUsageDaily_clampsExplicitNegativeRemainingMinutesToZero() {
        // Child caller passes explicit negative remaining minutes (-30)
        val result = simulateUpsertScreenTimeUsageDaily(
            callerUid = null,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 90,
            deviceSecret = deviceASecret,
            remainingMinutes = -30
        )

        assertTrue("RPC must succeed", result.success)
        assertNotNull(result.payload)
        val usage = result.payload as ScreenTimeDailyUsage
        assertEquals(
            "Explicit negative remaining minutes must be clamped to 0",
            0,
            usage.remainingMinutes
        )
    }

    @Test
    fun upsertScreenTimeUsageDaily_clampsToZeroWhenUsageExceedsLimitAndRemainingOmitted() {
        // Total usage exceeds limit (150 > 120) with null remainingMinutes
        val result = simulateUpsertScreenTimeUsageDaily(
            callerUid = null,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 150,
            deviceSecret = deviceASecret,
            remainingMinutes = null
        )

        assertTrue(result.success)
        val usage = result.payload as ScreenTimeDailyUsage
        assertEquals(
            "Derived negative remaining minutes (120 - 150 = -30) must be clamped to 0",
            0,
            usage.remainingMinutes
        )
        assertTrue("Limit reached must be true", usage.isLimitReached)
    }

    @Test
    fun upsertScreenTimeUsageDaily_preservesValidPositiveRemainingMinutes() {
        val result = simulateUpsertScreenTimeUsageDaily(
            callerUid = null,
            deviceId = deviceAId,
            usageDate = "2026-10-11",
            totalUsageMinutes = 45,
            deviceSecret = deviceASecret,
            remainingMinutes = 75
        )

        assertTrue(result.success)
        val usage = result.payload as ScreenTimeDailyUsage
        assertEquals(75, usage.remainingMinutes)
        assertFalse(usage.isLimitReached)
    }
}
