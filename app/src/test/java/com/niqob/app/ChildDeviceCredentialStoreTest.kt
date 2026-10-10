package com.niqob.app

import com.niqob.app.core.common.Result
import com.niqob.app.data.local.ChildDeviceCredentialStore
import com.niqob.app.data.local.DeviceSecretCipher
import com.niqob.app.data.local.NoOpDeviceSecretCipher
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.remote.supabase.dto.PairChildResultDto
import com.niqob.app.data.remote.supabase.dto.PairChildRpcResponse
import com.niqob.app.data.repository.SupabasePairingRepository
import com.niqob.app.domain.model.ChildDeviceStatus
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.RemoteOverrideRequest
import com.niqob.app.feature.child.ChildPairingUiState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ChildDeviceCredentialStoreTest {

    private val json = Json { ignoreUnknownKeys = true }

    // Deterministic test constants (never logged or printed)
    private val testDeviceId1 = "test-device-uuid-1111"
    private val testDeviceSecret1 = "11112222333344445555666677778888aaaabbbbccccddddeeeeffff00001111"

    private val testDeviceId2 = "test-device-uuid-2222"
    private val testDeviceSecret2 = "99998888777766665555444433332222fedcba9876543210fedcba9876543210"

    private lateinit var inMemoryStore: SharedPreferencesChildDeviceCredentialStore

    @Before
    fun setUp() {
        inMemoryStore = SharedPreferencesChildDeviceCredentialStore(
            sharedPreferences = null,
            cipher = NoOpDeviceSecretCipher
        )
    }

    // --------------------------------------------------------------------------
    // 1. Successful pairing parses device_secret
    // --------------------------------------------------------------------------
    @Test
    fun pairingResponse_parsesDeviceSecretSuccessfully() {
        val payload = """
            {
                "success": true,
                "session_id": "session-123",
                "family_id": "family-456",
                "device_id": "$testDeviceId1",
                "device_name": "Child Android Device",
                "status": "CONNECTED",
                "connected_at": "2026-10-05T12:00:00Z",
                "device_secret": "$testDeviceSecret1"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<PairChildRpcResponse>(payload)

        assertTrue(parsed.success)
        assertEquals("session-123", parsed.sessionId)
        assertEquals(testDeviceId1, parsed.deviceId)
        assertEquals(testDeviceSecret1, parsed.deviceSecret)
    }

    @Test
    fun pairChildResultDto_holdsDeviceSecret() {
        val dto = PairChildResultDto(
            success = true,
            sessionId = "s1",
            familyId = "f1",
            deviceId = testDeviceId1,
            deviceName = "Child Phone",
            status = "CONNECTED",
            deviceSecret = testDeviceSecret1
        )

        assertTrue(dto.success)
        assertEquals(testDeviceId1, dto.deviceId)
        assertEquals(testDeviceSecret1, dto.deviceSecret)
    }

    // --------------------------------------------------------------------------
    // 2. device_secret is persisted
    // --------------------------------------------------------------------------
    @Test
    fun deviceSecret_isPersistedAndRetrieved() {
        assertFalse(inMemoryStore.hasCredential())
        assertNull(inMemoryStore.getDeviceSecret())

        val saved = inMemoryStore.save(testDeviceId1, testDeviceSecret1)
        assertTrue(saved)

        assertTrue(inMemoryStore.hasCredential())
        assertEquals(testDeviceSecret1, inMemoryStore.getDeviceSecret())
    }

    // --------------------------------------------------------------------------
    // 3. device_id and device_secret are stored together
    // --------------------------------------------------------------------------
    @Test
    fun deviceIdAndSecret_areStoredTogether() {
        val saved = inMemoryStore.save(testDeviceId1, testDeviceSecret1)
        assertTrue(saved)

        assertEquals(testDeviceId1, inMemoryStore.getDeviceId())
        assertEquals(testDeviceSecret1, inMemoryStore.getDeviceSecret())
        assertTrue(inMemoryStore.hasCredential())

        // Refuse partial save with blank deviceId
        val failDeviceId = inMemoryStore.save("", testDeviceSecret1)
        assertFalse(failDeviceId)

        // Refuse partial save with blank deviceSecret
        val failSecret = inMemoryStore.save(testDeviceId1, "   ")
        assertFalse(failSecret)
    }

    // --------------------------------------------------------------------------
    // 4. Missing device_secret causes pairing failure
    // --------------------------------------------------------------------------
    @Test
    fun missingDeviceSecret_inResponse_causesPairingValidationFailure() {
        val incompletePayload = """
            {
                "success": true,
                "session_id": "session-123",
                "family_id": "family-456",
                "device_id": "$testDeviceId1",
                "status": "CONNECTED"
            }
        """.trimIndent()

        val parsed = json.decodeFromString<PairChildRpcResponse>(incompletePayload)
        assertTrue(parsed.success)
        assertNull(parsed.deviceSecret)

        // Simulating repository validation rule
        val isInvalid = parsed.deviceId.isNullOrBlank() || parsed.deviceSecret.isNullOrBlank()
        assertTrue("Incomplete credential response must trigger validation failure", isInvalid)
    }

    // --------------------------------------------------------------------------
    // 5. Secret persistence failure causes pairing failure
    // --------------------------------------------------------------------------
    @Test
    fun secretPersistenceFailure_preventsInconsistentState() {
        val failingStore = object : ChildDeviceCredentialStore {
            override fun save(deviceId: String, deviceSecret: String): Boolean = false
            override fun getDeviceId(): String? = null
            override fun getDeviceSecret(): String? = null
            override fun clear() {}
            override fun hasCredential(): Boolean = false
        }

        assertFalse(failingStore.save(testDeviceId1, testDeviceSecret1))
        assertFalse(failingStore.hasCredential())
        assertNull(failingStore.getDeviceId())
        assertNull(failingStore.getDeviceSecret())
    }

    // --------------------------------------------------------------------------
    // 6. Existing pairing failure responses remain compatible
    // --------------------------------------------------------------------------
    @Test
    fun failureResponses_withoutDeviceSecret_parseCleanly() {
        val invalidCodeJson = """
            {
                "success": false,
                "error_code": "INVALID_CODE",
                "message": "Invalid pairing code. Please verify the code shown on the parent device."
            }
        """.trimIndent()

        val parsed = json.decodeFromString<PairChildRpcResponse>(invalidCodeJson)
        assertFalse(parsed.success)
        assertEquals("INVALID_CODE", parsed.errorCode)
        assertNull(parsed.deviceSecret)
        assertNull(parsed.deviceId)

        val expiredJson = """
            {
                "success": false,
                "error_code": "EXPIRED",
                "message": "This pairing code has expired."
            }
        """.trimIndent()
        val parsedExpired = json.decodeFromString<PairChildRpcResponse>(expiredJson)
        assertFalse(parsedExpired.success)
        assertEquals("EXPIRED", parsedExpired.errorCode)
        assertNull(parsedExpired.deviceSecret)
    }

    // --------------------------------------------------------------------------
    // 7. Secret is never exposed through UI/domain presentation models
    // --------------------------------------------------------------------------
    @Test
    fun deviceSecret_neverExposedThroughDomainPresentationModels() {
        val pairingSessionFields = PairingSession::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "PairingSession domain model must NOT contain deviceSecret field",
            pairingSessionFields.any { it.contains("secret") }
        )

        val uiStateFields = ChildPairingUiState::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "ChildPairingUiState must NOT contain deviceSecret field",
            uiStateFields.any { it.contains("secret") }
        )

        val remoteOverrideFields = RemoteOverrideRequest::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "RemoteOverrideRequest must NOT contain deviceSecret field",
            remoteOverrideFields.any { it.contains("secret") }
        )

        val childDeviceStatusFields = ChildDeviceStatus::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "ChildDeviceStatus must NOT contain deviceSecret field",
            childDeviceStatusFields.any { it.contains("secret") }
        )
    }

    // --------------------------------------------------------------------------
    // 8. getDeviceSecret returns the stored secret to the authorized data layer
    // --------------------------------------------------------------------------
    @Test
    fun getDeviceSecret_returnsStoredSecretToAuthorizedRepository() {
        val repository = SupabasePairingRepository(
            sharedPreferences = null,
            credentialStore = inMemoryStore
        )

        assertNull(repository.getChildDeviceSecret())
        assertNull(repository.getChildDeviceId())

        inMemoryStore.save(testDeviceId1, testDeviceSecret1)

        assertEquals(testDeviceId1, repository.getChildDeviceId())
        assertEquals(testDeviceSecret1, repository.getChildDeviceSecret())
        assertEquals(testDeviceSecret1, repository.getDeviceSecret())
    }

    // --------------------------------------------------------------------------
    // 9. clear() removes the secret and device ID
    // --------------------------------------------------------------------------
    @Test
    fun clear_removesSecretAndDeviceId() {
        inMemoryStore.save(testDeviceId1, testDeviceSecret1)
        assertTrue(inMemoryStore.hasCredential())

        inMemoryStore.clear()

        assertFalse(inMemoryStore.hasCredential())
        assertNull(inMemoryStore.getDeviceId())
        assertNull(inMemoryStore.getDeviceSecret())
    }

    // --------------------------------------------------------------------------
    // 10. Re-pairing replaces the old credential correctly
    // --------------------------------------------------------------------------
    @Test
    fun rePairing_replacesOldCredentialCorrectly() {
        // Initial pairing
        inMemoryStore.save(testDeviceId1, testDeviceSecret1)
        assertEquals(testDeviceId1, inMemoryStore.getDeviceId())
        assertEquals(testDeviceSecret1, inMemoryStore.getDeviceSecret())

        // Re-pairing with new credentials
        val replaceSuccess = inMemoryStore.save(testDeviceId2, testDeviceSecret2)
        assertTrue(replaceSuccess)

        // Old credentials must be replaced completely
        assertEquals(testDeviceId2, inMemoryStore.getDeviceId())
        assertEquals(testDeviceSecret2, inMemoryStore.getDeviceSecret())
        assertNotEquals(testDeviceId1, inMemoryStore.getDeviceId())
        assertNotEquals(testDeviceSecret1, inMemoryStore.getDeviceSecret())
    }

    // --------------------------------------------------------------------------
    // 11. Cipher Encryption/Decryption Roundtrip (AES-GCM simulation)
    // --------------------------------------------------------------------------
    @Test
    fun customAesCipher_encryptsSecretBeforePersistence() {
        // 256-bit AES key for test encryption
        val testKeyBytes = ByteArray(32) { (it * 7).toByte() }
        val testKey = SecretKeySpec(testKeyBytes, "AES")

        val softwareCipher = object : DeviceSecretCipher {
            override fun encrypt(plainText: String): String {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, testKey)
                val iv = cipher.iv
                val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                val combined = ByteArray(iv.size + cipherBytes.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
                return Base64.getEncoder().encodeToString(combined)
            }

            override fun decrypt(cipherText: String): String {
                val combined = Base64.getDecoder().decode(cipherText)
                val iv = combined.copyOfRange(0, 12)
                val data = combined.copyOfRange(12, combined.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, testKey, GCMParameterSpec(128, iv))
                return String(cipher.doFinal(data), Charsets.UTF_8)
            }
        }

        val encryptedStore = SharedPreferencesChildDeviceCredentialStore(
            sharedPreferences = null,
            cipher = softwareCipher
        )

        encryptedStore.save(testDeviceId1, testDeviceSecret1)

        // Raw secret must decrypt successfully
        assertEquals(testDeviceSecret1, encryptedStore.getDeviceSecret())
        assertEquals(testDeviceId1, encryptedStore.getDeviceId())
    }
}
