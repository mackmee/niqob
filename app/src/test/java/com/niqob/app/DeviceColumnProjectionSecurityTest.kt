package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.remote.supabase.dto.DeviceTable
import com.niqob.app.data.repository.SupabaseDeviceRepository
import com.niqob.app.domain.model.Device
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceColumnProjectionSecurityTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Test 1 — Confirm DEVICE_COLUMNS contains all required columns for the parent dashboard.
     */
    @Test
    fun testDeviceColumns_explicitAllowlist_containsAllRequiredDashboardFields() {
        val columns = SupabaseDeviceRepository.DEVICE_COLUMNS

        val requiredFields = listOf(
            "id",
            "family_id",
            "device_name",
            "platform",
            "device_type",
            "last_seen_at"
        )

        for (field in requiredFields) {
            assertTrue(
                "DEVICE_COLUMNS must contain required field '$field'",
                columns.contains(field)
            )
        }
    }

    /**
     * Test 2 — Confirm DEVICE_COLUMNS strictly excludes device_secret_hash and all secret-bearing columns.
     */
    @Test
    fun testDeviceColumns_explicitAllowlist_strictlyExcludesDeviceSecretHash() {
        val columns = SupabaseDeviceRepository.DEVICE_COLUMNS

        assertFalse(
            "DEVICE_COLUMNS must NEVER project device_secret_hash (SEC03-F04)",
            columns.contains("device_secret_hash")
        )
        assertFalse(
            "DEVICE_COLUMNS must NEVER project device_secret",
            columns.contains("device_secret")
        )

        // Ensure no column name contains "secret"
        for (column in columns) {
            assertFalse(
                "Column '$column' in DEVICE_COLUMNS must not be secret-bearing",
                column.contains("secret", ignoreCase = true)
            )
        }
    }

    /**
     * Test 3 — Confirm DeviceTable deserializes cleanly when only allowlisted columns are returned.
     */
    @Test
    fun testDeviceTableDeserialization_withAllowlistedColumnsOnly_succeeds() {
        val payload = """
            {
                "id": "device-uuid-111",
                "family_id": "family-uuid-222",
                "user_id": null,
                "device_name": "Kid Phone",
                "platform": "ANDROID",
                "device_type": "CHILD_PHONE",
                "last_seen_at": "2026-10-11T12:00:00Z",
                "created_at": "2026-10-01T10:00:00Z",
                "updated_at": "2026-10-11T12:00:00Z"
            }
        """.trimIndent()

        val table = json.decodeFromString<DeviceTable>(payload)

        assertEquals("device-uuid-111", table.id)
        assertEquals("family-uuid-222", table.familyId)
        assertEquals("Kid Phone", table.deviceName)
        assertEquals("ANDROID", table.platform)
        assertEquals("CHILD_PHONE", table.deviceType)
        assertEquals("2026-10-11T12:00:00Z", table.lastSeenAt)
    }

    /**
     * Test 4 — Confirm Device domain model never declares or exposes device_secret_hash.
     */
    @Test
    fun testDeviceDomainModel_neverExposesSecretHash() {
        val declaredFields = Device::class.java.declaredFields.map { it.name }

        assertFalse(
            "Domain Device model must not contain device_secret_hash",
            declaredFields.contains("device_secret_hash")
        )
        assertFalse(
            "Domain Device model must not contain deviceSecret",
            declaredFields.contains("deviceSecret")
        )
    }

    /**
     * Test 5 — Confirm fetchDevices() in unconfigured environment returns Result.Success.
     */
    @Test
    fun testFetchDevices_unconfiguredEnvironment_returnsSuccess() = runBlocking {
        val repo = SupabaseDeviceRepository(
            isConfiguredProvider = { false }
        )

        val result = repo.fetchDevices()

        assertTrue(result is Result.Success)
        assertNotNull((result as Result.Success).data)
    }
}
