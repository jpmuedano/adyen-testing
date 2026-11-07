package com.adyen.testing.server

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject

/** Raw response from Adyen. We forward the JSON untouched: Drop-in parses it, not us. */
data class AdyenResponse(val status: HttpStatusCode, val body: String)

/**
 * Thin Checkout API client. Plain HTTP on purpose (instead of the Adyen Java library) so every
 * field we send to Adyen is visible in our code.
 */
class AdyenCheckoutClient(private val http: HttpClient, private val config: AdyenConfig) {

    suspend fun paymentMethods(request: JsonObject): AdyenResponse = post("/paymentMethods", request)

    private suspend fun post(path: String, body: JsonObject): AdyenResponse {
        val response = http.post(config.checkoutBaseUrl + path) {
            header("X-API-Key", config.apiKey)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return AdyenResponse(response.status, response.bodyAsText())
    }
}
