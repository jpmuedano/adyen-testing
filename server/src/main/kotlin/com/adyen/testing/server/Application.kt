package com.adyen.testing.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun main() {
    val config = AdyenConfig.load()
    val http = HttpClient(CIO) {
        install(ClientContentNegotiation) { json(json) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
        if (config.logAdyenTraffic) {
            // Learning aid. Never enable in production: from Phase 3 on, bodies carry encrypted card
            // data, shopper details and 3DS payloads.
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.ALL
                sanitizeHeader { it.equals("X-API-Key", ignoreCase = true) }
            }
        }
    }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    println("Starting merchant server on :$port with $config")
    // 0.0.0.0 so the emulator can reach us at http://10.0.2.2:$port.
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(AdyenCheckoutClient(http, config), config)
    }.start(wait = true)
}

fun Application.module(adyen: AdyenCheckoutClient, config: AdyenConfig) {
    install(ContentNegotiation) { json(json) }
    install(CallLogging) // Method + path + status only; no bodies, so no payment data in logs.
    install(StatusPages) {
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Malformed request")) }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Upstream call failed"))
        }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        paymentMethodsRoute(adyen, config)
    }
}
