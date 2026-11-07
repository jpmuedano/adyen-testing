package com.adyen.testing.server

import io.ktor.http.ContentType
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * What the app is allowed to tell us. Note what is *not* here: merchantAccount, amount, channel.
 * Those are server-owned, so a tampered app can't change them.
 */
@Serializable
data class PaymentMethodsRequest(
    val countryCode: String = "NL",
    val shopperLocale: String = "en-US",
)

/** Server-owned price. Phase 3 replaces this with a cart lookup keyed by an id the app sends. */
object DemoCart {
    const val CURRENCY = "EUR"
    const val VALUE_MINOR_UNITS = 1000L // EUR 10.00
}

// Unknown keys are dropped, so an app that sends "amount" or "merchantAccount" is simply ignored.
private val appJson = Json { ignoreUnknownKeys = true }

fun Route.paymentMethodsRoute(adyen: AdyenCheckoutClient, config: AdyenConfig) {
    post("/api/paymentMethods") {
        // Lenient on purpose: an empty body (plain `curl -X POST`) means "use defaults".
        val raw = call.receiveText()
        val appRequest = if (raw.isBlank()) {
            PaymentMethodsRequest()
        } else {
            try {
                appJson.decodeFromString<PaymentMethodsRequest>(raw)
            } catch (e: SerializationException) {
                throw BadRequestException("Malformed body", e)
            }
        }

        val adyenRequest = buildJsonObject {
            put("merchantAccount", config.merchantAccount)
            put("countryCode", appRequest.countryCode)
            put("shopperLocale", appRequest.shopperLocale)
            // Adyen's enum is "iOS" | "Android" | "Web". It filters out methods the platform can't render.
            put("channel", "Android")
            putJsonObject("amount") {
                put("currency", DemoCart.CURRENCY)
                put("value", DemoCart.VALUE_MINOR_UNITS)
            }
        }

        val response = adyen.paymentMethods(adyenRequest)
        // Pass Adyen's status and body through: on success the app hands this JSON straight to Drop-in;
        // on failure Adyen's error body (errorCode/message) contains no secrets and helps debugging.
        call.respondText(response.body, ContentType.Application.Json, response.status)
    }
}
