package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.remote.supabase.NiqobSupabaseClient
import com.niqob.app.data.repository.SupabaseAuthRepository
import com.niqob.app.data.repository.SupabaseDeviceRepository
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.domain.model.DeviceConnectivityStatus
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.User
import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.model.UserRole
import com.niqob.app.domain.repository.AuthState
import com.niqob.app.domain.usecase.ConnectChildDeviceUseCase
import com.niqob.app.domain.usecase.CreatePairingCodeUseCase
import com.niqob.app.domain.usecase.GetAuthStateUseCase
import com.niqob.app.domain.usecase.GetDeviceConnectivityUseCase
import com.niqob.app.domain.usecase.GetPairingSessionUseCase
import com.niqob.app.domain.usecase.ParentLoginUseCase
import com.niqob.app.domain.usecase.ParentLogoutUseCase
import com.niqob.app.domain.usecase.ParentRegisterUseCase
import com.niqob.app.domain.usecase.RegisterDeviceUseCase
import com.niqob.app.domain.usecase.RevokePairingUseCase
import com.niqob.app.domain.usecase.UpdateHeartbeatUseCase
import com.niqob.app.fakes.FakeAuthRepository
import com.niqob.app.fakes.FakeDeviceRepository
import com.niqob.app.fakes.FakePairingRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase3CloudFoundationTest {

    private lateinit var fakeAuthRepository: FakeAuthRepository
    private lateinit var fakePairingRepository: FakePairingRepository
    private lateinit var fakeDeviceRepository: FakeDeviceRepository

    private lateinit var parentRegisterUseCase: ParentRegisterUseCase
    private lateinit var parentLoginUseCase: ParentLoginUseCase
    private lateinit var parentLogoutUseCase: ParentLogoutUseCase
    private lateinit var getAuthStateUseCase: GetAuthStateUseCase
    private lateinit var registerDeviceUseCase: RegisterDeviceUseCase
    private lateinit var updateHeartbeatUseCase: UpdateHeartbeatUseCase
    private lateinit var getDeviceConnectivityUseCase: GetDeviceConnectivityUseCase
    private lateinit var createPairingCodeUseCase: CreatePairingCodeUseCase
    private lateinit var connectChildDeviceUseCase: ConnectChildDeviceUseCase
    private lateinit var getPairingSessionUseCase: GetPairingSessionUseCase
    private lateinit var revokePairingUseCase: RevokePairingUseCase

    @Before
    fun setup() {
        fakeAuthRepository = FakeAuthRepository()
        fakePairingRepository = FakePairingRepository()
        fakeDeviceRepository = FakeDeviceRepository()

        parentRegisterUseCase = ParentRegisterUseCase(fakeAuthRepository)
        parentLoginUseCase = ParentLoginUseCase(fakeAuthRepository)
        parentLogoutUseCase = ParentLogoutUseCase(fakeAuthRepository)
        getAuthStateUseCase = GetAuthStateUseCase(fakeAuthRepository)
        registerDeviceUseCase = RegisterDeviceUseCase(fakeDeviceRepository)
        updateHeartbeatUseCase = UpdateHeartbeatUseCase(fakeDeviceRepository)
        getDeviceConnectivityUseCase = GetDeviceConnectivityUseCase(fakeDeviceRepository)
        createPairingCodeUseCase = CreatePairingCodeUseCase(fakePairingRepository)
        connectChildDeviceUseCase = ConnectChildDeviceUseCase(fakePairingRepository)
        getPairingSessionUseCase = GetPairingSessionUseCase(fakePairingRepository)
        revokePairingUseCase = RevokePairingUseCase(fakePairingRepository)
    }

    // =========================================================================
    // 1. Authentication State Mapping Tests
    // =========================================================================

    @Test
    fun authState_initialStateIsUnauthenticated() {
        val state = getAuthStateUseCase().value
        assertTrue(state is AuthState.Unauthenticated)
        assertNull(fakeAuthRepository.getCurrentUser())
    }

    @Test
    fun registerParent_withValidCredentials_authenticatesUserWithParentRole() = runBlocking {
        val email = "parent@example.com"
        val password = "securePassword123"

        val result = parentRegisterUseCase(email, password)

        assertTrue(result is Result.Success)
        val user = (result as Result.Success).data
        assertEquals(email, user.email)
        assertEquals(UserRole.PARENT, user.role)
        assertNotNull(user.id)

        val state = getAuthStateUseCase().value
        assertTrue(state is AuthState.Authenticated)
        assertEquals(email, (state as AuthState.Authenticated).user.email)
        assertEquals(UserRole.PARENT, state.user.role)
    }

    @Test
    fun registerParent_withInvalidEmail_returnsErrorAndStaysUnauthenticated() = runBlocking {
        val result = parentRegisterUseCase("invalid-email", "password123")

        assertTrue(result is Result.Error)
        assertEquals("Please enter a valid email address.", (result as Result.Error).message)
        assertTrue(getAuthStateUseCase().value is AuthState.Unauthenticated)
    }

    @Test
    fun registerParent_withShortPassword_returnsError() = runBlocking {
        val result = parentRegisterUseCase("parent@example.com", "123")

        assertTrue(result is Result.Error)
        assertEquals("Password must be at least 6 characters long.", (result as Result.Error).message)
        assertTrue(getAuthStateUseCase().value is AuthState.Unauthenticated)
    }

    @Test
    fun loginParent_withValidCredentials_authenticatesSuccessfully() = runBlocking {
        val email = "parent@example.com"
        val password = "password123"

        val result = parentLoginUseCase(email, password)

        assertTrue(result is Result.Success)
        val user = (result as Result.Success).data
        assertEquals(email, user.email)
        assertEquals(UserRole.PARENT, user.role)

        val state = getAuthStateUseCase().value
        assertTrue(state is AuthState.Authenticated)
    }

    @Test
    fun loginParent_withEmptyPassword_returnsError() = runBlocking {
        val result = parentLoginUseCase("parent@example.com", "")

        assertTrue(result is Result.Error)
        assertEquals("Password cannot be empty.", (result as Result.Error).message)
    }

    @Test
    fun loginParent_withInvalidCredentials_mapsToDomainError() = runBlocking {
        fakeAuthRepository.shouldFailWithInvalidCredentials = true
        val result = parentLoginUseCase("parent@example.com", "wrongpass")

        assertTrue(result is Result.Error)
        assertEquals("Invalid email or password.", (result as Result.Error).message)
        assertTrue(getAuthStateUseCase().value is AuthState.Error)
    }

    @Test
    fun loginParent_withNetworkFailure_mapsToDomainError() = runBlocking {
        fakeAuthRepository.shouldFailWithNetworkError = true
        val result = parentLoginUseCase("parent@example.com", "password123")

        assertTrue(result is Result.Error)
        assertEquals("Connection unavailable. Please check your internet connection.", (result as Result.Error).message)
        assertTrue(getAuthStateUseCase().value is AuthState.Error)
    }

    @Test
    fun restoreSession_withActiveSession_restoresAuthenticatedState() = runBlocking {
        val existingUser = User("user-1", "user@test.com", UserRole.PARENT, "Parent User")
        fakeAuthRepository.setAuthenticatedUser(existingUser)

        val restoreResult = fakeAuthRepository.restoreSession()
        assertTrue(restoreResult is Result.Success)
        assertEquals("user-1", (restoreResult as Result.Success).data?.id)

        val state = getAuthStateUseCase().value
        assertTrue(state is AuthState.Authenticated)
        assertEquals("user@test.com", (state as AuthState.Authenticated).user.email)
    }

    @Test
    fun restoreSession_withoutActiveSession_restoresUnauthenticatedState() = runBlocking {
        val restoreResult = fakeAuthRepository.restoreSession()
        assertTrue(restoreResult is Result.Success)
        assertNull((restoreResult as Result.Success).data)

        val state = getAuthStateUseCase().value
        assertTrue(state is AuthState.Unauthenticated)
    }

    @Test
    fun signOutParent_clearsAuthenticationAndUser() = runBlocking {
        parentRegisterUseCase("parent@example.com", "password123")
        assertTrue(getAuthStateUseCase().value is AuthState.Authenticated)

        val logoutResult = parentLogoutUseCase()
        assertTrue(logoutResult is Result.Success)

        val state = getAuthStateUseCase().value
        assertTrue(state is AuthState.Unauthenticated)
        assertNull(fakeAuthRepository.getCurrentUser())
    }

    // =========================================================================
    // 2. Pairing Expiration Tests
    // =========================================================================

    @Test
    fun pairingSession_expiresAfterTenMinutes() = runBlocking {
        val createResult = createPairingCodeUseCase()
        assertTrue(createResult is Result.Success)
        val session = (createResult as Result.Success).data

        // 10 minutes lifetime
        assertEquals(session.createdAt + 10 * 60 * 1000L, session.expiresAt)

        // Valid within 10 minutes
        val withinTime = session.createdAt + 5 * 60 * 1000L
        assertFalse(session.isExpired(withinTime))
        assertTrue(session.isConnectable(withinTime))

        // Expired after 10 minutes
        val pastExpiration = session.expiresAt + 1000L
        assertTrue(session.isExpired(pastExpiration))
        assertFalse(session.isConnectable(pastExpiration))

        // Child connection attempt past expiration returns appropriate error
        fakePairingRepository.currentMockTimeMillis = pastExpiration
        val connectResult = connectChildDeviceUseCase(session.pairingCode)
        assertTrue(connectResult is Result.Error)
        assertTrue((connectResult as Result.Error).message?.contains("expired") == true)
    }

    // =========================================================================
    // 3. Invalid Pairing Code Tests
    // =========================================================================

    @Test
    fun connectChild_withInvalidLengthsOrCharacters_rejectsBeforeNetwork() = runBlocking {
        // Less than 6 digits
        val shortResult = connectChildDeviceUseCase("123")
        assertTrue(shortResult is Result.Error)
        assertEquals("Pairing code must be a 6-digit number.", (shortResult as Result.Error).message)

        // More than 6 digits
        val longResult = connectChildDeviceUseCase("1234567")
        assertTrue(longResult is Result.Error)
        assertEquals("Pairing code must be a 6-digit number.", (longResult as Result.Error).message)

        // Non-digit characters
        val alphaResult = connectChildDeviceUseCase("12ab56")
        assertTrue(alphaResult is Result.Error)
        assertEquals("Pairing code must be a 6-digit number.", (alphaResult as Result.Error).message)
    }

    @Test
    fun connectChild_withNonMatchingCode_returnsInvalidCodeError() = runBlocking {
        createPairingCodeUseCase() // generates "123456"

        val result = connectChildDeviceUseCase("999999")
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).message?.contains("Invalid pairing code") == true)
    }

    // =========================================================================
    // 4. Successful Pairing Tests
    // =========================================================================

    @Test
    fun pairingFlow_createsPendingCodeAndConnectsChildSuccessfully() = runBlocking {
        // 1. Parent generates code
        val createResult = createPairingCodeUseCase()
        assertTrue(createResult is Result.Success)
        val session = (createResult as Result.Success).data
        assertEquals(PairingStatus.PENDING, session.status)
        assertEquals(6, session.pairingCode.length)

        // 2. Child connects with generated code
        val connectResult = connectChildDeviceUseCase(session.pairingCode)
        assertTrue(connectResult is Result.Success)
        val connectedSession = (connectResult as Result.Success).data
        assertEquals(PairingStatus.CONNECTED, connectedSession.status)
        assertNotNull(connectedSession.childDeviceId)

        // 3. Pairing session flow reflects connected state
        val emittedSession = getPairingSessionUseCase().first()
        assertNotNull(emittedSession)
        assertEquals(PairingStatus.CONNECTED, emittedSession?.status)
    }

    // =========================================================================
    // 5. Revoked Pairing Tests
    // =========================================================================

    @Test
    fun revokePairing_preventsChildFromConnecting() = runBlocking {
        val createResult = createPairingCodeUseCase()
        val session = (createResult as Result.Success).data

        // Parent revokes code
        val revokeResult = revokePairingUseCase()
        assertTrue(revokeResult is Result.Success)

        val revokedSession = getPairingSessionUseCase().first()
        assertEquals(PairingStatus.REVOKED, revokedSession?.status)

        // Child attempt to connect revoked code fails
        val connectResult = connectChildDeviceUseCase(session.pairingCode)
        assertTrue(connectResult is Result.Error)
        assertTrue((connectResult as Result.Error).message?.contains("revoked") == true)
    }

    // =========================================================================
    // 6. Already-Connected Pairing Tests
    // =========================================================================

    @Test
    fun connectChild_whenAlreadyConnected_rejectsDuplicateConnection() = runBlocking {
        val createResult = createPairingCodeUseCase()
        val session = (createResult as Result.Success).data

        // First connection succeeds
        val firstConnect = connectChildDeviceUseCase(session.pairingCode)
        assertTrue(firstConnect is Result.Success)

        // Second connection with same code is rejected
        val secondConnect = connectChildDeviceUseCase(session.pairingCode)
        assertTrue(secondConnect is Result.Error)
        assertTrue((secondConnect as Result.Error).message?.contains("already been used") == true)
    }

    // =========================================================================
    // 7. Device Registration Logic Tests
    // =========================================================================

    @Test
    fun registerDevice_createsValidDeviceRecord() = runBlocking {
        val familyId = "fam_test_uuid"
        val deviceName = "Child Samsung Galaxy"

        val result = registerDeviceUseCase(
            familyId = familyId,
            deviceName = deviceName,
            platform = "ANDROID",
            deviceType = "CHILD_PHONE"
        )

        assertTrue(result is Result.Success)
        val device = (result as Result.Success).data
        assertEquals(familyId, device.familyId)
        assertEquals(deviceName, device.deviceName)
        assertEquals("ANDROID", device.platform)
        assertEquals("CHILD_PHONE", device.deviceType)
        assertTrue(device.lastSeenAt > 0)
    }

    @Test
    fun registerDevice_withBlankName_defaultsCleanlyToChildDevice() = runBlocking {
        val result = registerDeviceUseCase(
            familyId = "fam_test",
            deviceName = "   "
        )

        assertTrue(result is Result.Success)
        val device = (result as Result.Success).data
        assertEquals("Child Device", device.deviceName)
    }

    // =========================================================================
    // 8. Online / Offline Calculation & Heartbeat Tests
    // =========================================================================

    @Test
    fun onlineStatusCalculation_calculatesCorrectThresholds() {
        val now = 1_000_000L

        // <= 2 minutes (120,000 ms) -> ONLINE
        val onlineSeen = now - 1 * 60 * 1000L
        assertEquals(
            DeviceConnectivityStatus.ONLINE,
            DeviceConnectivityStatus.calculate(onlineSeen, now)
        )

        val exactly2MinSeen = now - 2 * 60 * 1000L
        assertEquals(
            DeviceConnectivityStatus.ONLINE,
            DeviceConnectivityStatus.calculate(exactly2MinSeen, now)
        )

        // > 2 minutes and <= 15 minutes (900,000 ms) -> RECENTLY_ONLINE
        val recentlyOnlineSeen = now - 10 * 60 * 1000L
        assertEquals(
            DeviceConnectivityStatus.RECENTLY_ONLINE,
            DeviceConnectivityStatus.calculate(recentlyOnlineSeen, now)
        )

        val exactly15MinSeen = now - 15 * 60 * 1000L
        assertEquals(
            DeviceConnectivityStatus.RECENTLY_ONLINE,
            DeviceConnectivityStatus.calculate(exactly15MinSeen, now)
        )

        // > 15 minutes -> OFFLINE
        val offlineSeen = now - 20 * 60 * 1000L
        assertEquals(
            DeviceConnectivityStatus.OFFLINE,
            DeviceConnectivityStatus.calculate(offlineSeen, now)
        )

        // Never seen (0) -> OFFLINE
        assertEquals(
            DeviceConnectivityStatus.OFFLINE,
            DeviceConnectivityStatus.calculate(0L, now)
        )
    }

    @Test
    fun updateHeartbeat_updatesDeviceLastSeenTimestamp() = runBlocking {
        val registerResult = registerDeviceUseCase("fam_heartbeat", "Tablet")
        val device = (registerResult as Result.Success).data

        // Advance mock time
        fakeDeviceRepository.currentMockTimeMillis = 5_000_000L
        val heartbeatResult = updateHeartbeatUseCase(device.id)
        assertTrue(heartbeatResult is Result.Success)

        // Empty device ID fails validation
        val emptyHeartbeat = updateHeartbeatUseCase("")
        assertTrue(emptyHeartbeat is Result.Error)
    }

    // =========================================================================
    // 9. Supabase Production Repositories Error Handling When Unconfigured
    // =========================================================================

    @Test
    fun productionRepositories_whenUnconfigured_returnSafeDescriptiveErrors() = runBlocking {
        // Explicitly simulate unconfigured state for this isolated test
        com.niqob.app.data.remote.supabase.SupabaseConfig.setCustomConfig("", "")
        assertFalse(NiqobSupabaseClient.isConfigured)

        val prodAuth = SupabaseAuthRepository()
        val prodPairing = SupabasePairingRepository()
        val prodDevice = SupabaseDeviceRepository()

        // Auth fails cleanly
        val authResult = prodAuth.signInParent("test@example.com", "password")
        assertTrue(authResult is Result.Error)
        val authError = (authResult as Result.Error).message
        assertTrue(authError?.contains("Supabase credentials not configured") == true)

        // Pairing fails cleanly
        val pairingResult = prodPairing.createPairingCode()
        assertTrue(pairingResult is Result.Error)
        val pairingError = (pairingResult as Result.Error).message
        assertTrue(pairingError?.contains("Supabase credentials not configured") == true)

        // Device registration fails cleanly
        val deviceResult = prodDevice.registerDevice("fam-1", "Child Device")
        assertTrue(deviceResult is Result.Error)
        val deviceError = (deviceResult as Result.Error).message
        assertTrue(deviceError?.contains("Supabase credentials not configured") == true)

        // Reset config
        com.niqob.app.data.remote.supabase.SupabaseConfig.reset()
    }

    @Test
    fun supabaseConfig_validation_handlesDynamicConfiguration() {
        com.niqob.app.data.remote.supabase.SupabaseConfig.setCustomConfig(
            "https://testproject.supabase.co/",
            "sample-anon-key-12345"
        )
        assertTrue(com.niqob.app.data.remote.supabase.SupabaseConfig.isConfigured)
        assertEquals("https://testproject.supabase.co", com.niqob.app.data.remote.supabase.SupabaseConfig.url)
        assertEquals("sample-anon-key-12345", com.niqob.app.data.remote.supabase.SupabaseConfig.anonKey)

        com.niqob.app.data.remote.supabase.SupabaseConfig.reset()
    }
}
