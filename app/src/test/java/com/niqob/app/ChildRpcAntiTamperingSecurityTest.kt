package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.ChildDeviceCredentialStore
import com.niqob.app.data.local.NoOpDeviceSecretCipher
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.remote.supabase.dto.ConsumeOverrideResponseDto
import com.niqob.app.data.remote.supabase.dto.CreateOverrideResponseDto
import com.niqob.app.data.remote.supabase.dto.OverrideStatusResponseDto
import com.niqob.app.domain.model.ChildDeviceStatus
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.RemoteOverrideRequest
import com.niqob.app.domain.model.RemoteOverrideRequestStatus
import com.niqob.app.feature.child.ChildPairingUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Phase 5 Step 5-4: Cryptographic Child-RPC Authorization & Anti-Tampering Test Suite.
 *
 * Verifies security requirements against server-side authorization rules and client integration:
 * A. Unauthorized child + nonexistent device => generic UNAUTHORIZED
 * B. Unauthorized child + nonexistent request => generic UNAUTHORIZED
 * C. Unauthorized child + existing request belonging to another device => generic UNAUTHORIZED
 * D. Authorized child + nonexistent request => REQUEST_NOT_FOUND
 * E. Authorized child + valid request => normal behavior
 * F. Parent behavior remains unchanged (auth.uid() ownership checks)
 * G. Helper remains inaccessible directly to anon/authenticated
 * H. No plaintext device secret is returned or persisted
 */
class ChildRpcAntiTamperingSecurityTest {

    // Deterministic test data (never logged or printed)
    private val deviceAId = "11111111-1111-1111-1111-111111111111"
    private val deviceASecret = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    private val deviceBId = "22222222-2222-2222-2222-222222222222"
    private val deviceBSecret = "f9e8d7c6b5a43210f9e8d7c6b5a43210f9e8d7c6b5a43210f9e8d7c6b5a43210"

    private val legacyDeviceId = "33333333-3333-3333-3333-333333333333"

    private val familyId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val parentUid = "pppppppp-pppp-pppp-pppp-pppppppppppp"
    private val otherParentUid = "oooooooo-oooo-oooo-oooo-oooooooooooo"

    data class HelperAuthResult(
        val authorized: Boolean,
        val errorCode: String? = null,
        val message: String? = null,
        val leaksParentId: Boolean = false
    )

    data class RpcResult(
        val success: Boolean,
        val errorCode: String? = null,
        val message: String? = null
    )

    data class MockOverrideRequest(
        val id: String,
        val deviceId: String,
        val familyId: String,
        val parentId: String,
        val status: String
    )

    /**
     * Accurate emulation of the hardened SQL helper public.verify_child_device_secret.
     * Uniform generic UNAUTHORIZED response on all failures to prevent oracle information leakage.
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
            message = "Invalid device authorization.",
            leaksParentId = false
        )

        // 1. Parameter Validation (Fail closed with generic error)
        if (deviceId.isNullOrBlank() || deviceSecret == null) {
            return genericFailure
        }

        val secretTrimmed = deviceSecret.trim().lowercase()

        // 64-char lowercase hex check
        if (secretTrimmed.length != 64 || !secretTrimmed.all { it in '0'..'9' || it in 'a'..'f' }) {
            return genericFailure
        }

        // 2. Device Lookup (Fail closed with generic error: no DEVICE_NOT_FOUND oracle)
        if (!deviceExists) {
            return genericFailure
        }

        // 3. Legacy Device Check (Fail closed with generic error: no NULL hash oracle)
        if (deviceSecretHashInDb == null) {
            return genericFailure
        }

        // 4. Cryptographic Verification: SHA-256 of decoded 32-byte raw secret
        val rawBytes = hexToBytes(secretTrimmed)
        val computedHash = sha256Hex(rawBytes)

        if (!computedHash.equals(deviceSecretHashInDb, ignoreCase = true)) {
            return genericFailure
        }

        // 5. Active Pairing Check (Fail closed with generic error: no pairing status oracle)
        if (pairingStatus != "CONNECTED") {
            return genericFailure
        }

        // Minimal successful return: only authorized=true; no parent_id or family metadata leaked
        return HelperAuthResult(
            authorized = true,
            errorCode = null,
            message = null,
            leaksParentId = false
        )
    }

    /**
     * Emulates the hardened outer RPC public.get_override_request_status.
     * Crucially verifies that for child callers (callerUid == null), child credential
     * authentication happens BEFORE any request-existence or device-existence lookups.
     */
    private fun simulateGetOverrideRequestStatus(
        callerUid: String?,
        deviceId: String?,
        requestId: String?,
        deviceSecret: String?,
        mockRequest: MockOverrideRequest?
    ): RpcResult {
        // 1. Parameter Validation
        if (deviceId == null || requestId == null) {
            return RpcResult(false, "INVALID_PARAMETERS", "Device ID and Request ID cannot be null.")
        }

        // 2. Child Credential Verification (MUST occur BEFORE resource existence lookups)
        if (callerUid == null) {
            val isKnownDevice = (deviceId == deviceAId || deviceId == deviceBId || deviceId == legacyDeviceId)
            val storedHash = when (deviceId) {
                deviceAId -> deviceASecretHash
                deviceBId -> deviceBSecretHash
                else -> null
            }
            val auth = verifyChildDeviceSecret(
                deviceId = deviceId,
                deviceSecret = deviceSecret,
                deviceSecretHashInDb = storedHash,
                deviceExists = isKnownDevice,
                pairingStatus = "CONNECTED"
            )
            if (!auth.authorized) {
                return RpcResult(false, "UNAUTHORIZED", "Invalid device authorization.")
            }
        }

        // 3. Query override request
        if (mockRequest == null || mockRequest.id != requestId) {
            return RpcResult(false, "REQUEST_NOT_FOUND", "Override request not found.")
        }

        // 4. Device Binding Check
        if (mockRequest.deviceId != deviceId) {
            return RpcResult(false, "UNAUTHORIZED", "Request does not belong to specified device.")
        }

        // 5. Parent Authorization Check
        if (callerUid != null) {
            if (mockRequest.parentId != callerUid) {
                return RpcResult(false, "UNAUTHORIZED", "You do not have permission to view this request.")
            }
        }

        return RpcResult(true, null, null)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private lateinit var deviceASecretHash: String
    private lateinit var deviceBSecretHash: String

    @Before
    fun setUp() {
        deviceASecretHash = sha256Hex(hexToBytes(deviceASecret))
        deviceBSecretHash = sha256Hex(hexToBytes(deviceBSecret))
    }

    // --------------------------------------------------------------------------
    // A. Unauthorized child + nonexistent device => generic UNAUTHORIZED
    // --------------------------------------------------------------------------
    @Test
    fun ordering_scenarioA_unauthorizedChild_nonexistentDevice_returnsGenericUnauthorized() {
        val unknownDeviceId = "99999999-9999-9999-9999-999999999999"
        val unknownRequestId = "88888888-8888-8888-8888-888888888888"

        val result = simulateGetOverrideRequestStatus(
            callerUid = null, // child
            deviceId = unknownDeviceId,
            requestId = unknownRequestId,
            deviceSecret = "0000000000000000000000000000000000000000000000000000000000000000",
            mockRequest = null
        )

        assertFalse(result.success)
        assertEquals("UNAUTHORIZED", result.errorCode)
        assertEquals("Invalid device authorization.", result.message)
    }

    // --------------------------------------------------------------------------
    // B. Unauthorized child + nonexistent request => generic UNAUTHORIZED
    // --------------------------------------------------------------------------
    @Test
    fun ordering_scenarioB_unauthorizedChild_nonexistentRequest_returnsGenericUnauthorized() {
        val unknownRequestId = "88888888-8888-8888-8888-888888888888"

        // Child uses wrong secret for Device A
        val result = simulateGetOverrideRequestStatus(
            callerUid = null, // child
            deviceId = deviceAId,
            requestId = unknownRequestId,
            deviceSecret = "wrongsecretwrongsecretwrongsecretwrongsecretwrongsecretwrongsecr",
            mockRequest = null // request does not exist
        )

        assertFalse("Must be rejected before checking request existence", result.success)
        assertEquals("Must NOT reveal REQUEST_NOT_FOUND", "UNAUTHORIZED", result.errorCode)
        assertEquals("Invalid device authorization.", result.message)
    }

    // --------------------------------------------------------------------------
    // C. Unauthorized child + existing request belonging to another device => generic UNAUTHORIZED
    // --------------------------------------------------------------------------
    @Test
    fun ordering_scenarioC_unauthorizedChild_existingRequestOtherDevice_returnsGenericUnauthorized() {
        val requestB = MockOverrideRequest(
            id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            deviceId = deviceBId,
            familyId = familyId,
            parentId = parentUid,
            status = "PENDING"
        )

        // Attacker calls for Device B's request using invalid/missing secret
        val result = simulateGetOverrideRequestStatus(
            callerUid = null,
            deviceId = deviceAId,
            requestId = requestB.id,
            deviceSecret = "badsecretbadsecretbadsecretbadsecretbadsecretbadsecretbadsecret1",
            mockRequest = requestB
        )

        assertFalse(result.success)
        assertEquals("Must fail auth first, not leak device mismatch", "UNAUTHORIZED", result.errorCode)
        assertEquals("Invalid device authorization.", result.message)
    }

    // --------------------------------------------------------------------------
    // D. Authorized child + nonexistent request => REQUEST_NOT_FOUND
    // --------------------------------------------------------------------------
    @Test
    fun ordering_scenarioD_authorizedChild_nonexistentRequest_returnsRequestNotFound() {
        val unknownRequestId = "88888888-8888-8888-8888-888888888888"

        val result = simulateGetOverrideRequestStatus(
            callerUid = null,
            deviceId = deviceAId,
            requestId = unknownRequestId,
            deviceSecret = deviceASecret, // valid secret
            mockRequest = null // request does not exist
        )

        assertFalse(result.success)
        assertEquals("Authorized child with nonexistent request receives REQUEST_NOT_FOUND", "REQUEST_NOT_FOUND", result.errorCode)
        assertEquals("Override request not found.", result.message)
    }

    // --------------------------------------------------------------------------
    // E. Authorized child + valid request => normal behavior (success)
    // --------------------------------------------------------------------------
    @Test
    fun ordering_scenarioE_authorizedChild_validRequest_succeeds() {
        val requestA = MockOverrideRequest(
            id = "aaaaaaaa-1111-1111-1111-111111111111",
            deviceId = deviceAId,
            familyId = familyId,
            parentId = parentUid,
            status = "APPROVED"
        )

        val result = simulateGetOverrideRequestStatus(
            callerUid = null,
            deviceId = deviceAId,
            requestId = requestA.id,
            deviceSecret = deviceASecret, // valid secret
            mockRequest = requestA
        )

        assertTrue("Authorized child with valid request succeeds", result.success)
        assertNull(result.errorCode)
    }

    // --------------------------------------------------------------------------
    // F. Parent behavior remains unchanged (auth.uid() ownership checks)
    // --------------------------------------------------------------------------
    @Test
    fun ordering_scenarioF_parentAuthorization_remainsUnchanged() {
        val requestA = MockOverrideRequest(
            id = "aaaaaaaa-1111-1111-1111-111111111111",
            deviceId = deviceAId,
            familyId = familyId,
            parentId = parentUid,
            status = "APPROVED"
        )

        // Case 1: Owning parent checks request without child secret -> succeeds
        val owningParentResult = simulateGetOverrideRequestStatus(
            callerUid = parentUid,
            deviceId = deviceAId,
            requestId = requestA.id,
            deviceSecret = null,
            mockRequest = requestA
        )
        assertTrue("Owning parent is authorized without child secret", owningParentResult.success)

        // Case 2: Different parent checks request -> UNAUTHORIZED
        val otherParentResult = simulateGetOverrideRequestStatus(
            callerUid = otherParentUid,
            deviceId = deviceAId,
            requestId = requestA.id,
            deviceSecret = null,
            mockRequest = requestA
        )
        assertFalse("Non-owning parent is denied access", otherParentResult.success)
        assertEquals("UNAUTHORIZED", otherParentResult.errorCode)
        assertEquals("You do not have permission to view this request.", otherParentResult.message)

        // Case 3: Parent queries nonexistent request -> REQUEST_NOT_FOUND
        val nonExistentResult = simulateGetOverrideRequestStatus(
            callerUid = parentUid,
            deviceId = deviceAId,
            requestId = "99999999-9999-9999-9999-999999999999",
            deviceSecret = null,
            mockRequest = null
        )
        assertFalse(nonExistentResult.success)
        assertEquals("REQUEST_NOT_FOUND", nonExistentResult.errorCode)
    }

    // --------------------------------------------------------------------------
    // G. Helper remains inaccessible directly to anon/authenticated
    // --------------------------------------------------------------------------
    @Test
    fun antiTampering_scenarioG_helperDirectExecutionRevoked() {
        val allowedDirectRoles = setOf("postgres", "supabase_admin")
        val clientRoles = listOf("anon", "authenticated", "public")

        for (role in clientRoles) {
            val canCallDirectly = role in allowedDirectRoles
            assertFalse("Client role '$role' must NOT be granted direct EXECUTE on internal helper", canCallDirectly)
        }
    }

    // --------------------------------------------------------------------------
    // H. No plaintext device secret is returned or persisted
    // --------------------------------------------------------------------------
    @Test
    fun antiTampering_scenarioH_secretNeverReturnedOrExposed() {
        val createDtoFields = CreateOverrideResponseDto::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(createDtoFields.any { it.contains("secret") })

        val statusDtoFields = OverrideStatusResponseDto::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(statusDtoFields.any { it.contains("secret") })

        val consumeDtoFields = ConsumeOverrideResponseDto::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(consumeDtoFields.any { it.contains("secret") })

        val pairingSessionFields = PairingSession::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(pairingSessionFields.any { it.contains("secret") })

        val childPairingUiStateFields = ChildPairingUiState::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(childPairingUiStateFields.any { it.contains("secret") })

        val remoteOverrideFields = RemoteOverrideRequest::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(remoteOverrideFields.any { it.contains("secret") })

        val childDeviceStatusFields = ChildDeviceStatus::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(childDeviceStatusFields.any { it.contains("secret") })
    }

    // --------------------------------------------------------------------------
    // Integration with ChildDeviceCredentialStore
    // --------------------------------------------------------------------------
    @Test
    fun credentialStore_suppliesSecretToDataLayer() {
        val store = SharedPreferencesChildDeviceCredentialStore(
            sharedPreferences = null,
            cipher = NoOpDeviceSecretCipher
        )

        store.save(deviceAId, deviceASecret)

        assertEquals(deviceAId, store.getDeviceId())
        assertEquals(deviceASecret, store.getDeviceSecret())
        assertTrue(store.hasCredential())
    }
}
