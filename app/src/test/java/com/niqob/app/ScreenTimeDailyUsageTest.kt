package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.remote.supabase.SupabaseConfig
import com.niqob.app.data.remote.supabase.dto.DailyUsageRecordDto
import com.niqob.app.data.remote.supabase.dto.GetDailyUsageResponseDto
import com.niqob.app.data.remote.supabase.dto.UpsertDailyUsageResponseDto
import com.niqob.app.data.repository.SupabaseScreenTimeUsageRepository
import com.niqob.app.domain.model.ScreenTimeDailyUsage
import com.niqob.app.fakes.FakeScreenTimeUsageRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenTimeDailyUsageTest {

    private lateinit var fakeRepository: FakeScreenTimeUsageRepository
    private lateinit var supabaseRepository: SupabaseScreenTimeUsageRepository
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        fakeRepository = FakeScreenTimeUsageRepository()
        supabaseRepository = SupabaseScreenTimeUsageRepository()
    }

    @After
    fun tearDown() {
        SupabaseConfig.reset()
    }

    @Test
    fun domainModel_defaultValues_areValid() {
        val usage = ScreenTimeDailyUsage(
            deviceId = "dev_child_1",
            usageDate = "2026-10-03",
            totalUsageMinutes = 45,
            dailyLimitMinutes = 120,
            remainingMinutes = 75,
            usagePercentage = 38,
            isLimitReached = false
        )

        assertEquals("dev_child_1", usage.deviceId)
        assertEquals("2026-10-03", usage.usageDate)
        assertEquals(45, usage.totalUsageMinutes)
        assertEquals(120, usage.dailyLimitMinutes)
        assertEquals(75, usage.remainingMinutes)
        assertEquals(38, usage.usagePercentage)
        assertFalse(usage.isLimitReached)
        assertEquals("Within limit", usage.statusText)
    }

    @Test
    fun domainModel_whenLimitReached_statusTextIsCorrect() {
        val usage = ScreenTimeDailyUsage(
            deviceId = "dev_child_1",
            usageDate = "2026-10-03",
            totalUsageMinutes = 135,
            dailyLimitMinutes = 120,
            remainingMinutes = 0,
            usagePercentage = 113,
            isLimitReached = true
        )

        assertTrue(usage.isLimitReached)
        assertEquals("Limit reached", usage.statusText)
    }

    @Test
    fun dtoSerialization_roundTrip_dailyUsageRecord() {
        val dto = DailyUsageRecordDto(
            id = "usage-uuid-1234",
            familyId = "fam-uuid-5678",
            deviceId = "dev-uuid-9999",
            usageDate = "2026-10-03",
            totalUsageMinutes = 45,
            dailyLimitMinutes = 120,
            remainingMinutes = 75,
            usagePercentage = 38,
            isLimitReached = false,
            createdAt = "2026-10-03T06:00:00Z",
            updatedAt = "2026-10-03T06:30:00Z"
        )

        val serialized = json.encodeToString(dto)
        val deserialized = json.decodeFromString<DailyUsageRecordDto>(serialized)

        assertEquals(dto.id, deserialized.id)
        assertEquals(dto.familyId, deserialized.familyId)
        assertEquals(dto.deviceId, deserialized.deviceId)
        assertEquals(dto.usageDate, deserialized.usageDate)
        assertEquals(dto.totalUsageMinutes, deserialized.totalUsageMinutes)
        assertEquals(dto.dailyLimitMinutes, deserialized.dailyLimitMinutes)
        assertEquals(dto.remainingMinutes, deserialized.remainingMinutes)
        assertEquals(dto.usagePercentage, deserialized.usagePercentage)
        assertEquals(dto.isLimitReached, deserialized.isLimitReached)
    }

    @Test
    fun dtoSerialization_upsertResponse_roundTrip() {
        val response = UpsertDailyUsageResponseDto(
            success = true,
            usage = DailyUsageRecordDto(
                id = "usage-1",
                deviceId = "dev-1",
                usageDate = "2026-10-03",
                totalUsageMinutes = 60,
                dailyLimitMinutes = 120,
                remainingMinutes = 60,
                usagePercentage = 50,
                isLimitReached = false
            )
        )

        val jsonStr = json.encodeToString(response)
        val decoded = json.decodeFromString<UpsertDailyUsageResponseDto>(jsonStr)

        assertTrue(decoded.success)
        assertNotNull(decoded.usage)
        assertEquals(60, decoded.usage?.totalUsageMinutes)
    }

    @Test
    fun dtoSerialization_getResponse_whenNoData_roundTrip() {
        val response = GetDailyUsageResponseDto(
            success = true,
            hasData = false,
            usage = null
        )

        val jsonStr = json.encodeToString(response)
        val decoded = json.decodeFromString<GetDailyUsageResponseDto>(jsonStr)

        assertTrue(decoded.success)
        assertFalse(decoded.hasData)
        assertNull(decoded.usage)
    }

    @Test
    fun repository_successfulUpsert_storesAndReturnsData() = runBlocking {
        val result = fakeRepository.upsertDailyUsage(
            deviceId = "dev_1",
            usageDate = "2026-10-03",
            totalUsageMinutes = 45,
            dailyLimitMinutes = 120,
            remainingMinutes = 75,
            usagePercentage = 38,
            isLimitReached = false
        )

        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals("dev_1", data.deviceId)
        assertEquals("2026-10-03", data.usageDate)
        assertEquals(45, data.totalUsageMinutes)
        assertEquals(120, data.dailyLimitMinutes)
        assertEquals(75, data.remainingMinutes)
        assertEquals(38, data.usagePercentage)
        assertFalse(data.isLimitReached)
    }

    @Test
    fun repository_readBack_returnsSyncedUsage() = runBlocking {
        fakeRepository.upsertDailyUsage(
            deviceId = "dev_1",
            usageDate = "2026-10-03",
            totalUsageMinutes = 50,
            dailyLimitMinutes = 100,
            remainingMinutes = 50,
            usagePercentage = 50,
            isLimitReached = false
        )

        val getResult = fakeRepository.getDailyUsage("dev_1", "2026-10-03")
        assertTrue(getResult is Result.Success)
        val readData = (getResult as Result.Success).data
        assertNotNull(readData)
        assertEquals(50, readData?.totalUsageMinutes)
        assertEquals(100, readData?.dailyLimitMinutes)
    }

    @Test
    fun repository_readBack_whenNoData_returnsSuccessWithNull() = runBlocking {
        val getResult = fakeRepository.getDailyUsage("dev_unknown", "2026-10-03")
        assertTrue(getResult is Result.Success)
        assertNull((getResult as Result.Success).data)
    }

    @Test
    fun repository_repeatedSync_updatesSameRowWithoutDuplicates() = runBlocking {
        // First sync: 30 minutes
        val firstResult = fakeRepository.upsertDailyUsage(
            deviceId = "dev_1",
            usageDate = "2026-10-03",
            totalUsageMinutes = 30,
            dailyLimitMinutes = 120,
            remainingMinutes = 90,
            usagePercentage = 25,
            isLimitReached = false
        )
        val firstId = (firstResult as Result.Success).data.id

        // Repeated sync: 45 minutes on same day
        val secondResult = fakeRepository.upsertDailyUsage(
            deviceId = "dev_1",
            usageDate = "2026-10-03",
            totalUsageMinutes = 45,
            dailyLimitMinutes = 120,
            remainingMinutes = 75,
            usagePercentage = 38,
            isLimitReached = false
        )
        val secondData = (secondResult as Result.Success).data

        // Must update existing row, retain same primary ID, and NOT create duplicates
        assertEquals(firstId, secondData.id)
        assertEquals(45, secondData.totalUsageMinutes)
        assertEquals(1, fakeRepository.getStoredCount())
    }

    @Test
    fun repository_validationBoundaries_negativeUsageRejected() = runBlocking {
        val result = fakeRepository.upsertDailyUsage(
            deviceId = "dev_1",
            usageDate = "2026-10-03",
            totalUsageMinutes = -5
        )
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).exception is IllegalArgumentException)
    }

    @Test
    fun repository_validationBoundaries_blankDeviceIdRejected() = runBlocking {
        val result = fakeRepository.upsertDailyUsage(
            deviceId = "   ",
            usageDate = "2026-10-03",
            totalUsageMinutes = 10
        )
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).exception is IllegalArgumentException)
    }

    @Test
    fun repository_zeroUsage_handledGracefully() = runBlocking {
        val result = fakeRepository.upsertDailyUsage(
            deviceId = "dev_1",
            usageDate = "2026-10-03",
            totalUsageMinutes = 0,
            dailyLimitMinutes = 120
        )
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(0, data.totalUsageMinutes)
        assertEquals(120, data.remainingMinutes)
        assertEquals(0, data.usagePercentage)
        assertFalse(data.isLimitReached)
    }

    @Test
    fun repository_usageAboveLimit_setsLimitReached() = runBlocking {
        val result = fakeRepository.upsertDailyUsage(
            deviceId = "dev_1",
            usageDate = "2026-10-03",
            totalUsageMinutes = 150,
            dailyLimitMinutes = 120,
            remainingMinutes = 0,
            usagePercentage = 125,
            isLimitReached = true
        )
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals(150, data.totalUsageMinutes)
        assertEquals(0, data.remainingMinutes)
        assertTrue(data.isLimitReached)
    }

    @Test
    fun repository_errorHandling_simulatedFailure() = runBlocking {
        fakeRepository.shouldFail = true
        fakeRepository.failureException = RuntimeException("Unauthorized network call")

        val result = fakeRepository.upsertDailyUsage(
            deviceId = "dev_1",
            usageDate = "2026-10-03",
            totalUsageMinutes = 10
        )
        assertTrue(result is Result.Error)
        assertEquals("Unauthorized network call", (result as Result.Error).exception.message)
    }

    @Test
    fun supabaseRepository_withoutCredentials_failsSafely() = runBlocking {
        val result = supabaseRepository.upsertDailyUsage(
            deviceId = "dev_test",
            usageDate = "2026-10-03",
            totalUsageMinutes = 30
        )
        // Without Supabase initialized in isolated unit test, returns clean Result.Error
        assertTrue(result is Result.Error)
    }
}
