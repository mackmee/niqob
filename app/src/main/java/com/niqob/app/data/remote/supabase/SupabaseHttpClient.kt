package com.niqob.app.data.remote.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

data class HttpResponse(
    val statusCode: Int,
    val body: String?,
    val isSuccessful: Boolean = statusCode in 200..299,
    val errorMessage: String? = null
)

interface SupabaseHttpClient {
    suspend fun get(endpoint: String, authToken: String? = null): HttpResponse
    suspend fun post(endpoint: String, body: String, authToken: String? = null): HttpResponse
    suspend fun patch(endpoint: String, body: String, authToken: String? = null): HttpResponse
    suspend fun delete(endpoint: String, authToken: String? = null): HttpResponse
}

class DefaultSupabaseHttpClient : SupabaseHttpClient {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }

    override suspend fun get(endpoint: String, authToken: String?): HttpResponse =
        executeRequest("GET", endpoint, body = null, authToken = authToken)

    override suspend fun post(endpoint: String, body: String, authToken: String?): HttpResponse =
        executeRequest("POST", endpoint, body = body, authToken = authToken)

    override suspend fun patch(endpoint: String, body: String, authToken: String?): HttpResponse =
        executeRequest("PATCH", endpoint, body = body, authToken = authToken)

    override suspend fun delete(endpoint: String, authToken: String?): HttpResponse =
        executeRequest("DELETE", endpoint, body = null, authToken = authToken)

    private suspend fun executeRequest(
        method: String,
        endpoint: String,
        body: String?,
        authToken: String?
    ): HttpResponse = withContext(Dispatchers.IO) {
        val baseUrl = SupabaseConfig.url
        val apiKey = SupabaseConfig.anonKey

        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return@withContext HttpResponse(
                statusCode = 0,
                body = null,
                isSuccessful = false,
                errorMessage = "Supabase configuration is missing. Please check your configuration."
            )
        }

        val fullUrl = if (endpoint.startsWith("http")) endpoint else "$baseUrl$endpoint"

        var connection: HttpURLConnection? = null
        try {
            val url = URL(fullUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Authorization", "Bearer ${authToken ?: apiKey}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                doInput = true

                if (body != null && (method == "POST" || method == "PATCH" || method == "PUT")) {
                    doOutput = true
                    OutputStreamWriter(outputStream, "UTF-8").use { writer ->
                        writer.write(body)
                        writer.flush()
                    }
                }
            }

            val statusCode = connection.responseCode
            val inputStream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = inputStream?.let { stream ->
                BufferedReader(InputStreamReader(stream, "UTF-8")).use { reader ->
                    reader.readText()
                }
            }

            HttpResponse(
                statusCode = statusCode,
                body = responseBody,
                isSuccessful = statusCode in 200..299,
                errorMessage = if (statusCode !in 200..299) extractErrorMessage(statusCode, responseBody) else null
            )
        } catch (e: UnknownHostException) {
            HttpResponse(
                statusCode = 0,
                body = null,
                isSuccessful = false,
                errorMessage = "Connection unavailable. Please check your internet connection."
            )
        } catch (e: ConnectException) {
            HttpResponse(
                statusCode = 0,
                body = null,
                isSuccessful = false,
                errorMessage = "Connection unavailable. Backend is not reachable."
            )
        } catch (e: SocketTimeoutException) {
            HttpResponse(
                statusCode = 0,
                body = null,
                isSuccessful = false,
                errorMessage = "Connection timed out. Please try again."
            )
        } catch (e: Exception) {
            HttpResponse(
                statusCode = 0,
                body = null,
                isSuccessful = false,
                errorMessage = e.message ?: "An unexpected network error occurred."
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractErrorMessage(statusCode: Int, body: String?): String {
        if (body.isNullOrBlank()) {
            return when (statusCode) {
                400 -> "Invalid request. Please verify your input."
                401 -> "Invalid credentials or session expired."
                403 -> "Access denied."
                404 -> "Requested resource not found."
                500, 502, 503 -> "Backend server error. Please try again later."
                else -> "Request failed with error code $statusCode."
            }
        }

        // Try extracting "msg", "message", or "error_description" from response json
        val regexPatterns = listOf(
            """"msg"\s*:\s*"([^"]+)"""".toRegex(),
            """"message"\s*:\s*"([^"]+)"""".toRegex(),
            """"error_description"\s*:\s*"([^"]+)"""".toRegex()
        )
        for (pattern in regexPatterns) {
            val match = pattern.find(body)
            if (match != null) {
                val extracted = match.groupValues[1]
                return mapUserFriendlyMessage(extracted)
            }
        }

        return "Request failed ($statusCode)."
    }

    private fun mapUserFriendlyMessage(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("invalid login credentials") -> "Invalid email or password."
            lower.contains("user already registered") -> "An account with this email already exists."
            lower.contains("password should be at least") -> "Password must be at least 6 characters."
            lower.contains("invalid email") -> "Please enter a valid email address."
            lower.contains("rate limit") -> "Too many attempts. Please wait a moment."
            else -> raw
        }
    }
}
