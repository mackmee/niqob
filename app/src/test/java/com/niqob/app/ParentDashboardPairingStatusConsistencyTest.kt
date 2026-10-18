package com.niqob.app

import com.niqob.app.domain.model.Device
import com.niqob.app.domain.model.DeviceConnectivityStatus
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.usecase.GetPairingSessionUseCase
import com.niqob.app.domain.usecase.ParentLogoutUseCase
import com.niqob.app.fakes.FakeAuthRepository
import com.niqob.app.fakes.FakeDeviceRepository
import com.niqob.app.fakes.FakePairingRepository
import com.niqob.app.fakes.FakeScreenTimeRepository
import com.niqob.app.feature.parent.ParentDashboardViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParentDashboardPairingStatusConsistencyTest {

    private lateinit var fakeScreenTimeRepository: FakeScreenTimeRepository
    private lateinit var fakeAuthRepository: FakeAuthRepository
    private lateinit var fakeDeviceRepository: FakeDeviceRepository
    private lateinit var fakePairingRepository: FakePairingRepository
    private lateinit var viewModel: ParentDashboardViewModel

    @Before
    fun setup() {
        fakeScreenTimeRepository = FakeScreenTimeRepository(defaultFamilyId = "family_test")
        fakeAuthRepository = FakeAuthRepository()
        fakeDeviceRepository = FakeDeviceRepository()
        fakePairingRepository = FakePairingRepository()

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
     * Requirement 6.1: REVOKED session + existing child device -> disconnected.
     * The existence of a device row in public.devices must NEVER override an explicit REVOKED status.
     */
    @Test
    fun syncRemoteData_withRevokedSessionAndExistingChildDevice_remainsDisconnected() = runBlocking {
        val childDeviceId = "48739d38-ae9-4276-bdcd-27ff357a5ab6"
        val existingDevice = Device(
            id = childDeviceId,
            familyId = "family_test",
            userId = null,
            deviceName = "Child Phone",
            platform = "ANDROID",
            deviceType = "CHILD_PHONE",
            lastSeenAt = System.currentTimeMillis()
        )
        fakeDeviceRepository.setDevicesDirectly(listOf(existingDevice))

        val revokedSession = PairingSession(
            pairingId = "session-revoked-001",
            parentDeviceId = "parent-dev-cloud",
            childDeviceId = childDeviceId,
            pairingCode = "123456",
            createdAt = System.currentTimeMillis() - 3600000L,
            expiresAt = System.currentTimeMillis() + 600000L,
            status = PairingStatus.REVOKED,
            familyId = "family_test",
            revokedAt = System.currentTimeMillis()
        )
        fakePairingRepository.setSessionDirectly(revokedSession)

        viewModel.syncRemoteData()

        val state = viewModel.uiState.value
        assertFalse("Child status must be disconnected when session is REVOKED", state.childStatus.isConnected)
        assertEquals("Not connected", state.childStatus.deviceName)
        assertEquals("Pairing session must retain REVOKED status without synthetic CONNECTED override", PairingStatus.REVOKED, state.pairingSession?.status)
        assertEquals("session-revoked-001", state.pairingSession?.pairingId)
        assertEquals(DeviceConnectivityStatus.OFFLINE, state.connectivityStatus)
    }

    /**
     * Requirement 6.2: EXPIRED session + existing child device -> disconnected.
     * An expired code with an existing device row must not report an active connection.
     */
    @Test
    fun syncRemoteData_withExpiredSessionAndExistingChildDevice_remainsDisconnected() = runBlocking {
        val childDeviceId = "child-dev-expired-002"
        val existingDevice = Device(
            id = childDeviceId,
            familyId = "family_test",
            userId = null,
            deviceName = "Old Tablet",
            platform = "ANDROID",
            deviceType = "CHILD_PHONE",
            lastSeenAt = System.currentTimeMillis()
        )
        fakeDeviceRepository.setDevicesDirectly(listOf(existingDevice))

        val expiredSession = PairingSession(
            pairingId = "session-expired-002",
            parentDeviceId = "parent-dev-cloud",
            childDeviceId = childDeviceId,
            pairingCode = "654321",
            createdAt = System.currentTimeMillis() - 700000L,
            expiresAt = System.currentTimeMillis() - 100000L,
            status = PairingStatus.EXPIRED,
            familyId = "family_test"
        )
        fakePairingRepository.setSessionDirectly(expiredSession)

        viewModel.syncRemoteData()

        val state = viewModel.uiState.value
        assertFalse("Child status must be disconnected when session is EXPIRED", state.childStatus.isConnected)
        assertEquals("Not connected", state.childStatus.deviceName)
        assertEquals(PairingStatus.EXPIRED, state.pairingSession?.status)
        assertEquals(DeviceConnectivityStatus.OFFLINE, state.connectivityStatus)
    }

    /**
     * Requirement 6.3: CONNECTED session + matching child device -> connected.
     * A valid active pairing with a matching device row must correctly display connected.
     */
    @Test
    fun syncRemoteData_withConnectedSessionAndMatchingChildDevice_connectsSuccessfully() = runBlocking {
        val childDeviceId = "child-dev-active-003"
        val activeDevice = Device(
            id = childDeviceId,
            familyId = "family_test",
            userId = null,
            deviceName = "Alex's Tablet",
            platform = "ANDROID",
            deviceType = "CHILD_PHONE",
            lastSeenAt = System.currentTimeMillis()
        )
        fakeDeviceRepository.setDevicesDirectly(listOf(activeDevice))

        val connectedSession = PairingSession(
            pairingId = "session-active-003",
            parentDeviceId = "parent-dev-cloud",
            childDeviceId = childDeviceId,
            pairingCode = "999111",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600000L,
            status = PairingStatus.CONNECTED,
            familyId = "family_test",
            connectedAt = System.currentTimeMillis()
        )
        fakePairingRepository.setSessionDirectly(connectedSession)

        viewModel.syncRemoteData()

        val state = viewModel.uiState.value
        assertTrue("Child status must be connected when session is CONNECTED", state.childStatus.isConnected)
        assertEquals("Alex's Tablet", state.childStatus.deviceName)
        assertEquals(childDeviceId, state.childDeviceId)
        assertEquals(PairingStatus.CONNECTED, state.pairingSession?.status)
    }

    /**
     * Requirement 6.4: No active session + stale device row -> disconnected.
     * Stale device rows in the database without any active pairing session must not cause a connected state.
     */
    @Test
    fun syncRemoteData_withNoActiveSessionAndStaleDeviceRow_remainsDisconnected() = runBlocking {
        val staleDevice = Device(
            id = "stale-orphaned-device",
            familyId = "family_test",
            userId = null,
            deviceName = "Orphaned Phone",
            platform = "ANDROID",
            deviceType = "CHILD_PHONE",
            lastSeenAt = System.currentTimeMillis()
        )
        fakeDeviceRepository.setDevicesDirectly(listOf(staleDevice))
        fakePairingRepository.setSessionDirectly(null)

        viewModel.syncRemoteData()

        val state = viewModel.uiState.value
        assertFalse("Device presence without pairing session must report disconnected", state.childStatus.isConnected)
        assertEquals("Not connected", state.childStatus.deviceName)
        assertNull("Pairing session must be null", state.pairingSession)
        assertEquals(DeviceConnectivityStatus.OFFLINE, state.connectivityStatus)
    }

    /**
     * Listener path test: deviceRepository.getDevicesForFamily("") must not flip isConnected to true
     * when the pairing session is REVOKED.
     */
    @Test
    fun deviceCollector_doesNotSetConnected_whenSessionIsRevoked() = runBlocking {
        val revokedSession = PairingSession(
            pairingId = "session-revoked-005",
            parentDeviceId = "parent-dev-cloud",
            childDeviceId = "device-revoked-005",
            pairingCode = "111222",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 600000L,
            status = PairingStatus.REVOKED,
            familyId = "family_test"
        )
        fakePairingRepository.setSessionDirectly(revokedSession)
        viewModel.syncRemoteData()

        assertFalse(viewModel.uiState.value.childStatus.isConnected)

        // Simulate background devices emission
        val device = Device(
            id = "device-revoked-005",
            familyId = "family_test",
            userId = null,
            deviceName = "Device 005",
            platform = "ANDROID",
            deviceType = "CHILD_PHONE",
            lastSeenAt = System.currentTimeMillis()
        )
        fakeDeviceRepository.setDevicesDirectly(listOf(device))

        // Re-verify that device presence still does NOT flip isConnected
        assertFalse("Background device emission must not bypass REVOKED pairing status", viewModel.uiState.value.childStatus.isConnected)
        assertEquals("Not connected", viewModel.uiState.value.childStatus.deviceName)
    }
}
