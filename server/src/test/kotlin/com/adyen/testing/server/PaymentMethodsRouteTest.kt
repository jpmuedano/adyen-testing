package com.adyen.testing.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PaymentMethodsRouteTest {

    private val config = AdyenConfig(apiKey = "test_api_key", merchantAccount = "TestMerchantECOM", hmacKey = null)

    private class Captured(var url: String? = null, var apiKey: String? = null, var body: JsonObject? = null)

    private fun mockAdyen(captured: Captured, status: HttpStatusCode = HttpStatusCode.OK, responseBody: String) =
        HttpClient(MockEngine { request ->
            captured.url = request.url.toString()
            captured.apiKey = request.headers["X-API-Key"]
            captured.body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond(responseBody, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(ContentNegotiation) { json() } }

    @Test
    fun `adds server-owned fields and ignores app attempts to set them`() = testApplication {
        val captured = Captured()
        application { module(AdyenCheckoutClient(mockAdyen(captured, responseBody = """{"paymentMethods":[]}"""), config), config) }

        val response = client.post("/api/paymentMethods") {
            contentType(ContentType.Application.Json)
            // A tampered app tries to change the price and merchant account.
            setBody("""{"countryCode":"US","merchantAccount":"Evil","amount":{"currency":"EUR","value":1}}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"paymentMethods":[]}""", response.bodyAsText())
        assertEquals("https://checkout-test.adyen.com/v71/paymentMethods", captured.url)
        assertEquals("test_api_key", captured.apiKey)
        val body = captured.body!!
        assertEquals("TestMerchantECOM", body["merchantAccount"]!!.jsonPrimitive.content)
        assertEquals("Android", body["channel"]!!.jsonPrimitive.content)
        assertEquals("US", body["countryCode"]!!.jsonPrimitive.content)
        assertEquals(DemoCart.VALUE_MINOR_UNITS, body["amount"]!!.jsonObject["value"]!!.jsonPrimitive.long)
    }

    @Test
    fun `empty body falls back to defaults`() = testApplication {
        val captured = Captured()
        application { module(AdyenCheckoutClient(mockAdyen(captured, responseBody = "{}"), config), config) }

        client.post("/api/paymentMethods")

        assertEquals("NL", captured.body!!["countryCode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `forwards Adyen errors without leaking the API key`() = testApplication {
        val captured = Captured()
        val adyenError = """{"status":401,"errorCode":"000","message":"HTTP Status Response - Unauthorized","errorType":"security"}"""
        application { module(AdyenCheckoutClient(mockAdyen(captured, HttpStatusCode.Unauthorized, adyenError), config), config) }

        val response = client.post("/api/paymentMethods")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertFalse(response.bodyAsText().contains(config.apiKey))
    }

    @Test
    fun `config toString redacts secrets`() {
        assertFalse(AdyenConfig("secret_key", "M", "secret_hmac").toString().contains("secret"))
    }
}
