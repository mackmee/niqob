package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.remote.supabase.SupabaseConfig
import com.niqob.app.data.remote.supabase.dto.GetScreenTimeLimitRpcResponse
import com.niqob.app.data.remote.supabase.dto.ScreenTimeLimitTable
import com.niqob.app.data.remote.supabase.dto.SetScreenTimeLimitRpcResponse
import com.niqob.app.data.repository.SupabaseScreenTimeRepository
import com.niqob.app.domain.model.ScreenTimeLimit
import com.niqob.app.fakes.FakeScreenTimeRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenTimeLimitTest {

    private lateinit var fakeRepository: FakeScreenTimeRepository
    private lateinit var supabaseRepository: SupabaseScreenTimeRepository
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        fakeRepository = FakeScreenTimeRepository(defaultFamilyId = "family_123")
        supabaseRepository = SupabaseScreenTimeRepository()
    }

    @After
    fun tearDown() {
        SupabaseConfig.reset()
    }

    @Test
    fun screenTimeLimit_defaultValues_areValid() {
        val limit = ScreenTimeLimit(
            familyId = "family_123",
            deviceId = "device_child_1"
        )

        assertEquals("family_123", limit.familyId)
        assertEquals("device_child_1", limit.deviceId)
        assertEquals(120, limit.dailyLimitMinutes)
        assertTrue(limit.enabled)
        assertTrue(limit.createdAt > 0)
        assertTrue(limit.updatedAt > 0)
    }

    @Test
    fun getScreenTimeLimit_whenNoneSet_returnsSensibleDefault() = runBlocking {
        val result = fakeRepository.getScreenTimeLimit("device_child_1")
        assertTrue(result is Result.Success)

        val limit = (result as Result.Success).data
        assertEquals("family_123", limit.familyId)
        assertEquals("device_child_1", limit.deviceId)
        assertEquals(120, limit.dailyLimitMinutes)
        assertTrue(limit.enabled)
    }

    @Test
    fun setScreenTimeLimit_persistsAndReturnsUpdatedLimit() = runBlocking {
        val setResult = fakeRepository.setScreenTimeLimit(
            deviceId = "device_child_1",
            dailyLimitMinutes = 90,
            enabled = true
        )
        assertTrue(setResult is Result.Success)

        val savedLimit = (setResult as Result.Success).data
        assertEquals(90, savedLimit.dailyLimitMinutes)
        assertTrue(savedLimit.enabled)
        assertEquals("device_child_1", savedLimit.deviceId)

        // Query repository to confirm persistence
        val getResult = fakeRepository.getScreenTimeLimit("device_child_1")
        assertTrue(getResult is Result.Success)
        val queriedLimit = (getResult as Result.Success).data
        assertEquals(90, queriedLimit.dailyLimitMinutes)
        assertTrue(queriedLimit.enabled)
    }

    @Test
    fun setScreenTimeLimit_canDisableLimit() = runBlocking {
        val setResult = fakeRepository.setScreenTimeLimit(
            deviceId = "device_child_1",
            dailyLimitMinutes = 60,
            enabled = false
        )
        assertTrue(setResult is Result.Success)

        val savedLimit = (setResult as Result.Success).data
        assertEquals(60, savedLimit.dailyLimitMinutes)
        assertFalse(savedLimit.enabled)
    }

    @Test
    fun setScreenTimeLimit_rejectsNegativeMinutes() = runBlocking {
        val result = fakeRepository.setScreenTimeLimit(
            deviceId = "device_child_1",
            dailyLimitMinutes = -15,
            enabled = true
        )
        assertTrue(result is Result.Error)
    }

    @Test
    fun setScreenTimeLimit_rejectsMinutesExceeding24Hours() = runBlocking {
        val result = fakeRepository.setScreenTimeLimit(
            deviceId = "device_child_1",
            dailyLimitMinutes = 1500,
            enabled = true
        )
        assertTrue(result is Result.Error)
    }

    @Test
    fun repository_failureSimulation_returnsError() = runBlocking {
        fakeRepository.shouldFail = true
        fakeRepository.failureMessage = "Database connectivity timeout"

        val getResult = fakeRepository.getScreenTimeLimit("device_child_1")
        assertTrue(getResult is Result.Error)
        assertEquals("Database connectivity timeout", (getResult as Result.Error).exception.message)

        val setResult = fakeRepository.setScreenTimeLimit("device_child_1", 100, true)
        assertTrue(setResult is Result.Error)
        assertEquals("Database connectivity timeout", (setResult as Result.Error).exception.message)
    }

    @Test
    fun supabaseRepository_validatesLimitsBeforeExecution() = runBlocking {
        val negResult = supabaseRepository.setScreenTimeLimit("dev_1", -10, true)
        assertTrue(negResult is Result.Error)

        val excessiveResult = supabaseRepository.setScreenTimeLimit("dev_1", 2000, true)
        assertTrue(excessiveResult is Result.Error)
    }

    @Test
    fun supabaseRepository_whenUnconfigured_returnsClearConfigurationError() = runBlocking {
        SupabaseConfig.setCustomConfig("", "")

        val getResult = supabaseRepository.getScreenTimeLimit("device_1")
        assertTrue(getResult is Result.Error)
        val getError = (getResult as Result.Error).message
        assertTrue(getError?.contains("Supabase credentials not configured") == true)

        val setResult = supabaseRepository.setScreenTimeLimit("device_1", 120, true)
        assertTrue(setResult is Result.Error)
        val setError = (setResult as Result.Error).message
        assertTrue(setError?.contains("Supabase credentials not configured") == true)
    }

    @Test
    fun supabaseRepository_mapsTableRecordToDomainCorrectly() {
        val tableRecord = ScreenTimeLimitTable(
            id = "lim_001",
            familyId = "fam_123",
            deviceId = "dev_456",
            dailyLimitMinutes = 180,
            enabled = true,
            createdAt = "2026-10-02T12:00:00Z",
            updatedAt = "2026-10-02T12:00:00Z"
        )

        val domain = supabaseRepository.mapTableToDomain(tableRecord)
        assertEquals("lim_001", domain.id)
        assertEquals("fam_123", domain.familyId)
        assertEquals("dev_456", domain.deviceId)
        assertEquals(180, domain.dailyLimitMinutes)
        assertTrue(domain.enabled)
        assertTrue(domain.createdAt > 0)
        assertTrue(domain.updatedAt > 0)
    }

    @Test
    fun screenTimeDtos_serializationRoundTrip_succeeds() {
        val tableRecord = ScreenTimeLimitTable(
            id = "lim_001",
            familyId = "fam_123",
            deviceId = "dev_456",
            dailyLimitMinutes = 120,
            enabled = true,
            createdAt = "2026-10-02T12:00:00Z",
            updatedAt = "2026-10-02T12:00:00Z"
        )

        val setResponse = SetScreenTimeLimitRpcResponse(
            success = true,
            limit = tableRecord
        )
        val serializedSet = json.encodeToString(setResponse)
        val deserializedSet = json.decodeFromString<SetScreenTimeLimitRpcResponse>(serializedSet)
        assertTrue(deserializedSet.success)
        assertEquals("dev_456", deserializedSet.limit?.deviceId)
        assertEquals(120, deserializedSet.limit?.dailyLimitMinutes)

        val getResponse = GetScreenTimeLimitRpcResponse(
            success = true,
            isDefault = false,
            limit = tableRecord
        )
        val serializedGet = json.encodeToString(getResponse)
        val deserializedGet = json.decodeFromString<GetScreenTimeLimitRpcResponse>(serializedGet)
        assertTrue(deserializedGet.success)
        assertFalse(deserializedGet.isDefault ?: true)
        assertEquals("fam_123", deserializedGet.limit?.familyId)
    }
}
