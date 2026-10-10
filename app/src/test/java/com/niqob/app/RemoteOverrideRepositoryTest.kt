package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.remote.supabase.dto.ApproveOverrideResponseDto
import com.niqob.app.data.remote.supabase.dto.ConsumeOverrideResponseDto
import com.niqob.app.data.remote.supabase.dto.CreateOverrideResponseDto
import com.niqob.app.data.remote.supabase.dto.GetPendingOverridesResponseDto
import com.niqob.app.data.remote.supabase.dto.OverrideStatusResponseDto
import com.niqob.app.data.remote.supabase.dto.PendingOverrideItemDto
import com.niqob.app.data.remote.supabase.dto.RejectOverrideResponseDto
import com.niqob.app.data.remote.supabase.dto.RevokeOverrideResponseDto
import com.niqob.app.data.repository.SupabaseRemoteOverrideRepository
import com.niqob.app.domain.model.ConsumedOverrideResult
import com.niqob.app.domain.model.RemoteOverrideRequest
import com.niqob.app.domain.model.RemoteOverrideRequestStatus
import com.niqob.app.domain.usecase.ConsumeRemoteOverrideUseCase
import com.niqob.app.fakes.FakeRemoteOverrideRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class RemoteOverrideRepositoryTest {

    private lateinit var fakeRepository: FakeRemoteOverrideRepository
    private lateinit var consumeUseCase: ConsumeRemoteOverrideUseCase
    private val json = Json { ignoreUnknownKeys = true }

    private val fixedInstant = Instant.parse("2026-10-05T10:00:00Z")

    @Before
    fun setUp() {
        fakeRepository = FakeRemoteOverrideRepository(clock = { fixedInstant })
        consumeUseCase = ConsumeRemoteOverrideUseCase(fakeRepository)
    }

    // --------------------------------------------------------------------------
    // A. Valid Child Request: 15, 30, 60 minutes
    // --------------------------------------------------------------------------
    @Test
    fun createRequest_validDurations_areAccepted() = runBlocking {
        for (duration in listOf(15, 30, 60)) {
            fakeRepository.clear()
            val result = fakeRepository.createRequest(
                deviceId = "dev-child-$duration",
                requestedDurationMinutes = duration,
                reason = "Need for math homework"
            )

            assertTrue("Duration $duration should succeed", result is Result.Success)
            val request = (result as Result.Success).data
            assertEquals("dev-child-$duration", request.deviceId)
            assertEquals(duration, request.requestedDurationMinutes)
            assertEquals(RemoteOverrideRequestStatus.PENDING, request.status)
            assertEquals("Need for math homework", request.reason)
            assertNotNull(request.claimExpiresAt)
        }
    }

    // --------------------------------------------------------------------------
    // B. Invalid Durations: 1, 14, 16, 45, 120 rejected locally
    // --------------------------------------------------------------------------
    @Test
    fun createRequest_invalidDurations_areRejectedLocally() = runBlocking {
        val invalidDurations = listOf(1, 14, 16, 45, 120, -15, 0)
        for (invalidDuration in invalidDurations) {
            val result = fakeRepository.createRequest(
                deviceId = "dev-child-1",
                requestedDurationMinutes = invalidDuration
            )

            assertTrue("Duration $invalidDuration should fail", result is Result.Error)
            val error = (result as Result.Error).exception
            assertTrue(error is IllegalArgumentException)
        }
    }

    @Test
    fun approveRequest_invalidDurations_areRejectedLocally() = runBlocking {
        val invalidDurations = listOf(1, 14, 16, 45, 120)
        for (invalidDuration in invalidDurations) {
            val result = fakeRepository.approveRequest(
                requestId = "req-123",
                approvedDurationMinutes = invalidDuration
            )
            assertTrue("Approved duration $invalidDuration should fail", result is Result.Error)
            assertTrue((result as Result.Error).exception is IllegalArgumentException)
        }
    }

    // --------------------------------------------------------------------------
    // C. Reason Validation: null, empty, 200 chars allowed, 201 chars rejected
    // --------------------------------------------------------------------------
    @Test
    fun createRequest_reasonValidation() = runBlocking {
        // null reason
        val nullResult = fakeRepository.createRequest("dev-1", 15, null)
        assertTrue(nullResult is Result.Success)
        assertNull((nullResult as Result.Success).data.reason)

        fakeRepository.clear()

        // empty reason
        val emptyResult = fakeRepository.createRequest("dev-1", 15, "")
        assertTrue(emptyResult is Result.Success)

        fakeRepository.clear()

        // exactly 200 chars
        val exact200 = "A".repeat(200)
        val valid200Result = fakeRepository.createRequest("dev-1", 15, exact200)
        assertTrue(valid200Result is Result.Success)
        assertEquals(200, (valid200Result as Result.Success).data.reason?.length)

        fakeRepository.clear()

        // 201 chars
        val over200 = "A".repeat(201)
        val invalidResult = fakeRepository.createRequest("dev-1", 15, over200)
        assertTrue(invalidResult is Result.Error)
        assertTrue((invalidResult as Result.Error).exception is IllegalArgumentException)
    }

    @Test
    fun rejectRequest_rejectionReasonValidation() = runBlocking {
        // 200 chars allowed
        val exact200 = "R".repeat(200)
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-test-1",
                deviceId = "dev-1",
                requestedDurationMinutes = 15,
                status = RemoteOverrideRequestStatus.PENDING,
                createdAt = fixedInstant
            )
        )
        val validResult = fakeRepository.rejectRequest("req-test-1", exact200)
        assertTrue(validResult is Result.Success)
        assertEquals(200, (validResult as Result.Success).data.rejectionReason?.length)

        // 201 chars rejected
        val over200 = "R".repeat(201)
        val invalidResult = fakeRepository.rejectRequest("req-test-1", over200)
        assertTrue(invalidResult is Result.Error)
        assertTrue((invalidResult as Result.Error).exception is IllegalArgumentException)
    }

    // --------------------------------------------------------------------------
    // D. Invalid Blank Device / Request IDs Rejected Locally
    // --------------------------------------------------------------------------
    @Test
    fun blankIdentifiers_areRejectedLocallyAcrossAllMethods() = runBlocking {
        // createRequest
        assertTrue(fakeRepository.createRequest("   ", 15) is Result.Error)

        // getRequestStatus
        assertTrue(fakeRepository.getRequestStatus("", "req-1") is Result.Error)
        assertTrue(fakeRepository.getRequestStatus("dev-1", "  ") is Result.Error)

        // consumeOverride
        assertTrue(fakeRepository.consumeOverride("", "req-1") is Result.Error)
        assertTrue(fakeRepository.consumeOverride("dev-1", "") is Result.Error)

        // getPendingRequests
        assertTrue(fakeRepository.getPendingRequests("   ") is Result.Error)

        // approveRequest
        assertTrue(fakeRepository.approveRequest("", 15) is Result.Error)

        // rejectRequest
        assertTrue(fakeRepository.rejectRequest("  ", "No") is Result.Error)

        // revokeOverride
        assertTrue(fakeRepository.revokeOverride("") is Result.Error)
    }

    // --------------------------------------------------------------------------
    // E. Approved Response Parsing
    // --------------------------------------------------------------------------
    @Test
    fun parseApprovedResponse_actualMigrationShape() {
        val jsonString = """
            {
                "success": true,
                "request_id": "b3e0c0b8-4c8d-4f76-88eb-7117e33501a1",
                "status": "APPROVED",
                "approved_duration_minutes": 30,
                "decided_at": "2026-10-05T10:15:00+00:00",
                "claim_expires_at": "2026-10-05T10:30:00+00:00"
            }
        """.trimIndent()

        val dto = json.decodeFromString<ApproveOverrideResponseDto>(jsonString)
        assertTrue(dto.success)
        assertEquals("b3e0c0b8-4c8d-4f76-88eb-7117e33501a1", dto.requestId)
        assertEquals("APPROVED", dto.status)
        assertEquals(30, dto.approvedDurationMinutes)
        assertEquals("2026-10-05T10:15:00+00:00", dto.decidedAt)
        assertEquals("2026-10-05T10:30:00+00:00", dto.claimExpiresAt)

        val decidedInstant = SupabaseRemoteOverrideRepository.parseIsoInstant(dto.decidedAt)
        assertNotNull(decidedInstant)
        assertEquals(Instant.parse("2026-10-05T10:15:00Z"), decidedInstant)
    }

    // --------------------------------------------------------------------------
    // F. Rejected Response Parsing
    // --------------------------------------------------------------------------
    @Test
    fun parseRejectedResponse_actualMigrationShape() {
        val jsonString = """
            {
                "success": true,
                "request_id": "b3e0c0b8-4c8d-4f76-88eb-7117e33501a1",
                "status": "REJECTED",
                "rejection_reason": "Bedtime soon, finish homework tomorrow.",
                "decided_at": "2026-10-05T10:16:00+00:00"
            }
        """.trimIndent()

        val dto = json.decodeFromString<RejectOverrideResponseDto>(jsonString)
        assertTrue(dto.success)
        assertEquals("b3e0c0b8-4c8d-4f76-88eb-7117e33501a1", dto.requestId)
        assertEquals("REJECTED", dto.status)
        assertEquals("Bedtime soon, finish homework tomorrow.", dto.rejectionReason)
        assertEquals("2026-10-05T10:16:00+00:00", dto.decidedAt)
    }

    // --------------------------------------------------------------------------
    // G. Expired Response Parsing
    // --------------------------------------------------------------------------
    @Test
    fun parseExpiredStatusResponse_actualMigrationShape() {
        val jsonString = """
            {
                "success": true,
                "request_id": "b3e0c0b8-4c8d-4f76-88eb-7117e33501a1",
                "device_id": "a1b2c3d4-0000-0000-0000-000000000001",
                "status": "EXPIRED",
                "requested_duration_minutes": 15,
                "approved_duration_minutes": 15,
                "reason": null,
                "rejection_reason": null,
                "created_at": "2026-10-05T09:30:00+00:00",
                "claim_expires_at": "2026-10-05T09:45:00+00:00",
                "decided_at": null,
                "consumed_at": null,
                "consumed_expires_at": null,
                "is_active": false,
                "is_expired": true,
                "remaining_seconds": 0
            }
        """.trimIndent()

        val dto = json.decodeFromString<OverrideStatusResponseDto>(jsonString)
        assertTrue(dto.success)
        assertEquals("EXPIRED", dto.status)
        assertFalse(dto.isActive)
        assertTrue(dto.isExpired)
        assertEquals(0L, dto.remainingSeconds)

        val domain = SupabaseRemoteOverrideRepository.mapStatusDtoToDomain(dto, fixedInstant)
        assertEquals(RemoteOverrideRequestStatus.EXPIRED, domain.status)
        assertFalse(domain.isActive)
        assertTrue(domain.isExpired)
        assertEquals(0L, domain.remainingSeconds)
    }

    // --------------------------------------------------------------------------
    // H. Revoked Response Parsing
    // --------------------------------------------------------------------------
    @Test
    fun parseRevokedResponse_actualMigrationShape() {
        val jsonString = """
            {
                "success": true,
                "request_id": "b3e0c0b8-4c8d-4f76-88eb-7117e33501a1",
                "status": "REVOKED",
                "revoked_at": "2026-10-05T10:25:00+00:00"
            }
        """.trimIndent()

        val dto = json.decodeFromString<RevokeOverrideResponseDto>(jsonString)
        assertTrue(dto.success)
        assertEquals("REVOKED", dto.status)
        assertEquals("2026-10-05T10:25:00+00:00", dto.revokedAt)

        val revokedInstant = SupabaseRemoteOverrideRepository.parseIsoInstant(dto.revokedAt)
        assertNotNull(revokedInstant)
        assertEquals(Instant.parse("2026-10-05T10:25:00Z"), revokedInstant)
    }

    // --------------------------------------------------------------------------
    // I. Consumed Response Parsing
    // --------------------------------------------------------------------------
    @Test
    fun parseConsumedResponse_actualMigrationShape() {
        val jsonString = """
            {
                "success": true,
                "status": "CONSUMED",
                "request_id": "b3e0c0b8-4c8d-4f76-88eb-7117e33501a1",
                "approved_duration_minutes": 15,
                "consumed_at": "2026-10-05T10:00:00+00:00",
                "consumed_expires_at": "2026-10-05T10:15:00+00:00"
            }
        """.trimIndent()

        val dto = json.decodeFromString<ConsumeOverrideResponseDto>(jsonString)
        assertTrue(dto.success)
        assertEquals("CONSUMED", dto.status)
        assertEquals("b3e0c0b8-4c8d-4f76-88eb-7117e33501a1", dto.requestId)
        assertEquals(15, dto.approvedDurationMinutes)
        assertEquals("2026-10-05T10:00:00+00:00", dto.consumedAt)
        assertEquals("2026-10-05T10:15:00+00:00", dto.consumedExpiresAt)

        val consumedAt = SupabaseRemoteOverrideRepository.parseIsoInstant(dto.consumedAt)
        val consumedExpiresAt = SupabaseRemoteOverrideRepository.parseIsoInstant(dto.consumedExpiresAt)

        assertNotNull(consumedAt)
        assertNotNull(consumedExpiresAt)
        assertEquals(900_000L, consumedExpiresAt!!.toEpochMilli() - consumedAt!!.toEpochMilli())
    }

    // --------------------------------------------------------------------------
    // J. remainingSeconds Parsing
    // --------------------------------------------------------------------------
    @Test
    fun parseRemainingSeconds_activeConsumedStatus() {
        val jsonString = """
            {
                "success": true,
                "request_id": "req-1",
                "device_id": "dev-1",
                "status": "CONSUMED",
                "requested_duration_minutes": 15,
                "approved_duration_minutes": 15,
                "consumed_at": "2026-10-05T10:00:00+00:00",
                "consumed_expires_at": "2026-10-05T10:15:00+00:00",
                "is_active": true,
                "is_expired": false,
                "remaining_seconds": 642
            }
        """.trimIndent()

        val dto = json.decodeFromString<OverrideStatusResponseDto>(jsonString)
        assertEquals(642L, dto.remainingSeconds)
        assertTrue(dto.isActive)
        assertFalse(dto.isExpired)

        val domain = SupabaseRemoteOverrideRepository.mapStatusDtoToDomain(dto, fixedInstant)
        assertEquals(642L, domain.remainingSeconds)
        assertTrue(domain.isActive)
        assertFalse(domain.isExpired)
    }

    // --------------------------------------------------------------------------
    // K. consume requires requestId + deviceId only
    // --------------------------------------------------------------------------
    @Test
    fun consumeOverride_acceptsStrictlyDeviceIdAndRequestId() = runBlocking {
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-1",
                deviceId = "dev-1",
                requestedDurationMinutes = 15,
                approvedDurationMinutes = 30,
                status = RemoteOverrideRequestStatus.APPROVED,
                createdAt = fixedInstant
            )
        )

        val result = fakeRepository.consumeOverride(deviceId = "dev-1", requestId = "req-1")
        assertTrue(result is Result.Success)
        val consumed = (result as Result.Success).data
        assertEquals("req-1", consumed.requestId)
        assertEquals(30, consumed.approvedDurationMinutes)
        assertEquals(RemoteOverrideRequestStatus.CONSUMED, consumed.status)

        // Verifies the call tracker received only deviceId and requestId
        assertEquals("dev-1", fakeRepository.lastConsumedDeviceId)
        assertEquals("req-1", fakeRepository.lastConsumedRequestId)
    }

    // --------------------------------------------------------------------------
    // L & M. Repository never sends child-provided expiration or duration on consume
    // --------------------------------------------------------------------------
    @Test
    fun consumeOverride_guaranteesServerAuthoritativeDurationAndExpiration() = runBlocking {
        // Even if child requested 15, parent approved 60: consume strictly applies the approved 60
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-long",
                deviceId = "dev-child",
                requestedDurationMinutes = 15,
                approvedDurationMinutes = 60,
                status = RemoteOverrideRequestStatus.APPROVED,
                createdAt = fixedInstant
            )
        )

        val result = fakeRepository.consumeOverride("dev-child", "req-long")
        assertTrue(result is Result.Success)
        val consumed = (result as Result.Success).data
        assertEquals(60, consumed.approvedDurationMinutes)
        assertEquals(
            3600_000L,
            consumed.consumedExpiresAtMillis - consumed.consumedAtMillis
        )
    }

    @Test
    fun consumeOverride_doubleSpendFails() = runBlocking {
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-once",
                deviceId = "dev-1",
                requestedDurationMinutes = 15,
                approvedDurationMinutes = 15,
                status = RemoteOverrideRequestStatus.APPROVED,
                createdAt = fixedInstant
            )
        )

        // First consume succeeds
        val first = fakeRepository.consumeOverride("dev-1", "req-once")
        assertTrue(first is Result.Success)

        // Second consume must fail
        val second = fakeRepository.consumeOverride("dev-1", "req-once")
        assertTrue(second is Result.Error)
        assertEquals(
            "This override has already been consumed.",
            (second as Result.Error).exception.message
        )
    }

    // --------------------------------------------------------------------------
    // N. Actual Response Shapes: get_pending_override_requests
    // --------------------------------------------------------------------------
    @Test
    fun parsePendingOverridesListResponse_actualMigrationShape() {
        val jsonString = """
            {
                "success": true,
                "requests": [
                    {
                        "request_id": "r1",
                        "device_id": "d1",
                        "requested_duration_minutes": 15,
                        "approved_duration_minutes": null,
                        "status": "PENDING",
                        "reason": "Math project",
                        "rejection_reason": null,
                        "created_at": "2026-10-05T10:00:00+00:00",
                        "claim_expires_at": "2026-10-05T10:15:00+00:00",
                        "decided_at": null,
                        "consumed_at": null,
                        "consumed_expires_at": null,
                        "revoked_at": null
                    },
                    {
                        "request_id": "r2",
                        "device_id": "d2",
                        "requested_duration_minutes": 30,
                        "approved_duration_minutes": null,
                        "status": "PENDING",
                        "reason": null,
                        "rejection_reason": null,
                        "created_at": "2026-10-05T10:05:00+00:00",
                        "claim_expires_at": "2026-10-05T10:20:00+00:00",
                        "decided_at": null,
                        "consumed_at": null,
                        "consumed_expires_at": null,
                        "revoked_at": null
                    }
                ]
            }
        """.trimIndent()

        val dto = json.decodeFromString<GetPendingOverridesResponseDto>(jsonString)
        assertTrue(dto.success)
        assertEquals(2, dto.requests.size)

        val item1 = dto.requests[0]
        assertEquals("r1", item1.requestId)
        assertEquals(15, item1.requestedDurationMinutes)
        assertEquals("Math project", item1.reason)

        val domain1 = SupabaseRemoteOverrideRepository.mapPendingItemToDomain(item1, fixedInstant)
        assertEquals("r1", domain1.id)
        assertEquals("d1", domain1.deviceId)
        assertEquals(15, domain1.requestedDurationMinutes)
        assertEquals(RemoteOverrideRequestStatus.PENDING, domain1.status)
        assertEquals(Instant.parse("2026-10-05T10:00:00Z"), domain1.createdAt)
    }

    // --------------------------------------------------------------------------
    // O. ConsumeRemoteOverrideUseCase Integration Seam
    // --------------------------------------------------------------------------
    @Test
    fun consumeUseCase_validConsume_returnsConsumedResult() = runBlocking {
        fakeRepository.seedRequest(
            RemoteOverrideRequest(
                id = "req-usecase",
                deviceId = "dev-usecase",
                requestedDurationMinutes = 15,
                approvedDurationMinutes = 15,
                status = RemoteOverrideRequestStatus.APPROVED,
                createdAt = fixedInstant
            )
        )

        val result = consumeUseCase("dev-usecase", "req-usecase")
        assertTrue(result is Result.Success)
        val data = (result as Result.Success).data
        assertEquals("req-usecase", data.requestId)
        assertEquals(15, data.approvedDurationMinutes)
        assertEquals(RemoteOverrideRequestStatus.CONSUMED, data.status)
        assertEquals(fixedInstant, data.consumedAt)
        assertEquals(fixedInstant.plusSeconds(900), data.consumedExpiresAt)
    }

    @Test
    fun consumeUseCase_blankIds_failsFast() = runBlocking {
        val blankDevice = consumeUseCase("  ", "req-1")
        assertTrue(blankDevice is Result.Error)
        assertTrue((blankDevice as Result.Error).exception is IllegalArgumentException)

        val blankRequest = consumeUseCase("dev-1", "  ")
        assertTrue(blankRequest is Result.Error)
        assertTrue((blankRequest as Result.Error).exception is IllegalArgumentException)
    }

    @Test
    fun consumeUseCase_repositoryError_propagatesFailure() = runBlocking {
        fakeRepository.shouldFail = true
        fakeRepository.failureException = IllegalStateException("Database connection timeout")

        val result = consumeUseCase("dev-1", "req-1")
        assertTrue(result is Result.Error)
        assertEquals(
            "Database connection timeout",
            (result as Result.Error).exception.message
        )
    }
}
