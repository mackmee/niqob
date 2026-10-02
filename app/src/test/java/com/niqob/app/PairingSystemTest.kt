package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.LocalDataSourcePlaceholder
import com.niqob.app.data.local.LocalPairingDataSource
import com.niqob.app.data.repository.PairingRepositoryImpl
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.usecase.ConnectChildDeviceUseCase
import com.niqob.app.domain.usecase.CreatePairingCodeUseCase
import com.niqob.app.domain.usecase.GetPairingSessionUseCase
import com.niqob.app.domain.usecase.GetUserRoleUseCase
import com.niqob.app.domain.usecase.RevokePairingUseCase
import com.niqob.app.domain.usecase.SetUserRoleUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingSystemTest {

    private lateinit var localPairingDataSource: LocalPairingDataSource
    private lateinit var localGeneralDataSource: LocalDataSourcePlaceholder
    private lateinit var pairingRepository: PairingRepositoryImpl

    private lateinit var createPairingCodeUseCase: CreatePairingCodeUseCase
    private lateinit var connectChildDeviceUseCase: ConnectChildDeviceUseCase
    private lateinit var getPairingSessionUseCase: GetPairingSessionUseCase
    private lateinit var revokePairingUseCase: RevokePairingUseCase
    private lateinit var setUserRoleUseCase: SetUserRoleUseCase
    private lateinit var getUserRoleUseCase: GetUserRoleUseCase

    @Before
    fun setup() {
        LocalPairingDataSource.resetInstanceForTests()
        LocalDataSourcePlaceholder.resetInstanceForTests()

        localPairingDataSource = LocalPairingDataSource()
        localGeneralDataSource = LocalDataSourcePlaceholder()
        pairingRepository = PairingRepositoryImpl(localPairingDataSource, localGeneralDataSource)

        createPairingCodeUseCase = CreatePairingCodeUseCase(pairingRepository)
        connectChildDeviceUseCase = ConnectChildDeviceUseCase(pairingRepository)
        getPairingSessionUseCase = GetPairingSessionUseCase(pairingRepository)
        revokePairingUseCase = RevokePairingUseCase(pairingRepository)
        setUserRoleUseCase = SetUserRoleUseCase(pairingRepository)
        getUserRoleUseCase = GetUserRoleUseCase(pairingRepository)
    }

    @After
    fun tearDown() {
        LocalPairingDataSource.resetInstanceForTests()
        LocalDataSourcePlaceholder.resetInstanceForTests()
    }

    // 1. Pairing code generation
    @Test
    fun createPairingCode_generatesSixDigitCodeAndPendingSession() = runBlocking {
        val result = createPairingCodeUseCase()

        assertTrue(result is Result.Success)
        val session = (result as Result.Success).data

        assertEquals(6, session.pairingCode.length)
        assertTrue(session.pairingCode.all { it.isDigit() })
        assertEquals(PairingStatus.PENDING, session.status)
        assertTrue(session.parentDeviceId.startsWith("parent_dev_"))
        assertEquals(null, session.childDeviceId)
        assertEquals(session.createdAt + 10 * 60 * 1000L, session.expiresAt)
    }

    // 2. Pairing code expiration
    @Test
    fun pairingCode_expiresAfterTenMinutes() = runBlocking {
        val currentTime = 1_000_000L
        val session = localPairingDataSource.createPairingCode(currentTimeMillis = currentTime)

        // At 5 minutes: valid
        assertFalse(session.isExpired(currentTime + 5 * 60 * 1000L))

        // At 10 minutes + 1 second: expired
        assertTrue(session.isExpired(currentTime + 10 * 60 * 1000L + 1000L))

        // Child connection attempt past expiration returns error
        val connectResult = localPairingDataSource.connectChild(
            code = session.pairingCode,
            currentTimeMillis = currentTime + 11 * 60 * 1000L
        )

        assertTrue(connectResult is Result.Error)
        val error = connectResult as Result.Error
        assertEquals("This pairing code has expired.", error.message)

        // Session status is updated to EXPIRED
        val currentSession = getPairingSessionUseCase().first()
        assertNotNull(currentSession)
        assertEquals(PairingStatus.EXPIRED, currentSession?.status)
    }

    // 3. Invalid pairing code
    @Test
    fun connectChild_withInvalidCode_returnsError() = runBlocking {
        val createResult = createPairingCodeUseCase()
        assertTrue(createResult is Result.Success)
        val validCode = (createResult as Result.Success).data.pairingCode

        val wrongCode = if (validCode == "123456") "654321" else "123456"
        val connectResult = connectChildDeviceUseCase(wrongCode)

        assertTrue(connectResult is Result.Error)
        assertEquals("Invalid pairing code.", (connectResult as Result.Error).message)

        // Status remains PENDING
        val session = getPairingSessionUseCase().first()
        assertEquals(PairingStatus.PENDING, session?.status)
    }

    // 4. Successful child connection
    @Test
    fun connectChild_withValidCode_connectsSuccessfully() = runBlocking {
        val createResult = createPairingCodeUseCase()
        assertTrue(createResult is Result.Success)
        val code = (createResult as Result.Success).data.pairingCode

        val connectResult = connectChildDeviceUseCase(code)
        assertTrue(connectResult is Result.Success)

        val connectedSession = (connectResult as Result.Success).data
        assertEquals(PairingStatus.CONNECTED, connectedSession.status)
        assertNotNull(connectedSession.childDeviceId)
        assertTrue(connectedSession.childDeviceId!!.startsWith("child_dev_"))

        // Verify repository general status was updated
        val generalStatus = localGeneralDataSource.childDeviceStatus.first()
        assertTrue(generalStatus.isConnected)
        assertEquals(connectedSession.childDeviceId, generalStatus.deviceName)
    }

    // 5. Pairing status transition
    @Test
    fun pairingStatus_transitionsFromPendingToConnected() = runBlocking {
        // Initial state before creation
        assertEquals(null, getPairingSessionUseCase().first())

        // 1. PENDING
        val session = (createPairingCodeUseCase() as Result.Success).data
        assertEquals(PairingStatus.PENDING, session.status)
        assertEquals(PairingStatus.PENDING, getPairingSessionUseCase().first()?.status)

        // 2. CONNECTED
        connectChildDeviceUseCase(session.pairingCode)
        assertEquals(PairingStatus.CONNECTED, getPairingSessionUseCase().first()?.status)
    }

    // 6. Pairing revocation
    @Test
    fun revokePairing_updatesStatusToRevokedAndDisconnectsDevice() = runBlocking {
        val session = (createPairingCodeUseCase() as Result.Success).data
        connectChildDeviceUseCase(session.pairingCode)

        // Verify connected first
        assertEquals(PairingStatus.CONNECTED, getPairingSessionUseCase().first()?.status)
        assertTrue(localGeneralDataSource.childDeviceStatus.first().isConnected)

        // Revoke
        val revokeResult = revokePairingUseCase()
        assertTrue(revokeResult is Result.Success)

        // Verify revoked status
        val currentSession = getPairingSessionUseCase().first()
        assertEquals(PairingStatus.REVOKED, currentSession?.status)
        assertFalse(localGeneralDataSource.childDeviceStatus.first().isConnected)
        assertEquals("Not connected", localGeneralDataSource.childDeviceStatus.first().deviceName)

        // Trying to connect a revoked session fails
        val retryConnect = connectChildDeviceUseCase(session.pairingCode)
        assertTrue(retryConnect is Result.Error)
        assertEquals("This pairing code has been revoked.", (retryConnect as Result.Error).message)
    }

    // Role persistence test
    @Test
    fun roleSelection_persistsRoleCorrectly() = runBlocking {
        assertEquals(UserMode.UNSELECTED, getUserRoleUseCase().first())

        setUserRoleUseCase(UserMode.PARENT)
        assertEquals(UserMode.PARENT, getUserRoleUseCase().first())

        setUserRoleUseCase(UserMode.CHILD)
        assertEquals(UserMode.CHILD, getUserRoleUseCase().first())
    }
}
