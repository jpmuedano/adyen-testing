# 01 · Trust boundaries

> Phase 1 takeaway. Built: `server/` (Ktor) with `POST /api/paymentMethods` → Adyen `/v71/paymentMethods`.
> Read: `adyen-android/example-app/README.md`, `example-app/.../service/RequestUtils.kt`, the v5 SDK's
> `internal/data/api/*Service.kt`, and study guide p.4.

## The picture

```
 UNTRUSTED                         │ TRUSTED (merchant)              │ TRUSTED (Adyen)
                                   │                                 │
 ┌── shopper device ────────────┐  │  ┌── merchant server ────────┐  │  ┌── checkout-test.adyen.com ─┐
 │ merchant app                 │  │  │ ADYEN_API_KEY             │  │  │ /paymentMethods            │
 │   holds: CLIENT KEY (public) │──┼─▶│ MERCHANT_ACCOUNT          │──┼─▶│ /payments  /details        │
 │   sends: countryCode, locale │  │  │ adds: merchantAccount,    │  │  │ (API key: X-API-Key)       │
 │                              │  │  │   amount, channel         │  │  └────────────────────────────┘
 │ Adyen SDK ───────────────────┼──┼──┼───────────────────────────┼──┼─▶┌── checkoutshopper-test ────┐
 │   CSE, 3DS2, BIN lookup      │  │  └───────────────────────────┘  │  │ clientKeys, binLookup,     │
 └──────────────────────────────┘  │                                 │  │ 3DS2 fingerprint, analytics│
                                   │                                 │  │ (client key only)          │
```

Two different Adyen hosts, two different keys. The app never talks to `checkout-*.adyen.com`.

## Takeaways

- **The API key can make payments. The client key can't.** The API key (on the `ws_` API credential) calls
  `/payments`, `/payments/details`, and, depending on roles, modifications such as refunds. That is money movement, so it
  stays on the server (`server/local.properties`, git-ignored, redacted in `AdyenConfig.toString()`).
- **The client key is public by design.** Format `test_`/`live_` + 32 chars (`ValidationUtils.isClientKeyValid`,
  also checked against the configured `Environment`). In v5 it is only used against `checkoutshopper-*` endpoints:
  - `v1/clientKeys/{key}`: fetch the **CSE public key** (`PublicKeyService`)
  - `v2/bin/binLookup`: card brand detection (`BinLookupService`)
  - `v1/submitThreeDS2Fingerprint`: 3DS2 (`SubmitFingerprintService`)
  - `v1/nativeRedirect/redirectResult`, `services/PaymentInitiation/v1/status`, `v1/order/status`, `v3/analytics`
  None of these can initiate a charge on their own.
- **The Sessions twist.** `SessionService` calls `v1/sessions/{id}/payments` using the client key. That's still safe,
  because the session (amount, merchant account) was created server-side with the API key. The client key only drives
  a payment the server already set up. It's a good example of a trust boundary to point out in the interview.
- **The server owns the facts that matter.** `merchantAccount`, `amount`, `channel` are set by the server. The app request
  DTO doesn't even have those fields, and unknown keys are dropped. A test (`adds server-owned fields and ignores
  app attempts to set them`) sends `"amount":1` and checks that we still send 1000.
- **Why Adyen insists on a proxy** (example-app README): the API key must be managed on the server. The README's
  "connect directly" option is labelled *testing only, could leak your credentials*. Anything shipped in an APK can be
  extracted, so an API key in an app is a published API key.
- **What Adyen's sample adds server-side** (`RequestUtils.kt`): `merchantAccount`, `channel`, `reference`,
  `returnUrl`, `shopperIP`, `authenticationData` (3DS mode), `lineItems`, `shopperReference`. Note that their sample sends
  `channel = "android"`. Adyen's enum is `iOS | Android | Web`, so we send `"Android"`.
- **Adyen filters the list, not the app.** Verified against TEST (`ElEncantoTattooECOM`): `countryCode=NL` returns
  cards + `klarna` + `klarna_account`, `US` returns cards only. The server decides context (country, amount, channel),
  Adyen returns only what is valid for it, and Drop-in just renders the result. One gotcha: a *company* account
  (`ElEncantoTattoo`) gets `422 errorCode 901 Invalid Merchant Account`. Payments need a merchant account under it.
- **Logs are a trust boundary too.** `CallLogging` logs only the method, path and status, never bodies. A 401 from Adyen is passed
  through (it contains no secrets). A test checks that the API key never appears in a response body.

## Threat model (study guide p.4: name the attacker and the asset)

| Attacker | Asset they want | What stops them here |
|---|---|---|
| Someone who decompiles the APK | API key → charge or refund at will | It isn't there. The APK holds only the client key, which can't create payments. |
| Tampered or MITM'd app | Pay EUR 0.01 for a EUR 10 cart | Server-owned amount. The app sends only context (later, a cart id). |
| Network attacker | Card data in transit | TLS, plus CSE: the PAN is encrypted on-device with Adyen's public key (Phase 3), so even the **merchant server** never sees it. |
| Compromised merchant server | API key, webhook HMAC key | Out of scope for the prototype. In production: secret manager, key rotation, API-credential roles with least privilege, IP allow-list. |
| Insider / repo leak | Secrets in git | `local.properties` git-ignored; `local.properties.example` only. |
| Fake webhook sender | Mark an unpaid order paid | HMAC verification (Phase 4). |

**Gap to call out honestly:** there is no auth between our app and our server. Anyone can call `/api/paymentMethods`.
That's fine here because it only reveals which methods we accept. `/api/payments` in a real shop would sit behind shopper
auth plus rate limiting.

## Interview sound bite

> "There are two keys and two hosts. The client key is public and scoped. It can fetch the encryption key, do BIN
> lookups and run 3DS, but it can't charge anyone. Everything that moves money goes through the merchant's server
> with the API key, and the server, not the app, decides the amount."
