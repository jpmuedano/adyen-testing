package com.adyen.testing.server

import java.io.File
import java.util.Properties

/**
 * Server-only secrets. These never leave this process: the app only ever gets the client key,
 * which is configured on the app side and is safe to ship.
 */
data class AdyenConfig(
    val apiKey: String,
    val merchantAccount: String,
    val hmacKey: String?,
    val checkoutBaseUrl: String = TEST_CHECKOUT_BASE_URL,
    /** Dev-only: log server→Adyen request/response bodies (API key masked). Off by default. */
    val logAdyenTraffic: Boolean = false,
) {
    // Keep secrets out of logs and stack traces.
    override fun toString() =
        "AdyenConfig(merchantAccount=$merchantAccount, checkoutBaseUrl=$checkoutBaseUrl, logAdyenTraffic=$logAdyenTraffic, apiKey=***, hmacKey=${if (hmacKey == null) "unset" else "***"})"

    companion object {
        const val TEST_CHECKOUT_BASE_URL = "https://checkout-test.adyen.com/v71"

        /** Environment variables win over `local.properties`, so CI or a shell can override. */
        fun load(file: File = File("local.properties"), env: Map<String, String> = System.getenv()): AdyenConfig {
            val props = Properties().apply { if (file.exists()) file.inputStream().use(::load) }
            fun value(key: String) = env[key]?.takeIf { it.isNotBlank() } ?: props.getProperty(key)?.takeIf { it.isNotBlank() }
            fun required(key: String) = value(key)
                ?: error("Missing $key. Copy server/local.properties.example to server/local.properties and fill it in.")

            return AdyenConfig(
                apiKey = required("ADYEN_API_KEY"),
                merchantAccount = required("MERCHANT_ACCOUNT"),
                hmacKey = value("HMAC_KEY"), // Needed from Phase 4 (webhooks) on.
                checkoutBaseUrl = value("ADYEN_CHECKOUT_BASE_URL") ?: TEST_CHECKOUT_BASE_URL,
                logAdyenTraffic = value("LOG_ADYEN_TRAFFIC").toBoolean(),
            )
        }
    }
}
