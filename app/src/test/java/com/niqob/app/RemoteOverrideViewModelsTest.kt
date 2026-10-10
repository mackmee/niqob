package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.enforcement.InMemoryParentOverrideSessionStore
import com.niqob.app.data.repository.LocalScreenTimeEnforcementRepository
import com.niqob.app.domain.enforcement.ParentOverrideSessionStore
import com.niqob.app.domain.model.ParentOverrideSession
import com.niqob.app.domain.model.RemoteOverrideRequest
import com.niqob.app.domain.model.RemoteOverrideRequestStatus
import com.niqob.app.domain.usecase.ConsumeRemoteOverrideUseCase
import com.niqob.app.fakes.FakePairingRepository
import com.niqob.app.fakes.FakeRemoteOverrideRepository
import com.niqob.app.feature.child.override.RemoteOverrideRequestViewModel
import com.niqob.app.feature.parent.override.ParentRemoteOverrideViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

class RemoteOverrideViewModelsTest {

    private lateinit var fakeRepository: FakeRemoteOverrideRepository
    private lateinit var fakePairingRepository: FakePairingRepository
    private lateinit var sessionStore: InMemoryParentOverrideSessionStore
    private lateinit var enforcementRepository: LocalScreenTimeEnforcementRepository
    private val fixedInstant = Instant.parse("2026-10-05T10:00:00Z")

    @Before
    fun setUp() {
        LocalScreenTimeEnforcementRepository.resetForTesting()
        fakeRepository = FakeRemoteOverrideRepository(clock = { fixedInstant })
        fakePairingRepository = FakePairingRepository()
        sessionStore = InMemoryParentOverrideSessionStore(timeProvider = { fixedInstant.toEpochMilli() })
        enforcementRepository = LocalScreenTimeEnforcementRepository.getInstance(
            sessionStore = sessionStore,
            timeProvider = { fixedInstant.toEpochMilli() }
        )
    }

    @After
    fun tearDown() {
        LocalScreenTimeEnforcementRepository.resetForTesting()
    }

    private suspend fun seedApprovedRequest(
        viewModel: RemoteOverrideRequestViewModel,
        durationMinutes: Int = 30
    ): RemoteOverrideRequest {
        val submitResult = viewModel.submitRequestInternal()
        assertTrue(submitResult is Result.Success)
        val requestId = viewModel.uiState.value.activeRequest!!.id
        fakeRepository.approveRequest(requestId, durationMinutes)
        val refreshResult = viewModel.refreshStatusInternal()
        assertTrue(refreshResult is Result.Success)
        val request = viewModel.uiState.value.activeRequest!!
        assertEquals(RemoteOverrideRequestStatus.APPROVED, request.status)
        return request
    }

    // ==========================================================================
    // CHILD VIEWMODEL TESTS
    // ==========================================================================

    @Test
    fun childViewModel_initialState_defaultDurationIs15() {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        val state = viewModel.uiState.value
        assertEquals("child-dev-1", state.deviceId)
        assertEquals(15, state.selectedDurationMinutes)
        assertEquals("", state.reason)
        assertFalse(state.isSubmitting)
        assertFalse(state.isRefreshing)
        assertNull(state.activeRequest)
        assertNull(state.errorMessage)
        assertFalse(state.isSuccessSent)
        assertTrue(state.canSubmit)
    }

    @Test
    fun childViewModel_validDurationSelection_updatesState() {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.onDurationSelected(30)
        assertEquals(30, viewModel.uiState.value.selectedDurationMinutes)
        assertNull(viewModel.uiState.value.errorMessage)

        viewModel.onDurationSelected(60)
        assertEquals(60, viewModel.uiState.value.selectedDurationMinutes)
        assertNull(viewModel.uiState.value.errorMessage)

        viewModel.onDurationSelected(15)
        assertEquals(15, viewModel.uiState.value.selectedDurationMinutes)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun childViewModel_invalidDurationSelection_rejectedWithErrorMessage() {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.onDurationSelected(45)
        assertEquals("Requested duration must be 15, 30, or 60 minutes.", viewModel.uiState.value.errorMessage)
        assertEquals(15, viewModel.uiState.value.selectedDurationMinutes)

        viewModel.onDurationSelected(120)
        assertEquals("Requested duration must be 15, 30, or 60 minutes.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun childViewModel_successfulRequest_transitionsToPending() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.onDurationSelected(30)
        viewModel.onReasonChanged("Working on history homework project")

        val result = viewModel.submitRequestInternal()
        assertTrue(result is Result.Success)

        val state = viewModel.uiState.value
        assertFalse(state.isSubmitting)
        assertTrue(state.isSuccessSent)
        assertNull(state.errorMessage)

        val active = state.activeRequest
        assertNotNull(active)
        assertEquals("child-dev-1", active?.deviceId)
        assertEquals(30, active?.requestedDurationMinutes)
        assertEquals(RemoteOverrideRequestStatus.PENDING, active?.status)
        assertEquals("Working on history homework project", active?.reason)
        assertNotNull(active?.claimExpiresAt)
        assertFalse(state.canSubmit) // Cannot submit again while pending
    }

    @Test
    fun childViewModel_requestFailure_setsErrorState() = runBlocking {
        fakeRepository.shouldFail = true
        fakeRepository.failureException = IllegalStateException("Supabase server unreachable")

        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        val result = viewModel.submitRequestInternal()
        assertTrue(result is Result.Error)

        val state = viewModel.uiState.value
        assertFalse(state.isSubmitting)
        assertFalse(state.isSuccessSent)
        assertNull(state.activeRequest)
        assertEquals("Supabase server unreachable", state.errorMessage)
    }

    @Test
    fun childViewModel_duplicateSubmissionPrevention() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        // First submission succeeds
        val result1 = viewModel.submitRequestInternal()
        assertTrue(result1 is Result.Success)

        // Second submission while pending is prevented
        val result2 = viewModel.submitRequestInternal()
        assertTrue(result2 is Result.Error)
        assertEquals("A pending override request already exists for this device.", (result2 as Result.Error).exception.message)
    }

    @Test
    fun childViewModel_statusRefresh_updatesActiveRequest() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.submitRequestInternal()
        val requestId = viewModel.uiState.value.activeRequest!!.id

        // Parent approves on the backend
        fakeRepository.approveRequest(requestId, 15)

        // Child manually refreshes
        val refreshResult = viewModel.refreshStatusInternal()
        assertTrue(refreshResult is Result.Success)

        val active = viewModel.uiState.value.activeRequest
        assertNotNull(active)
        assertEquals(RemoteOverrideRequestStatus.APPROVED, active?.status)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun childViewModel_approvedStatus_doesNotCreateLocalParentOverrideSession() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.submitRequestInternal()
        val requestId = viewModel.uiState.value.activeRequest!!.id
        fakeRepository.approveRequest(requestId, 30)

        viewModel.refreshStatusInternal()

        // Critical safety verification: local override session must NOT be created
        val localEnforcementRepo = LocalScreenTimeEnforcementRepository.getInstance()
        val localState = localEnforcementRepo.getEnforcementState().value
        assertNull(
            "Local ParentOverrideSession must NOT be created upon remote approval in Step 3",
            localState?.overrideSession
        )
        assertEquals(RemoteOverrideRequestStatus.APPROVED, viewModel.uiState.value.activeRequest?.status)
    }

    @Test
    fun childViewModel_rejectedState_handledCorrectly() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.submitRequestInternal()
        val requestId = viewModel.uiState.value.activeRequest!!.id
        fakeRepository.rejectRequest(requestId, "Bedtime soon, sleep well!")

        viewModel.refreshStatusInternal()

        val active = viewModel.uiState.value.activeRequest
        assertNotNull(active)
        assertEquals(RemoteOverrideRequestStatus.REJECTED, active?.status)
        assertEquals("Bedtime soon, sleep well!", active?.rejectionReason)
    }

    @Test
    fun childViewModel_expiredState_handledCorrectly() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.submitRequestInternal()
        val requestId = viewModel.uiState.value.activeRequest!!.id

        // Seed as expired on backend
        fakeRepository.seedRequest(
            viewModel.uiState.value.activeRequest!!.copy(
                status = RemoteOverrideRequestStatus.EXPIRED,
                isExpired = true
            )
        )

        viewModel.refreshStatusInternal()

        val active = viewModel.uiState.value.activeRequest
        assertNotNull(active)
        assertEquals(RemoteOverrideRequestStatus.EXPIRED, active?.status)
        assertTrue(active!!.isExpired)
    }

    // ==========================================================================
    // STEP 4: CONSUME REMOTE OVERRIDE & ACTIVATE LOCAL SESSION TESTS
    // ==========================================================================

    @Test
    fun approvedRequest_continueToActivate_callsConsume() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        val approved = seedApprovedRequest(viewModel, 30)

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Success)
        assertEquals(1, fakeRepository.consumeCallCount)
        assertEquals("child-dev-1", fakeRepository.lastConsumedDeviceId)
        assertEquals(approved.id, fakeRepository.lastConsumedRequestId)
    }

    @Test
    fun nonApprovedRequest_cannotActivate() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        // Case A: null activeRequest
        val resNull = viewModel.continueToActivateInternal()
        assertTrue(resNull is Result.Error)
        assertEquals(0, fakeRepository.consumeCallCount)

        // Case B: PENDING
        viewModel.submitRequestInternal()
        assertEquals(RemoteOverrideRequestStatus.PENDING, viewModel.uiState.value.activeRequest?.status)
        assertFalse(viewModel.uiState.value.canActivate)
        val resPending = viewModel.continueToActivateInternal()
        assertTrue(resPending is Result.Error)
        assertEquals(0, fakeRepository.consumeCallCount)

        // Case C: REJECTED
        val reqId = viewModel.uiState.value.activeRequest!!.id
        fakeRepository.rejectRequest(reqId, "Not now")
        viewModel.refreshStatusInternal()
        assertEquals(RemoteOverrideRequestStatus.REJECTED, viewModel.uiState.value.activeRequest?.status)
        assertFalse(viewModel.uiState.value.canActivate)
        val resRejected = viewModel.continueToActivateInternal()
        assertTrue(resRejected is Result.Error)
        assertEquals(0, fakeRepository.consumeCallCount)

        // Case D: EXPIRED
        fakeRepository.seedRequest(
            viewModel.uiState.value.activeRequest!!.copy(
                status = RemoteOverrideRequestStatus.EXPIRED,
                isExpired = true
            )
        )
        viewModel.refreshStatusInternal()
        assertEquals(RemoteOverrideRequestStatus.EXPIRED, viewModel.uiState.value.activeRequest?.status)
        assertFalse(viewModel.uiState.value.canActivate)
        val resExpired = viewModel.continueToActivateInternal()
        assertTrue(resExpired is Result.Error)
        assertEquals(0, fakeRepository.consumeCallCount)

        // Case E: REVOKED
        fakeRepository.seedRequest(
            viewModel.uiState.value.activeRequest!!.copy(
                status = RemoteOverrideRequestStatus.REVOKED
            )
        )
        viewModel.refreshStatusInternal()
        assertEquals(RemoteOverrideRequestStatus.REVOKED, viewModel.uiState.value.activeRequest?.status)
        assertFalse(viewModel.uiState.value.canActivate)
        val resRevoked = viewModel.continueToActivateInternal()
        assertTrue(resRevoked is Result.Error)
        assertEquals(0, fakeRepository.consumeCallCount)
    }

    @Test
    fun consumeSuccess_createsLocalParentOverrideSession() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 30)

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Success)

        val activeSession = enforcementRepository.getActiveOverrideSession()
        assertNotNull(activeSession)
        assertTrue(activeSession!!.isActive)
        assertTrue(activeSession.isCurrentlyActive(fixedInstant.toEpochMilli()))

        val enforcementState = enforcementRepository.getEnforcementState().value
        assertNotNull(enforcementState)
        assertTrue(enforcementState!!.isParentOverrideActive)
        assertEquals(activeSession, enforcementState.overrideSession)

        // Persisted in sessionStore
        val loaded = sessionStore.load(fixedInstant.toEpochMilli())
        assertNotNull(loaded)
        assertEquals(activeSession, loaded)
    }

    @Test
    fun localSessionUsesServerConsumedAt() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 30)

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Success)

        val session = (result as Result.Success).data
        val storedInRepo = fakeRepository.getStoredRequest(viewModel.uiState.value.activeRequest!!.id)
        val serverConsumedAt = storedInRepo!!.consumedAt!!

        assertEquals(serverConsumedAt.toEpochMilli(), session.activatedAtMillis)
        assertEquals(serverConsumedAt.toEpochMilli(), enforcementRepository.getActiveOverrideSession()?.activatedAtMillis)
    }

    @Test
    fun localSessionUsesServerConsumedExpiresAt() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 30)

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Success)

        val session = (result as Result.Success).data
        val storedInRepo = fakeRepository.getStoredRequest(viewModel.uiState.value.activeRequest!!.id)
        val serverExpiresAt = storedInRepo!!.consumedExpiresAt!!

        assertEquals(serverExpiresAt.toEpochMilli(), session.expiresAtMillis)
        assertEquals(serverExpiresAt.toEpochMilli(), enforcementRepository.getActiveOverrideSession()?.expiresAtMillis)
    }

    @Test
    fun localSessionUsesServerApprovedDuration() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        // Child requested 15 minutes
        viewModel.onDurationSelected(15)
        viewModel.submitRequestInternal()
        val reqId = viewModel.uiState.value.activeRequest!!.id

        // Parent approved 60 minutes
        fakeRepository.approveRequest(reqId, 60)
        viewModel.refreshStatusInternal()

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Success)

        val session = (result as Result.Success).data
        assertEquals(60, session.durationMinutes)
        assertEquals(60, enforcementRepository.getActiveOverrideSession()?.durationMinutes)
    }

    @Test
    fun activationDoesNotCalculateExpirationLocally() = runBlocking {
        val serverTime = Instant.parse("2026-10-05T12:00:00Z")
        val customRepo = FakeRemoteOverrideRepository(clock = { serverTime })
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = customRepo,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.submitRequestInternal()
        val reqId = viewModel.uiState.value.activeRequest!!.id
        customRepo.approveRequest(reqId, 15)
        viewModel.refreshStatusInternal()

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Success)

        val session = (result as Result.Success).data
        val expectedServerExpires = serverTime.plusSeconds(15 * 60).toEpochMilli()

        // Expiration MUST match serverTime + duration, not any local clock
        assertEquals(expectedServerExpires, session.expiresAtMillis)
        assertEquals(serverTime.toEpochMilli(), session.activatedAtMillis)
    }

    @Test
    fun consumeFailure_doesNotCreateLocalSession() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 30)

        fakeRepository.shouldFail = true
        fakeRepository.failureException = IllegalStateException("Database lock conflict")

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Error)

        assertNull(enforcementRepository.getActiveOverrideSession())
        assertNull(sessionStore.load(fixedInstant.toEpochMilli()))
        assertFalse(viewModel.uiState.value.isActivationSuccess)
        assertNull(viewModel.uiState.value.activatedSession)
        assertEquals("Database lock conflict", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun networkFailure_doesNotCreateLocalSession() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 30)

        fakeRepository.shouldFail = true
        fakeRepository.failureException = IOException("Unable to resolve host: supabase.co")

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Error)

        assertNull(enforcementRepository.getActiveOverrideSession())
        assertNull(sessionStore.load(fixedInstant.toEpochMilli()))
        assertFalse(viewModel.uiState.value.isActivationSuccess)
        assertEquals(
            "Connection unavailable. Please check your internet connection and try again.",
            viewModel.uiState.value.errorMessage
        )
    }

    @Test
    fun duplicateActivationTap_prevented() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 30)

        val result1 = viewModel.continueToActivateInternal()
        assertTrue(result1 is Result.Success)
        assertEquals(1, fakeRepository.consumeCallCount)

        // Attempting to activate again when already consumed is blocked
        assertFalse(viewModel.uiState.value.canActivate)
        val result2 = viewModel.continueToActivateInternal()
        assertTrue(result2 is Result.Error)
        // RPC is not invoked a second time
        assertEquals(1, fakeRepository.consumeCallCount)
    }

    @Test
    fun consumedState_handledSafely() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 30)

        viewModel.continueToActivateInternal()
        val activeSession = enforcementRepository.getActiveOverrideSession()
        assertNotNull(activeSession)

        // Trying to activate an already consumed request preserves the existing local session
        val retryResult = viewModel.continueToActivateInternal()
        assertNotNull(enforcementRepository.getActiveOverrideSession())
        assertEquals(activeSession, enforcementRepository.getActiveOverrideSession())
    }

    @Test
    fun expiredState_updatesUi() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 30)

        fakeRepository.shouldFail = true
        fakeRepository.failureException = IllegalStateException("claim_window_expired")

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Error)

        assertEquals("Approval claim window has expired.", viewModel.uiState.value.errorMessage)
        assertEquals(RemoteOverrideRequestStatus.EXPIRED, viewModel.uiState.value.activeRequest?.status)
        assertFalse(viewModel.uiState.value.canActivate)
        assertNull(enforcementRepository.getActiveOverrideSession())
    }

    @Test
    fun revokedState_updatesUi() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 30)

        fakeRepository.shouldFail = true
        fakeRepository.failureException = IllegalStateException("override_was_revoked")

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Error)

        assertEquals("This override has been revoked.", viewModel.uiState.value.errorMessage)
        assertEquals(RemoteOverrideRequestStatus.REVOKED, viewModel.uiState.value.activeRequest?.status)
        assertFalse(viewModel.uiState.value.canActivate)
        assertNull(enforcementRepository.getActiveOverrideSession())
    }

    @Test
    fun persistenceFailure_doesNotReportSuccess() = runBlocking {
        val failingStore = object : ParentOverrideSessionStore {
            override fun save(session: ParentOverrideSession) {
                throw RuntimeException("SharedPreferences disk failure")
            }
            override fun load(currentTimeMillis: Long): ParentOverrideSession? = null
            override fun clear() {}
        }

        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = failingStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 30)

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Error)

        assertFalse(viewModel.uiState.value.isActivationSuccess)
        assertNull(viewModel.uiState.value.activatedSession)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("Failed to activate local screen time session"))
    }

    @Test
    fun successfulActivation_updatesUiState() = runBlocking {
        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )
        seedApprovedRequest(viewModel, 15)

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Success)

        val state = viewModel.uiState.value
        assertFalse(state.isActivating)
        assertTrue(state.isActivationSuccess)
        assertNull(state.errorMessage)
        assertNotNull(state.activatedSession)
        assertEquals(15, state.activatedSession?.durationMinutes)
        assertEquals(RemoteOverrideRequestStatus.CONSUMED, state.activeRequest?.status)
        assertTrue(state.activeRequest!!.isActive)
        assertFalse(state.canActivate)
        assertTrue(state.canSubmit)
    }

    @Test
    fun successfulActivation_preservesServerExpiration() = runBlocking {
        val serverNow = Instant.parse("2026-10-05T15:30:00Z")
        val customRepo = FakeRemoteOverrideRepository(clock = { serverNow })

        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = customRepo,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        viewModel.submitRequestInternal()
        val reqId = viewModel.uiState.value.activeRequest!!.id
        customRepo.approveRequest(reqId, 30)
        viewModel.refreshStatusInternal()

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Success)

        val expectedExpiresMillis = serverNow.plusSeconds(30 * 60).toEpochMilli()
        val state = viewModel.uiState.value
        assertEquals(expectedExpiresMillis, state.activatedSession?.expiresAtMillis)
        assertEquals(expectedExpiresMillis, state.activeRequest?.consumedExpiresAt?.toEpochMilli())
    }

    @Test
    fun viewModelDoesNotPassDurationOrExpirationIntoConsumeUseCase() = runBlocking {
        var interceptedDeviceId: String? = null
        var interceptedRequestId: String? = null

        val trackingUseCase = object : ConsumeRemoteOverrideUseCase(fakeRepository) {
            override suspend fun invoke(deviceId: String, requestId: String): Result<com.niqob.app.domain.model.ConsumedOverrideResult> {
                interceptedDeviceId = deviceId
                interceptedRequestId = requestId
                return super.invoke(deviceId, requestId)
            }
        }

        val viewModel = RemoteOverrideRequestViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            enforcementRepository = enforcementRepository,
            sessionStore = sessionStore,
            consumeRemoteOverrideUseCase = trackingUseCase,
            initialDeviceId = "child-dev-1",
            dispatcher = Dispatchers.Unconfined
        )

        // Child selected 15 minutes in UI
        viewModel.onDurationSelected(15)
        seedApprovedRequest(viewModel, 60) // Server approves 60 minutes

        val result = viewModel.continueToActivateInternal()
        assertTrue(result is Result.Success)

        // Verify that trackingUseCase only received deviceId and requestId
        assertEquals("child-dev-1", interceptedDeviceId)
        assertEquals(viewModel.uiState.value.activeRequest!!.id, interceptedRequestId)
        // And the activated session received the server-decided 60 minutes
        assertEquals(60, viewModel.uiState.value.activatedSession?.durationMinutes)
    }

    // ==========================================================================
    // PARENT VIEWMODEL TESTS
    // ==========================================================================

    @Test
    fun parentViewModel_pendingListLoadsSuccessfully() = runBlocking {
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-1",
                deviceId = "dev-1",
                requestedDurationMinutes = 15,
                status = RemoteOverrideRequestStatus.PENDING,
                reason = "Reading app",
                createdAt = fixedInstant
            )
        )
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-2",
                deviceId = "dev-2",
                requestedDurationMinutes = 30,
                status = RemoteOverrideRequestStatus.PENDING,
                reason = "Math homework",
                createdAt = fixedInstant
            )
        )

        val viewModel = ParentRemoteOverrideViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = null,
            dispatcher = Dispatchers.Unconfined
        )

        val result = viewModel.loadPendingRequestsInternal(null)
        assertTrue(result is Result.Success)

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.pendingRequests.size)
        assertNull(state.errorMessage)
    }

    @Test
    fun parentViewModel_approveSuccess_removesFromPending() = runBlocking {
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-approve-1",
                deviceId = "dev-1",
                requestedDurationMinutes = 15,
                status = RemoteOverrideRequestStatus.PENDING,
                createdAt = fixedInstant
            )
        )

        val viewModel = ParentRemoteOverrideViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = null,
            dispatcher = Dispatchers.Unconfined
        )
        viewModel.loadPendingRequestsInternal(null)
        assertEquals(1, viewModel.uiState.value.pendingRequests.size)

        val approveResult = viewModel.approveRequestInternal("req-approve-1", 30)
        assertTrue(approveResult is Result.Success)

        val state = viewModel.uiState.value
        assertEquals(0, state.pendingRequests.size)
        assertEquals("Screen time request approved.", state.successMessage)
        assertNull(state.errorMessage)
        assertNull(state.actionInProgressRequestId)

        val stored = fakeRepository.getStoredRequest("req-approve-1")
        assertEquals(RemoteOverrideRequestStatus.APPROVED, stored?.status)
        assertEquals(30, stored?.approvedDurationMinutes)
    }

    @Test
    fun parentViewModel_approveFailure_setsErrorState() = runBlocking {
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-fail-1",
                deviceId = "dev-1",
                requestedDurationMinutes = 15,
                status = RemoteOverrideRequestStatus.PENDING,
                createdAt = fixedInstant
            )
        )

        val viewModel = ParentRemoteOverrideViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = null,
            dispatcher = Dispatchers.Unconfined
        )
        viewModel.loadPendingRequestsInternal(null)

        fakeRepository.shouldFail = true
        fakeRepository.failureException = IllegalStateException("Not authorized to approve for this family")

        val result = viewModel.approveRequestInternal("req-fail-1", 15)
        assertTrue(result is Result.Error)

        val state = viewModel.uiState.value
        assertEquals(1, state.pendingRequests.size) // Not removed on failure
        assertEquals("Not authorized to approve for this family", state.errorMessage)
        assertNull(state.actionInProgressRequestId)
    }

    @Test
    fun parentViewModel_rejectSuccess_removesFromPending() = runBlocking {
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-reject-1",
                deviceId = "dev-1",
                requestedDurationMinutes = 15,
                status = RemoteOverrideRequestStatus.PENDING,
                createdAt = fixedInstant
            )
        )

        val viewModel = ParentRemoteOverrideViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = null,
            dispatcher = Dispatchers.Unconfined
        )
        viewModel.loadPendingRequestsInternal(null)

        val rejectResult = viewModel.rejectRequestInternal("req-reject-1", "Bedtime")
        assertTrue(rejectResult is Result.Success)

        val state = viewModel.uiState.value
        assertEquals(0, state.pendingRequests.size)
        assertEquals("Screen time request rejected.", state.successMessage)
        assertNull(state.errorMessage)

        val stored = fakeRepository.getStoredRequest("req-reject-1")
        assertEquals(RemoteOverrideRequestStatus.REJECTED, stored?.status)
        assertEquals("Bedtime", stored?.rejectionReason)
    }

    @Test
    fun parentViewModel_rejectFailure_setsErrorState() = runBlocking {
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-reject-fail",
                deviceId = "dev-1",
                requestedDurationMinutes = 15,
                status = RemoteOverrideRequestStatus.PENDING,
                createdAt = fixedInstant
            )
        )

        val viewModel = ParentRemoteOverrideViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = null,
            dispatcher = Dispatchers.Unconfined
        )
        viewModel.loadPendingRequestsInternal(null)

        fakeRepository.shouldFail = true
        fakeRepository.failureException = IllegalStateException("Network unreachable")

        val result = viewModel.rejectRequestInternal("req-reject-fail", "No")
        assertTrue(result is Result.Error)

        val state = viewModel.uiState.value
        assertEquals(1, state.pendingRequests.size)
        assertEquals("Network unreachable", state.errorMessage)
    }

    @Test
    fun parentViewModel_duplicateActionPrevention() = runBlocking {
        val viewModel = ParentRemoteOverrideViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = null,
            dispatcher = Dispatchers.Unconfined
        )

        // Simulate action already in progress
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-1",
                deviceId = "dev-1",
                requestedDurationMinutes = 15,
                status = RemoteOverrideRequestStatus.PENDING,
                createdAt = fixedInstant
            )
        )

        // When actionInProgressRequestId is set, approval and rejection are rejected
        val approvedDurationResult = viewModel.approveRequestInternal("req-1", 45) // invalid duration
        assertTrue(approvedDurationResult is Result.Error)
        assertEquals("Approved duration must be 15, 30, or 60 minutes.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun parentViewModel_requestedDurationIsDefaultApprovalDuration() = runBlocking {
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-default-dur",
                deviceId = "dev-1",
                requestedDurationMinutes = 60, // Child requested 60
                status = RemoteOverrideRequestStatus.PENDING,
                createdAt = fixedInstant
            )
        )

        val viewModel = ParentRemoteOverrideViewModel(
            remoteOverrideRepository = fakeRepository,
            pairingRepository = fakePairingRepository,
            initialDeviceId = null,
            dispatcher = Dispatchers.Unconfined
        )
        viewModel.loadPendingRequestsInternal(null)

        // Parent passes null for duration: should default to requested duration (60)
        val result = viewModel.approveRequestInternal("req-default-dur", null)
        assertTrue(result is Result.Success)

        val stored = fakeRepository.getStoredRequest("req-default-dur")
        assertEquals(60, stored?.approvedDurationMinutes)
    }
}
