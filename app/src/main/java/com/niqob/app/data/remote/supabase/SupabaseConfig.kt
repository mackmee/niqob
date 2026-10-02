package com.niqob.app.data.remote.supabase

import com.niqob.app.BuildConfig

/**
 * Safe client-side Supabase configuration.
 *
 * Security guarantees:
 * - NEVER contains or accepts service-role secret keys.
 * - Only uses the public anonymous key (anon key) intended for mobile client apps.
 * - Values are injected at build time from local.properties via BuildConfig,
 *   or configured dynamically at runtime.
 */
object SupabaseConfig {

    private var customUrl: String? = null
    private var customAnonKey: String? = null

    val url: String
        get() = (customUrl ?: BuildConfig.SUPABASE_URL)
            .trim()
            .removeSuffix("/")
            .removeSuffix("/rest/v1")
            .removeSuffix("/")

    val anonKey: String
        get() = (customAnonKey ?: BuildConfig.SUPABASE_ANON_KEY).trim()

    val isConfigured: Boolean
        get() = url.isNotBlank() &&
                anonKey.isNotBlank() &&
                url.startsWith("http") &&
                !url.contains("your-project.supabase.co")

    fun setCustomConfig(url: String?, anonKey: String?) {
        customUrl = url
        customAnonKey = anonKey
    }

    fun reset() {
        customUrl = null
        customAnonKey = null
    }
}
