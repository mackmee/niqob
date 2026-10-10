package com.niqob.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.niqob.app.core.common.Result
import com.niqob.app.data.local.ChildDeviceCredentialStore
import com.niqob.app.data.local.SharedPreferencesChildDeviceCredentialStore
import com.niqob.app.data.remote.supabase.NiqobSupabaseClient
import com.niqob.app.data.remote.supabase.dto.CreatePairingRpcResponse
import com.niqob.app.data.remote.supabase.dto.PairChildRpcResponse
import com.niqob.app.data.remote.supabase.dto.PairingSessionTable
import com.niqob.app.domain.model.PairingSession
import com.niqob.app.domain.model.PairingStatus
import com.niqob.app.domain.model.UserMode
import com.niqob.app.domain.repository.PairingRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class SupabasePairingRepository(
    private var sharedPreferences: SharedPreferences? = null,
    private var credentialStore: ChildDeviceCredentialStore? = null
) : PairingRepository {

    companion object {
        const val PREFS_NAME = "niqob_child_device_prefs"
        private const val KEY_SESSION_ID = "sessionId"
        private const val KEY_CHILD_DEVICE_ID = "childDeviceId"
        private const val KEY_FAMILY_ID = "familyId"
        private const val KEY_STATUS = "status"
        private const val KEY_CONNECTED_AT = "connectedAt"
        private const val KEY_PAIRING_CODE = "pairingCode"

        @Volatile
        private var instance: SupabasePairingRepository? = null

        fun getInstance(context: Context? = null): SupabasePairingRepository {
            val existing = instance
            if (existing != null) {
                if (context != null) {
                    if (existing.sharedPreferences == null) existing.initPreferences(context)
                    if (existing.credentialStore == null) existing.initCredentialStore(context)
                }
                return existing
            }
            return synchronized(this) {
                instance ?: SupabasePairingRepository().also { repo ->
                    if (context != null) {
                        repo.initPreferences(context)
                        repo.initCredentialStore(context)
                    }
                    instance = repo
                }
            }
        }

        fun resetInstanceForTesting() {
            instance = null
        }
    }

    private val localDeviceId: String = "parent_dev_${UUID.randomUUID().toString().take(8)}"
    private val _pairingSession = MutableStateFlow<PairingSession?>(restoreSession())
    private val _currentRole = MutableStateFlow<UserMode>(UserMode.UNSELECTED)

    fun initPreferences(context: Context) {
        if (sharedPreferences == null) {
            sharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (_pairingSession.value == null) {
                _pairingSession.value = restoreSession()
            }
        }
        if (credentialStore == null) {
            credentialStore = SharedPreferencesChildDeviceCredentialStore.getInstance(context)
        }
    }

    fun initCredentialStore(context: Context) {
        if (credentialStore == null) {
            credentialStore = SharedPreferencesChildDeviceCredentialStore.getInstance(context)
        }
    }

    fun setCredentialStore(store: ChildDeviceCredentialStore) {
        this.credentialStore = store
    }

    private fun getOrCreateCredentialStore(): ChildDeviceCredentialStore {
        return credentialStore ?: SharedPreferencesChildDeviceCredentialStore.getInstance().also {
            credentialStore = it
        }
    }

    private fun persistSession(session: PairingSession) {
        sharedPreferences?.edit()?.apply {
            putString(KEY_SESSION_ID, session.pairingId)
            putString(KEY_CHILD_DEVICE_ID, session.childDeviceId)
            putString(KEY_FAMILY_ID, session.familyId)
            putString(KEY_STATUS, session.status.name)
            putLong(KEY_CONNECTED_AT, session.connectedAt ?: System.currentTimeMillis())
            putString(KEY_PAIRING_CODE, session.pairingCode)
            apply()
        }
    }

    private fun clearPersistedSession() {
        sharedPreferences?.edit()?.apply {
            remove(KEY_SESSION_ID)
            remove(KEY_CHILD_DEVICE_ID)
            remove(KEY_FAMILY_ID)
            remove(KEY_STATUS)
            remove(KEY_CONNECTED_AT)
            remove(KEY_PAIRING_CODE)
            apply()
        }
    }

    private fun restoreSession(): PairingSession? {
        val prefs = sharedPreferences ?: return null
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        val childDeviceId = prefs.getString(KEY_CHILD_DEVICE_ID, null) ?: return null
        val familyId = prefs.getString(KEY_FAMILY_ID, null)
        val statusStr = prefs.getString(KEY_STATUS, null) ?: return null
        val connectedAt = prefs.getLong(KEY_CONNECTED_AT, 0L)
        val pairingCode = prefs.getString(KEY_PAIRING_CODE, "") ?: ""

        val status = try {
            PairingStatus.valueOf(statusStr)
        } catch (_: Exception) {
            PairingStatus.PENDING
        }

        if (status != PairingStatus.CONNECTED) {
            return null
        }

        return PairingSession(
            pairingId = sessionId,
            parentDeviceId = "parent_cloud",
            childDeviceId = childDeviceId,
            pairingCode = pairingCode,
            createdAt = connectedAt,
            expiresAt = connectedAt + 10 * 60 * 1000L,
            status = PairingStatus.CONNECTED,
            familyId = familyId,
            connectedAt = connectedAt
        )
    }

    override suspend fun createPairingCode(): Result<PairingSession> {
        if (!NiqobSupabaseClient.isConfigured) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        return try {
            val client = NiqobSupabaseClient.getClient()
            val rpcResponse = client.postgrest.rpc(
                function = "create_pairing_session",
                parameters = buildJsonObject {
                    put("p_parent_device_id", localDeviceId)
                }
            ).decodeAs<CreatePairingRpcResponse>()

            if (!rpcResponse.success || rpcResponse.sessionId == null || rpcResponse.pairingCode == null) {
                return Result.Error(Exception(rpcResponse.message ?: "Failed to generate pairing code on the server."))
            }

            val expiresAtMillis = parseIsoTimestamp(rpcResponse.expiresAt)
                ?: (System.currentTimeMillis() + 10 * 60 * 1000L)

            val session = PairingSession(
                pairingId = rpcResponse.sessionId,
                parentDeviceId = rpcResponse.parentDeviceId ?: localDeviceId,
                childDeviceId = null,
                pairingCode = rpcResponse.pairingCode,
                createdAt = System.currentTimeMillis(),
                expiresAt = expiresAtMillis,
                status = PairingStatus.PENDING,
                familyId = rpcResponse.familyId
            )

            _pairingSession.value = session
            Result.Success(session)
        } catch (e: Exception) {
            Result.Error(Exception(mapPairingError(e)))
        }
    }

    override suspend fun connectChild(pairingCode: String): Result<PairingSession> {
        val trimmedCode = pairingCode.trim()
        if (trimmedCode.length != 6 || !trimmedCode.all { it.isDigit() }) {
            return Result.Error(IllegalArgumentException("Pairing code must be a 6-digit number."))
        }

        if (!NiqobSupabaseClient.isConfigured) {
            return Result.Error(
                IllegalStateException("Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties.")
            )
        }

        return try {
            val client = NiqobSupabaseClient.getClient()
            val rpcResponse = client.postgrest.rpc(
                function = "pair_child_device",
                parameters = buildJsonObject {
                    put("p_pairing_code", trimmedCode)
                    put("p_device_name", "Child Android Device")
                    put("p_platform", "ANDROID")
                    put("p_device_type", "CHILD_PHONE")
                }
            ).decodeAs<PairChildRpcResponse>()

            if (!rpcResponse.success) {
                val userFriendlyMessage = when (rpcResponse.errorCode) {
                    "INVALID_CODE" -> "Invalid pairing code. Please verify the code shown on the parent device."
                    "EXPIRED" -> "This pairing code has expired. Please generate a new code on the parent device."
                    "ALREADY_USED" -> "This pairing code has already been used."
                    "REVOKED" -> "This pairing code has been revoked by the parent."
                    else -> rpcResponse.message ?: "Pairing validation failed."
                }
                return Result.Error(Exception(userFriendlyMessage))
            }

            val deviceId = rpcResponse.deviceId
            val deviceSecret = rpcResponse.deviceSecret

            // Security requirement: Both device_id and device_secret MUST be present in successful pairing
            if (deviceId.isNullOrBlank() || deviceSecret.isNullOrBlank()) {
                return Result.Error(
                    Exception("Pairing failed: Device credentials incomplete in server response.")
                )
            }

            // Security requirement: Persist credentials to secure store before reporting success
            val store = getOrCreateCredentialStore()
            val savedSuccessfully = try {
                store.save(deviceId, deviceSecret)
            } catch (_: Exception) {
                false
            }

            if (!savedSuccessfully) {
                return Result.Error(
                    Exception("Pairing failed: Could not securely persist device credentials.")
                )
            }

            val connectedAtMillis = parseIsoTimestamp(rpcResponse.connectedAt) ?: System.currentTimeMillis()

            val session = PairingSession(
                pairingId = rpcResponse.sessionId ?: UUID.randomUUID().toString(),
                parentDeviceId = "parent_cloud",
                childDeviceId = deviceId,
                pairingCode = trimmedCode,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 10 * 60 * 1000L,
                status = PairingStatus.CONNECTED,
                familyId = rpcResponse.familyId,
                connectedAt = connectedAtMillis
            )

            _pairingSession.value = session
            try {
                persistSession(session)
            } catch (e: Exception) {
                store.clear()
                _pairingSession.value = null
                return Result.Error(
                    Exception("Pairing failed: Could not persist session.")
                )
            }
            Result.Success(session)
        } catch (e: Exception) {
            Result.Error(Exception(mapPairingError(e)))
        }
    }

    override fun getPairingSession(): Flow<PairingSession?> {
        return _pairingSession.asStateFlow()
    }

    override suspend fun revokePairing(): Result<Unit> {
        val current = _pairingSession.value
        if (current != null && NiqobSupabaseClient.isConfigured) {
            try {
                val client = NiqobSupabaseClient.getClient()
                client.postgrest.rpc(
                    function = "revoke_pairing_session",
                    parameters = buildJsonObject {
                        put("p_session_id", current.pairingId)
                    }
                )
            } catch (_: Exception) {
                // Best-effort remote revocation
            }
        }

        clearPersistedSession()
        credentialStore?.clear()

        if (current != null) {
            _pairingSession.value = current.copy(
                status = PairingStatus.REVOKED,
                revokedAt = System.currentTimeMillis()
            )
        }
        return Result.Success(Unit)
    }

    override fun getChildDeviceId(): String? {
        return credentialStore?.getDeviceId() ?: _pairingSession.value?.childDeviceId
    }

    override fun getChildDeviceSecret(): String? {
        return credentialStore?.getDeviceSecret()
    }

    fun getDeviceId(): String? = getChildDeviceId()
    fun getDeviceSecret(): String? = getChildDeviceSecret()

    override fun getCurrentRole(): Flow<UserMode> {
        return _currentRole.asStateFlow()
    }

    override suspend fun setCurrentRole(role: UserMode) {
        _currentRole.value = role
    }

    override suspend fun syncPairingSession(): Result<PairingSession?> {
        if (!NiqobSupabaseClient.isConfigured) {
            return Result.Success(_pairingSession.value)
        }

        val currentUser = try {
            NiqobSupabaseClient.getClient().auth.currentUserOrNull()
        } catch (_: Exception) { null }

        if (currentUser == null) {
            // Unauthenticated callers (child device mode) rely on local persistence
            return Result.Success(_pairingSession.value)
        }

        return try {
            val client = NiqobSupabaseClient.getClient()
            val sessions = client.postgrest["pairing_sessions"]
                .select()
                .decodeList<PairingSessionTable>()
                .sortedByDescending { it.createdAt }

            if (sessions.isEmpty()) {
                Result.Success(_pairingSession.value)
            } else {
                val dto = sessions.first()
                val parsedStatus = try {
                    PairingStatus.valueOf(dto.status.uppercase())
                } catch (_: Exception) {
                    PairingStatus.PENDING
                }

                val session = PairingSession(
                    pairingId = dto.id,
                    parentDeviceId = dto.parentDeviceId,
                    childDeviceId = dto.childDeviceId,
                    pairingCode = dto.pairingCode,
                    createdAt = parseIsoTimestamp(dto.createdAt) ?: System.currentTimeMillis(),
                    expiresAt = parseIsoTimestamp(dto.expiresAt) ?: (System.currentTimeMillis() + 10 * 60 * 1000L),
                    status = parsedStatus,
                    familyId = dto.familyId,
                    connectedAt = parseIsoTimestamp(dto.connectedAt),
                    revokedAt = parseIsoTimestamp(dto.revokedAt)
                )

                _pairingSession.value = session
                Result.Success(session)
            }
        } catch (e: Exception) {
            Result.Error(Exception(mapPairingError(e)))
        }
    }

    private fun mapPairingError(e: Throwable): String {
        val msg = e.message?.lowercase() ?: ""
        return when {
            msg.contains("network") || msg.contains("unable to resolve host") || msg.contains("connect") ->
                "Connection unavailable. Please check your internet connection and try again."
            msg.contains("timeout") ->
                "Request timed out. Please verify your connection."
            else ->
                e.message ?: "An unexpected error occurred during pairing."
        }
    }

    private fun parseIsoTimestamp(isoString: String?): Long? {
        if (isoString.isNullOrBlank()) return null
        return try {
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
            )
            for (format in formats) {
                try {
                    val sdf = SimpleDateFormat(format, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val date = sdf.parse(isoString)
                    if (date != null) return date.time
                } catch (_: Exception) { }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
