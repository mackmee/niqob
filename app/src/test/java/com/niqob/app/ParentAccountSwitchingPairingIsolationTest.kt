package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.NoOpDeviceSecretCipher
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.domain.model.Device
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.User
import com.niqob.app.domain.model.UserRole
import com.niqob.app.domain.usecase.GetPairingSessionUseCase
import com.niqob.app.domain.usecase.ParentLogoutUseCase
import com.niqob.app.fakes.FakeAuthRepository
import com.niqob.app.fakes.FakeDeviceRepository
import com.niqob.app.fakes.FakePairingRepository
import com.niqob.app.fakes.FakeScreenTimeRepository
import com.niqob.app.feature.parent.ParentDashboardViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ParentAccountSwitchingPairingIsolationTest {

    private lateinit var fakeScreenTimeRepository: FakeScreenTimeRepository
    private lateinit var fakeAuthRepository: FakeAuthRepository
    private lateinit var fakeDeviceRepository: FakeDeviceRepository
    private lateinit var fakePairingRepository: FakePairingRepository
    private lateinit var viewModel: ParentDashboardViewModel
    private lateinit var childCredentialStore: SharedPreferencesChildDeviceCredentialStore

    @Before
    fun setup() {
        fakeScreenTimeRepository = FakeScreenTimeRepository(defaultFamilyId = "family_alpha")
        fakeAuthRepository = FakeAuthRepository()
        fakeDeviceRepository = FakeDeviceRepository()
        fakePairingRepository = FakePairingRepository()

        // Independent in-memory store simulating child-device credentials
        childCredentialStore = SharedPreferencesChildDeviceCredentialStore(
            sharedPreferences = null,
            cipher = NoOpDeviceSecretCipher
        )

        viewModel = ParentDashboardViewModel(
            pairingRepository = fakePairingRepository,
            getPairingSessionUseCase = GetPairingSessionUseCase(fakePairingRepository),
            authRepository = fakeAuthRepository,
            deviceRepository = fakeDeviceRepository,
            logoutUseCase = ParentLogoutUseCase(fakeAuthRepository),
            screenTimeRepository = fakeScreenTimeRepository
        )
    }

    /**
     * Test A — Logout clears the previous parent's pairing cache
     * - Parent A is logged in with an active pairing session.
     * - Child device has stored credentials in ChildDeviceCredentialStore.
     * - Parent A logs out.
     * - Verified: exposed pairing state becomes null, UI state resets to disconnected,
     *   and child-device credentials in ChildDeviceCredentialStore are NOT erased.
     */
    @Test
    fun testParentLogout_clearsCachedPairingSession_withoutClearingChildCredentials() = runBlocking {
        // 1. Seed Parent A session
        val parentAUser = User(id = "parent-A", email = "parentA@example.com", role = UserRole.PARENT)
        fakeAuthRepository.setAuthenticatedUser(parentAUser)

        val parentASession = PairingSession(
            pairingId = "session-A-123",
            parentDeviceId = "parent-dev-A",
            childDeviceId = "child-device-A-uuid",
            pairingCode = "111222",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600000,
            status = PairingStatus.CONNECTED,
            familyId = "family_alpha"
        )
        fakePairingRepository.setSessionDirectly(parentASession)

        // 2. Child device has its own cryptographic credentials stored
        childCredentialStore.save("child-device-A-uuid", "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90")
        assertTrue(childCredentialStore.hasCredential())

        // 3. Update dashboard to observe Parent A state
        viewModel.syncRemoteData()
        assertEquals("child-device-A-uuid", viewModel.uiState.value.childDeviceId)
        assertTrue(viewModel.uiState.value.childStatus.isConnected)

        // 4. Execute parent logout (testing logoutInternal directly and verifying callback behavior)
        viewModel.logoutInternal()

        // 5. Assert Parent pairing state is cleared
        assertNull("Pairing repository session must be null after logout", fakePairingRepository.getPairingSession().first())
        assertNull("Dashboard pairingSession must be null after logout", viewModel.uiState.value.pairingSession)
        assertNull("Dashboard childDeviceId must be null after logout", viewModel.uiState.value.childDeviceId)
        assertFalse("Dashboard isConnected must be false after logout", viewModel.uiState.value.childStatus.isConnected)
        assertEquals("Not connected", viewModel.uiState.value.childStatus.deviceName)

        // 6. Assert child-device credentials were NOT erased by parent logout
        assertTrue("ChildDeviceCredentialStore must retain credentials", childCredentialStore.hasCredential())
        assertEquals("child-device-A-uuid", childCredentialStore.getDeviceId())
        assertNotNull(childCredentialStore.getDeviceSecret())
    }

    /**
     * Test B — Parent B with no registered children
     * - Seed repository with Parent A's pairing state.
     * - Parent A logs out; Parent B logs in.
     * - Server returns an empty list for Parent B.
     * - Verified: Parent A's child device ID and name are NOT exposed.
     *   Connected state is false and pairing session is null.
     */
    @Test
    fun testParentBLogin_withNoRegisteredChildren_doesNotExposeParentAData() = runBlocking {
        // 1. Seed Parent A's data
        val parentASession = PairingSession(
            pairingId = "session-A-999",
            parentDeviceId = "parent-dev-A",
            childDeviceId = "parent-A-secret-child-device",
            pairingCode = "333444",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600000,
            status = PairingStatus.CONNECTED,
            familyId = "family_alpha"
        )
        fakePairingRepository.setSessionDirectly(parentASession)

        // 2. Parent A logs out
        viewModel.logoutInternal()
        assertNull(fakePairingRepository.getPairingSession().first())

        // 3. Parent B authenticates
        val parentBUser = User(id = "parent-B", email = "parentB@example.com", role = UserRole.PARENT)
        fakeAuthRepository.setAuthenticatedUser(parentBUser)

        // 4. Server returns empty list for Parent B (no registered devices or sessions)
        fakePairingRepository.syncReturnsEmpty = true
        fakeDeviceRepository.setDevicesDirectly(emptyList())

        // 5. Create new ViewModel for Parent B's session
        val parentBViewModel = ParentDashboardViewModel(
            pairingRepository = fakePairingRepository,
            getPairingSessionUseCase = GetPairingSessionUseCase(fakePairingRepository),
            authRepository = fakeAuthRepository,
            deviceRepository = fakeDeviceRepository,
            logoutUseCase = ParentLogoutUseCase(fakeAuthRepository),
            screenTimeRepository = fakeScreenTimeRepository
        )

        parentBViewModel.syncRemoteData()

        // 6. Assert Parent B sees zero trace of Parent A's child device
        val state = parentBViewModel.uiState.value
        assertEquals("parentB@example.com", state.user?.email)
        assertNull("Parent A child device must NOT leak to Parent B", state.childDeviceId)
        assertNull("Parent A pairing session must NOT leak to Parent B", state.pairingSession)
        assertFalse("Parent B has no devices, connected must be false", state.childStatus.isConnected)
        assertEquals("Not connected", state.childStatus.deviceName)
    }

    /**
     * Test C — Parent B with its own registered child
     * - Simulate account switching from Parent A to Parent B.
     * - Server returns Parent B's legitimate server-side pairing and device data.
     * - Verified: Parent B's data is displayed; Parent A's data is completely absent.
     */
    @Test
    fun testParentBLogin_withOwnRegisteredChild_displaysParentBData_andParentAAbsent() = runBlocking {
        // 1. Seed Parent A
        fakePairingRepository.setSessionDirectly(
            PairingSession(
                pairingId = "session-A",
                parentDeviceId = "parent-dev-A",
                childDeviceId = "parent-A-child-uuid",
                pairingCode = "111111",
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 600000,
                status = PairingStatus.CONNECTED,
                familyId = "family_alpha"
            )
        )
        viewModel.logoutInternal()

        // 2. Parent B authenticates with its own child device
        val parentBUser = User(id = "parent-B", email = "parentB@example.com", role = UserRole.PARENT)
        fakeAuthRepository.setAuthenticatedUser(parentBUser)

        val parentBChildDeviceId = "parent-B-legitimate-child-uuid"
        val parentBDevice = Device(
            id = parentBChildDeviceId,
            familyId = "family_beta",
            userId = null,
            deviceName = "Parent B Tablet",
            platform = "ANDROID",
            deviceType = "CHILD_PHONE",
            lastSeenAt = System.currentTimeMillis()
        )
        fakeDeviceRepository.setDevicesDirectly(listOf(parentBDevice))

        val parentBSession = PairingSession(
            pairingId = "session-B-777",
            parentDeviceId = "parent-dev-B",
            childDeviceId = parentBChildDeviceId,
            pairingCode = "777888",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600000,
            status = PairingStatus.CONNECTED,
            familyId = "family_beta"
        )
        fakePairingRepository.syncReturnsEmpty = false
        fakePairingRepository.setSessionDirectly(parentBSession)

        val parentBViewModel = ParentDashboardViewModel(
            pairingRepository = fakePairingRepository,
            getPairingSessionUseCase = GetPairingSessionUseCase(fakePairingRepository),
            authRepository = fakeAuthRepository,
            deviceRepository = fakeDeviceRepository,
            logoutUseCase = ParentLogoutUseCase(fakeAuthRepository),
            screenTimeRepository = fakeScreenTimeRepository
        )

        parentBViewModel.syncRemoteData()

        // 3. Verify Parent B's legitimate data is present and Parent A's is absent
        val state = parentBViewModel.uiState.value
        assertEquals("parentB@example.com", state.user?.email)
        assertEquals(parentBChildDeviceId, state.childDeviceId)
        assertEquals("Parent B Tablet", state.childStatus.deviceName)
        assertTrue(state.childStatus.isConnected)
        assertFalse(state.childDeviceId == "parent-A-child-uuid")
    }

    /**
     * Test D — Empty results versus network failure
     * - Successful query returning empty list: clears stale session to null.
     * - Transient network failure: returns Result.Error and preserves existing state without crash.
     */
    @Test
    fun testSyncPairingSession_emptyServerResponseClearsStaleState_whileNetworkErrorPreservesState() = runBlocking {
        val staleSession = PairingSession(
            pairingId = "stale-session-123",
            parentDeviceId = "parent-dev-old",
            childDeviceId = "child-old",
            pairingCode = "999888",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600000,
            status = PairingStatus.CONNECTED,
            familyId = "family_old"
        )

        // 1. Case: Successful server response with empty session list
        fakePairingRepository.setSessionDirectly(staleSession)
        fakePairingRepository.syncReturnsEmpty = true
        fakePairingRepository.syncFailureException = null

        val emptyResult = fakePairingRepository.syncPairingSession()
        assertTrue("Empty server query should report success", emptyResult is Result.Success)
        assertNull("Empty server query should return null session", (emptyResult as Result.Success).data)
        assertNull("Stale session in repository must be cleared to null", fakePairingRepository.getPairingSession().first())

        // 2. Case: Transient network failure during sync
        fakePairingRepository.syncReturnsEmpty = false
        val activeSession = staleSession.copy(pairingId = "active-session-456")
        fakePairingRepository.setSessionDirectly(activeSession)
        fakePairingRepository.syncFailureException = IOException("Connection refused by peer")

        val failureResult = fakePairingRepository.syncPairingSession()
        assertTrue("Network error must return Result.Error", failureResult is Result.Error)
        assertEquals("Connection refused by peer", (failureResult as Result.Error).exception.message)
        assertEquals("Transient network error must preserve last known session", activeSession, fakePairingRepository.getPairingSession().first())
    }
}
