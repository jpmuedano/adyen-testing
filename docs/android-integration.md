# Adyen Android Drop-in — Advanced flow integration (v5.20.0)

Local notes distilled from Adyen's
[Advanced flow integration guide](https://docs.adyen.com/online-payments/build-your-integration/advanced-flow?platform=Android&integration=Drop-in&version=5.20.0)
(Android · Drop-in · 5.20.0). Code samples are taken from that page; comments marked
**Note** are our own observations.

---

## 1. Overview

The Advanced flow uses three server-side Checkout API calls, with Drop-in handling the UI on the device:

```
 App (Drop-in)                    Your server                      Adyen
 ─────────────                    ───────────                      ─────
 request payment methods  ──────▶ POST /paymentMethods  ─────────▶
 DropIn.startPayment(...) ◀────── paymentMethods JSON   ◀─────────
 shopper taps Pay
 DropInService.onSubmit   ──────▶ POST /payments        ─────────▶
                          ◀────── resultCode [+ action] ◀─────────
 (if action) Drop-in runs redirect / 3DS2 / QR / sdk / voucher / await
 DropInService.onAdditionalDetails ─▶ POST /payments/details ────▶
                          ◀────── resultCode            ◀─────────
 DropInResult → show status
                                  webhook AUTHORISATION ◀───────── (final outcome)
```

Drop-in renders payment methods, collects card data securely (it never touches your
server), and handles 3D Secure 2 fingerprinting/challenge including the exchange with the
issuer's ACS.

**3D Secure 2 adds these requirements:**
- Configure Drop-in to collect the cardholder name.
- Send additional parameters in the `/payments` request.
- Submit authentication results when an `action` is returned.
- Handle the redirect if the payment is routed to the 3DS2 redirect flow.

---

## 2. Before you begin

From the Adyen Customer Area (test account):

| Credential | Used by | Notes |
|---|---|---|
| **API key** | Server | Never ship it in the app. |
| **Client key** | App | Passed to `CheckoutConfiguration`. |
| **Merchant account** | Server | Sent in every API request. |
| **Webhook endpoint** | Server | Receives the final payment outcome. |

> **Note:** The app must never call the Checkout API directly. A backend (or, for
> experimentation, a mock) is required.

---

## 3. Server: install an API library (optional)

Adyen provides server libraries (Java, Node, PHP, Python, .NET, Go, Ruby…) with generated
models and a built-in HTTP client. Java example:

```xml
<dependency>
  <groupId>com.adyen</groupId>
  <artifactId>adyen-java-api-library</artifactId>
  <version>LATEST_VERSION</version>
</dependency>
```

```java
Client client = new Client("ADYEN_API_KEY", Environment.TEST);
```

Example servers: <https://github.com/adyen-examples/adyen-java-spring-online-payments>

---

## 4. Server: get available payment methods

`POST /paymentMethods` when the shopper is ready to pay. Include all optional parameters for the most accurate list.

| Parameter | Required | Description |
|---|---|---|
| `merchantAccount` | ✅ | Your merchant account name. |
| `amount` | | `currency` + `value` in minor units (e.g. `1000` = 10.00 EUR). |
| `channel` | | `Android` — returns only Android-capable methods. |
| `countryCode` | | ISO-3166-1 alpha-2 (exception: `QZ` for Kosovo). |
| `shopperLocale` | | Defaults to `en-US`. Must match the locale set in the Drop-in configuration. |

Response:

```json
{
  "paymentMethods": [
    { "details": [...], "name": "Cards", "type": "scheme" },
    { "details": [...], "name": "SEPA Direct Debit", "type": "sepadirectdebit" }
  ]
}
```

Pass the JSON to the app, which deserializes it:

```kotlin
val paymentMethodsApiResponse = PaymentMethodsApiResponse.SERIALIZER.deserialize(paymentMethodsResponseJSON)
```

---

## 5. App: set up Drop-in

### 5.1 Add the dependency

Available on Maven Central. Latest version: <https://github.com/Adyen/adyen-android>.

| Variant | Dependency |
|---|---|
| With Jetpack Compose (default) | `com.adyen.checkout:drop-in-compose:5.20.0` |
| Without Jetpack Compose | `com.adyen.checkout:drop-in:5.20.0` |

> **Note:** This project is Compose-based, so `drop-in-compose` is the natural choice.
> In `gradle/libs.versions.toml`:
> ```toml
> [versions]
> adyen = "5.20.0"
> [libraries]
> adyen-dropin-compose = { group = "com.adyen.checkout", name = "drop-in-compose", version.ref = "adyen" }
> ```

### 5.2 Create the configuration object

| Property | Required | Description |
|---|---|---|
| `environment` | ✅ | `Environment.TEST` for testing; a live environment in production (see §10). |
| `clientKey` | ✅ | Your client key. |
| `shopperLocale` | | Defaults to the device locale. |
| `amount` | Only to show the amount on the Pay button | `currency` + `value`. |

```kotlin
// Create the amount object.
val amount = Amount(
  currency = "EUR",
  value = 1000, // Value in minor units.
)

// Create a configuration object
val checkoutConfiguration = CheckoutConfiguration(
  environment = environment,
  clientKey = clientKey,
  shopperLocale = shopperLocale, // Optional
  amount = amount, // Optional: set this to show the amount on the Pay button.
) {
  // Optional: add Drop-in configuration.
  dropIn {
      setEnableRemovingStoredPaymentMethods(true)
  }

  // Optional: add or change default configuration for the card payment method.
  card {
      setHolderNameRequired(true)   // Needed for 3D Secure 2.
      setShopperReference("...")
  }

  // Optional: add or change default configuration for 3D Secure 2.
  adyen3DS2 {
      setThreeDSRequestorAppURL("...")
  }
}
```

Some payment methods require extra configuration; see their individual guides.

### 5.3 Implement a `DropInService`

The service is the bridge between Drop-in and your server. Each handler calls your backend
and replies with a `DropInServiceResult`, which Drop-in uses to finish, dismiss, or run an
additional action.

```kotlin
class YourDropInService : DropInService() {
    // The handler to make a /payments request.
    override fun onSubmit(state: PaymentComponentState<*>) {
        val paymentComponentJson = PaymentComponentData.SERIALIZER.serialize(state.data)
        // Your server makes a /payments request, including `paymentComponentJson`.

        // Create the `DropInServiceResult` based on the /payments response.
        // You must switch to a background thread before making an API request.
        // For example, `launch(Dispatchers.IO)` if using coroutines.

        // If the payment finished, handle the result.
        sendResult(DropInServiceResult.Finished("YOUR_RESULT"))

        // If additional action is needed, handle the action.
        val action = Action.SERIALIZER.deserialize(actionJSONObject)
        sendResult(DropInServiceResult.Action(action))
    }

    // Handler to make a /payments/details request to send additional payment details.
    override fun onAdditionalDetails(actionComponentData: ActionComponentData) {
        val actionComponentJson = ActionComponentData.SERIALIZER.serialize(actionComponentData)
        // Your server makes a /payments/details request, including `actionComponentJson`.

        // Create the `DropInServiceResult` based on the /payments/details response.
        sendResult(DropInServiceResult.Finished("YOUR_RESULT"))
    }
}
```

Possible results: `DropInServiceResult.Finished(result)`, `DropInServiceResult.Action(action)`,
`DropInServiceResult.Error(...)`.

Register the service in `AndroidManifest.xml`:

```xml
<service
    android:name=".YourDropInService"
    android:exported="false" />
```

### 5.4 Launch and show Drop-in

Implement `DropInCallback` to receive the final result:

```kotlin
override fun onDropInResult(dropInResult: DropInResult?) {
   when (dropInResult) {
      // The payment finishes with a result.
      is DropInResult.Finished -> handleResult(dropInResult.result)
      // The shopper dismisses Drop-in.
      is DropInResult.CancelledByUser -> { /* ... */ }
      // Drop-in encounters an error.
      is DropInResult.Error -> handleError(dropInResult.reason)
      // Drop-in encounters an unexpected state.
      null -> { /* ... */ }
   }
}
```

**With Jetpack Compose:**

```kotlin
import com.adyen.checkout.dropin.compose.startPayment
import com.adyen.checkout.dropin.compose.rememberLauncherForDropInResult

@Composable
private fun ComposableDropIn() {
    val dropInLauncher = rememberLauncherForDropInResult(dropInCallback)

    DropIn.startPayment(dropInLauncher, paymentMethodsApiResponse, checkoutConfiguration, YourDropInService::class.java)
}
```

**Without Jetpack Compose:**

```kotlin
// Declare this as a field in your Activity or Fragment.
private val dropInLauncher = DropIn.registerForDropInResult(this, dropInCallback)

val paymentMethodsApiResponse = PaymentMethodsApiResponse.SERIALIZER.deserialize(paymentMethodsResponseJSON)

DropIn.startPayment(
    context,
    dropInLauncher,
    paymentMethodsApiResponse,
    checkoutConfiguration,
    YourDropInService::class.java,
)
```

| `startPayment` parameter | Description |
|---|---|
| `context` (non-Compose only) | Your context. |
| `dropInLauncher` | The launcher you created/registered. |
| `paymentMethodsApiResponse` | The deserialized `/paymentMethods` response. |
| `checkoutConfiguration` | The configuration from §5.2. |
| `YourDropInService::class.java` | Your `DropInService` subclass. |

> **Note:** In Compose, avoid calling `startPayment` directly during composition (it would
> relaunch on recomposition) — trigger it from a click handler or a `LaunchedEffect`.

---

## 6. Server: make a payment

When the shopper taps Pay, `onSubmit` is called. Send `paymentComponentJson` to your server, which calls `POST /payments`:

| Parameter | Required | Description |
|---|---|---|
| `merchantAccount` | ✅ | Your merchant account name. |
| `amount` | ✅ | `currency` + `value` in minor units. |
| `reference` | ✅ | Your unique reference for this payment. |
| `paymentMethod` | ✅ | The complete `paymentComponentData.paymentMethod` object from the app. |
| `paymentMethod.sdkData` | ✅ | Collected by Drop-in (checkout attempt ID, etc.); needed for the Checkout analytics dashboard. |
| `returnUrl` | ✅ | Where the shopper returns after a redirect (max 1024 chars). Get it in the app via `RedirectComponent.getReturnUrl(context)`. |
| `applicationInfo` | | If you build an Adyen solution for multiple merchants. |

Additional parameters are required to: integrate certain payment methods, use risk
management (required risk fields), use **native 3D Secure 2**, or tokenize / make recurring payments.

> **Note:** The simplest approach is to forward the whole `paymentComponentJson`
> (`paymentMethod`, `browserInfo`, `storePaymentMethod`, …) and merge in the server-owned
> fields (`merchantAccount`, `amount`, `reference`, `returnUrl`).

Example response with an action (3DS2):

```json
{
  "resultCode": "IdentifyShopper",
  "action": {
    "token": "eyJkaXJl...",
    "paymentMethodType": "scheme",
    "paymentData": "Ab02b4c0...",
    "type": "threeDS2",
    "authorisationToken": "BQABAQ...",
    "subtype": "fingerprint"
  }
}
```

Next step:
- **No `action`** → the payment is complete; go to §9.
- **`action` present** → go to §7.

**Error handling:** if `/payments` fails, return `DropInServiceResult.Error` to the app.

---

## 7. App: handle the additional action

Pass the **full** `action` object from your server to the app, deserialize it, and return it to Drop-in:

```kotlin
val action = Action.SERIALIZER.deserialize(actionJSONObject)
sendResult(DropInServiceResult.Action(action))
```

> **Note:** The guide's text says `ActionResponse.SERIALIZER`, but its v5 code sample uses
> `Action.SERIALIZER` — use the latter.

Drop-in runs the action on the client. Then, for every type, it calls
`onAdditionalDetails` → serialize `actionComponentJson` → send to server → §8.

| Type | `action.type` | What Drop-in does |
|---|---|---|
| Redirect | `redirect` | Sends the shopper to another site/app; `onAdditionalDetails` fires on return. If the shopper never returns, rely on the webhook. |
| 3D Secure 2 | `threeDS2` | Runs frictionless or challenge flow. |
| QR code | `qrCode` | Shows the QR code; fires when the shopper completes payment. |
| SDK | `sdk` | Shows a payment-method-specific UI overlay. |
| Voucher | `voucher` | Shows the voucher (save to wallet / email). |
| Await | `await` | Shows instructions (e.g. confirm in banking app) and waits. |

Implement all action types so any payment method works.

> **Note:** For redirects to return to the app, the redirect return URL must be handled by
> the app. Check the Adyen Android v5 docs/README for the required intent-filter setup
> — the Advanced flow page doesn't cover it.

---

## 8. Server: send additional payment details

1. Pass the full `actionComponentJson` to your server.
2. `POST /payments/details` with the full data from `actionComponentJson`.
3. Return the response to the app. It may contain another `action` — handle it the same way (§7).

Successful:

```json
{ "pspReference": "NC6HT9CRT65ZGN82", "resultCode": "Authorised" }
```

Refused:

```json
{ "pspReference": "KHQC5N7G84BLNK43", "refusalReason": "Not enough balance", "resultCode": "Refused" }
```

---

## 9. Get the payment outcome

**Inform the shopper (app):** use `resultCode` (e.g. `Authorised`, `Refused`, `Pending`,
`Received`, `Cancelled`, `Error`) to show the current status. This synchronous result is
**not** final.

**Update your order management system (webhook server):** the final outcome arrives
asynchronously in a webhook with `eventCode: AUTHORISATION`:
- `success: true` → payment succeeded.
- `success: false` → failed; `reason` explains why.

---

## 10. Test and go live

**Testing**
- Use Adyen's test cards and test payment methods; test every method you plan to offer.
- The *Adyen Android test cards* app can copy/autofill card details on device.
- Check test payments in Customer Area → **Payments → Payment list**; use **API logs** to debug.

**Going live**
1. Apply for a live account.
2. Assess PCI DSS compliance (Self-Assessment Questionnaire-A).
3. Configure your live account.
4. Request payment methods in your live Customer Area.
5. Switch from test to live endpoints. **All requests for one payment session must use the
   same live region** — mixing regions between `/payments` and `/payments/details` can break 3DS2.
6. Set `environment` to match your live endpoint region:

| Endpoint region | `Environment` value |
|---|---|
| Europe (EU) | `EUROPE` |
| United States (US) | `UNITED_STATES` |
| Australia (AU) | `AUSTRALIA` |
| Northeast Asia (NEA) | `NEA` |
| India (IN) | `INDIA` |

---

## 11. Checklist for this project

- [ ] Add `com.adyen.checkout:drop-in-compose:5.20.0` to the version catalog and `app/build.gradle.kts`.
- [ ] Add `INTERNET` permission to the manifest.
- [ ] Provide the client key / environment via `BuildConfig` or `local.properties` (not committed).
- [ ] Choose a backend: a real server (holds the API key) or a mock for UI experiments.
- [ ] Implement a repository/API client for `/paymentMethods`, `/payments`, `/payments/details` through that backend.
- [ ] Implement `DropInService` (coroutines on `Dispatchers.IO`) and register it in the manifest.
- [ ] Build `CheckoutConfiguration` with `card { setHolderNameRequired(true) }` for 3DS2.
- [ ] Launch Drop-in from Compose via `rememberLauncherForDropInResult` + `DropIn.startPayment`.
- [ ] Handle the redirect return URL.
- [ ] Map `DropInResult` / `resultCode` to UI states.
- [ ] Set up the webhook handler for `AUTHORISATION` on the server.

## 12. Further reading

- Modify payments (capture, cancel, refund)
- Add payment methods
- Tokenization
- 3D Secure authentication (PSD2 SCA)
- Result codes · Go-live checklist · Upgrade to Adyen Android v5
