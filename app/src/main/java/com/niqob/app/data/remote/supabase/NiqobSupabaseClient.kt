package com.niqob.app.data.remote.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

/**
 * Central singleton managing the official Supabase mobile client instance.
 *
 * Security guarantees:
 * - Only uses public mobile anonymous key (anon key).
 * - Never accepts or contains service-role secret keys.
 * - Credentials must be supplied via local.properties (SUPABASE_URL and SUPABASE_ANON_KEY).
 * - If credentials are not supplied, accessing the client produces a clear, descriptive
 *   configuration error rather than silent or fake fallback.
 */
object NiqobSupabaseClient {

    @Volatile
    private var clientInstance: SupabaseClient? = null

    val isConfigured: Boolean
        get() = SupabaseConfig.isConfigured

    fun getClient(): SupabaseClient {
        if (!isConfigured) {
            throw IllegalStateException(
                "Supabase credentials not configured. Please supply SUPABASE_URL and SUPABASE_ANON_KEY in local.properties."
            )
        }
        return clientInstance ?: synchronized(this) {
            clientInstance ?: createSupabaseClient(
                supabaseUrl = SupabaseConfig.url,
                supabaseKey = SupabaseConfig.anonKey
            ) {
                install(Auth)
                install(Postgrest)
                install(Realtime)
            }.also { clientInstance = it }
        }
    }

    fun resetForTesting() {
        clientInstance = null
    }
}
