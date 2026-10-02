package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.domain.model.ScreenTimeLimit
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParentScreenTimeViewModelTest {

    private lateinit var fakeScreenTimeRepository: FakeScreenTimeRepository
    private lateinit var fakeAuthRepository: FakeAuthRepository
    private lateinit var fakeDeviceRepository: FakeDeviceRepository
    private lateinit var fakePairingRepository: FakePairingRepository
    private lateinit var viewModel: ParentDashboardViewModel

    @Before
    fun setup() {
        fakeScreenTimeRepository = FakeScreenTimeRepository(defaultFamilyId = "family_abc")
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

    @Test
    fun initialState_hasSensibleDefaults() {
        val state = viewModel.uiState.value
        assertEquals(120, state.screenTimeLimitMinutes)
        assertEquals(120, state.selectedScreenTimeMinutes)
        assertTrue(state.isScreenTimeEnabled)
        assertFalse(state.isScreenTimeLoading)
        assertFalse(state.isScreenTimeSaving)
        assertNull(state.screenTimeError)
        assertFalse(state.screenTimeSaveSuccess)
    }

    @Test
    fun updateScreenTimeMinutes_withValidPresets_updatesStateAndClearsErrors() {
        viewModel.updateScreenTimeMinutes(30)
        assertEquals(30, viewModel.uiState.value.selectedScreenTimeMinutes)
        assertNull(viewModel.uiState.value.screenTimeError)

        viewModel.updateScreenTimeMinutes(60)
        assertEquals(60, viewModel.uiState.value.selectedScreenTimeMinutes)
        assertNull(viewModel.uiState.value.screenTimeError)

        viewModel.updateScreenTimeMinutes(180)
        assertEquals(180, viewModel.uiState.value.selectedScreenTimeMinutes)
        assertNull(viewModel.uiState.value.screenTimeError)
    }

    @Test
    fun updateScreenTimeMinutes_withBoundaryValues_isAccepted() {
        viewModel.updateScreenTimeMinutes(0)
        assertEquals(0, viewModel.uiState.value.selectedScreenTimeMinutes)
        assertNull(viewModel.uiState.value.screenTimeError)

        viewModel.updateScreenTimeMinutes(1440)
        assertEquals(1440, viewModel.uiState.value.selectedScreenTimeMinutes)
        assertNull(viewModel.uiState.value.screenTimeError)
    }

    @Test
    fun updateScreenTimeMinutes_withInvalidValues_setsValidationError() {
        viewModel.updateScreenTimeMinutes(-1)
        assertNotNull(viewModel.uiState.value.screenTimeError)
        assertEquals(-1, viewModel.uiState.value.selectedScreenTimeMinutes)

        viewModel.updateScreenTimeMinutes(1441)
        assertNotNull(viewModel.uiState.value.screenTimeError)
        assertEquals(1441, viewModel.uiState.value.selectedScreenTimeMinutes)
    }

    @Test
    fun toggleScreenTimeEnabled_togglesStateCorrectly() {
        viewModel.toggleScreenTimeEnabled(false)
        assertFalse(viewModel.uiState.value.isScreenTimeEnabled)
        assertFalse(viewModel.uiState.value.screenTimeSaveSuccess)

        viewModel.toggleScreenTimeEnabled(true)
        assertTrue(viewModel.uiState.value.isScreenTimeEnabled)
    }

    @Test
    fun loadScreenTimeLimitInternal_fetchesFromRepositoryAndUpdatesState() = runBlocking {
        val testDevice = "device_child_test_1"
        fakeScreenTimeRepository.setLimitDirectly(
            ScreenTimeLimit(
                familyId = "family_abc",
                deviceId = testDevice,
                dailyLimitMinutes = 60,
                enabled = true
            )
        )

        val result = viewModel.loadScreenTimeLimitInternal(testDevice)
        assertTrue(result is Result.Success)

        val state = viewModel.uiState.value
        assertEquals(60, state.screenTimeLimitMinutes)
        assertEquals(60, state.selectedScreenTimeMinutes)
        assertTrue(state.isScreenTimeEnabled)
        assertFalse(state.isScreenTimeLoading)
        assertNull(state.screenTimeError)
        assertEquals(testDevice, state.childDeviceId)
    }

    @Test
    fun saveScreenTimeLimitInternal_whenValid_updatesServerAndReadsBackConfirmation() = runBlocking {
        val testDevice = "device_child_test_2"
        viewModel.setTargetChildDeviceId(testDevice)
        viewModel.updateScreenTimeMinutes(180)
        viewModel.toggleScreenTimeEnabled(true)

        val saveResult = viewModel.saveScreenTimeLimitInternal()
        assertTrue(saveResult is Result.Success)

        val state = viewModel.uiState.value
        assertFalse(state.isScreenTimeSaving)
        assertTrue(state.screenTimeSaveSuccess)
        assertEquals(180, state.screenTimeLimitMinutes)
        assertEquals(180, state.selectedScreenTimeMinutes)
        assertTrue(state.isScreenTimeEnabled)
        assertNull(state.screenTimeError)

        // Verify that the repository actually has the saved limit
        val storedInRepo = fakeScreenTimeRepository.getStoredLimit(testDevice)
        assertNotNull(storedInRepo)
        assertEquals(180, storedInRepo?.dailyLimitMinutes)
        assertTrue(storedInRepo?.enabled == true)
    }

    @Test
    fun saveScreenTimeLimitInternal_whenNoChildDeviceConnected_failsValidation() = runBlocking {
        // No childDeviceId set
        val saveResult = viewModel.saveScreenTimeLimitInternal()
        assertTrue(saveResult is Result.Error)
        assertNotNull(viewModel.uiState.value.screenTimeError)
        assertFalse(viewModel.uiState.value.screenTimeSaveSuccess)
        assertFalse(viewModel.uiState.value.isScreenTimeSaving)
    }

    @Test
    fun saveScreenTimeLimitInternal_whenMinutesInvalid_failsLocallyWithoutCallingRepository() = runBlocking {
        viewModel.setTargetChildDeviceId("device_child_test_3")
        viewModel.updateScreenTimeMinutes(2000)

        val saveResult = viewModel.saveScreenTimeLimitInternal()
        assertTrue(saveResult is Result.Error)
        assertNotNull(viewModel.uiState.value.screenTimeError)
        assertFalse(viewModel.uiState.value.screenTimeSaveSuccess)

        // Repository should not have been called
        assertNull(fakeScreenTimeRepository.getStoredLimit("device_child_test_3"))
    }

    @Test
    fun saveScreenTimeLimitInternal_whenRepositoryFails_setsErrorState() = runBlocking {
        val testDevice = "device_child_test_4"
        viewModel.setTargetChildDeviceId(testDevice)
        viewModel.updateScreenTimeMinutes(90)
        fakeScreenTimeRepository.shouldFail = true
        fakeScreenTimeRepository.failureMessage = "Database network timeout"

        val saveResult = viewModel.saveScreenTimeLimitInternal()
        assertTrue(saveResult is Result.Error)

        val state = viewModel.uiState.value
        assertFalse(state.isScreenTimeSaving)
        assertFalse(state.screenTimeSaveSuccess)
        assertEquals("Database network timeout", state.screenTimeError)
    }
}
