# Study plan: Adyen v5 Drop-in sample + merchant backend

> **Purpose:** prepare for the Adyen Staff Engineer (Mobile SDK) system design interview.
> The code is a quick prototype, built to learn from. Each phase builds something small, then
> answers study questions using Adyen's real source, then turns that into a talking point.
>
> Companion docs: [android-integration.md](android-integration.md) (v5 Advanced flow reference) ·
> [study-guide.pdf](study-guide.pdf) (interview prep).

---

## 0. Ground rules

| Rule | Why |
|---|---|
| **Learning over polish.** No Hilt, no multi-module app, no production hardening unless it teaches something. | We have about two weeks. Every hour spent on scaffolding is an hour not spent on design. |
| **Every phase ends with a written takeaway** in `docs/notes/` (5–10 bullets plus one diagram). | The interview is spoken. Writing things down is how they stick. |
| **Read the source before coding each piece.** | The goal is to explain *why Adyen built it this way*, not just how to call it. |
| **Compare with v6 at each step.** | "How does v2 ship without breaking v1 merchants?" is a likely deep dive. v6 is a real answer to it. |
| **Secrets never enter the app.** API key and HMAC key live only in `server/` local config, which is git-ignored. | This is the security point we will make out loud in the interview, so the prototype should follow it. |

### Reference checkouts

| Path | Branch | Use |
|---|---|---|
| `~/Repos/adyen-android` | `v5` (5.22.0+) | What we integrate against. Read Drop-in internals here. |
| `~/Repos/adyen-android/.claude/worktrees/v6` | `main` (6.0.0-alpha) | Where Adyen is heading. Use it for comparisons. |
| `~/Repos/adyen-testing` | `main` | **Our work.** |

**SDK version:** `com.adyen.checkout:drop-in-compose:5.22.0`. This is the latest v5 tag and matches the
reference checkout, so the source we read is the code we run. (`android-integration.md` was written
for 5.20.0; the API is the same.)

---

## 1. Target system

This is the whiteboard diagram from the study guide (p.6), built for real:

```
┌────────────── Android app (merchant app) ───────────────┐         ┌──────── server/ (Ktor) ────────┐        ┌──── Adyen TEST ────┐
│  Compose UI  ──▶  CheckoutViewModel                      │  HTTP   │  POST /api/paymentMethods      │  API   │  /paymentMethods   │
│                     │  state machine (sealed UiState)    │ ──────▶ │  POST /api/payments            │  key   │  /payments         │
│                     ▼                                    │         │  POST /api/payments/details    │ ─────▶ │  /payments/details │
│  Adyen Drop-in (v5) ──▶ AppDropInService ──▶ MerchantApi │         │  GET  /api/orders/{ref}        │        │                    │
│   (CSE, 3DS2, redirect, UI)   (client key only)         │         │  POST /webhooks/adyen  (HMAC)  │ ◀───── │  webhook AUTHORISATION
└──────────────────────────────────────────────────────────┘         │  OrderStore (in-memory)        │        └────────────────────┘
                                                                     └────────────────────────────────┘
```

**Rule we will show the interviewer:** the app shows `resultCode` right away, but the
order's final status comes from the **webhook**. The app reads it back through `GET /api/orders/{ref}`.

### What we deliberately won't build (say this in the interview)
- Sessions flow. We picked Advanced to see every API hop. Sessions is covered in notes only.
- Real persistence, auth between app and server, and deployment.
- Gift cards, partial payments, stored-payment-method removal, address lookup.
- iOS. Note the counterpart concepts only.
- Production observability. We do one small telemetry exercise instead (Phase 6).

---

## 2. Phases

Each phase is about **½–1 day**. Phases 1–4 build the full flow; 5–8 are deep dives.

### Phase 1: Merchant backend skeleton
**Build**
- `server/` Gradle module (Ktor + kotlinx.serialization + Adyen Java API library, or plain Ktor client calls).
- `POST /api/paymentMethods` proxies to Adyen with `channel=Android`.
- Config in `server/local.properties`, git-ignored: `ADYEN_API_KEY`, `MERCHANT_ACCOUNT`, `HMAC_KEY`.
- Test with `curl` before any app code exists.

**Study (read)**
- `adyen-android/example-app/README.md`: why Adyen insists on a proxy server.
- `example-app/.../service/RequestUtils.kt`: which fields Adyen's own sample adds on the server side.

**Interview takeaway** → `notes/01-trust-boundaries.md`
- Client key versus API key: what each key can do, and why the client key is safe to make public.
- Threat model: name the attacker and the asset (study guide p.4).

---

### Phase 2: App shows Drop-in (`/paymentMethods` only)
**Build**
- Add `drop-in-compose:5.22.0`, `INTERNET` permission, and a Retrofit/Ktor client `MerchantApi`.
- `CheckoutConfiguration(environment, clientKey) { dropIn {}; card { setHolderNameRequired(true) } }`.
- `rememberLauncherForDropInResult` + `DropIn.startPayment(...)`, triggered from a button, not from composition.
- A stub `AppDropInService` that returns `DropInServiceResult.Error` for now.
- Emulator → host server via `http://10.0.2.2:8080` (cleartext allowed for debug only).

**Study (read in v5)**
- `drop-in/.../DropIn.kt`: the public entry points. Count the public API surface.
- `drop-in/.../internal/ui/DropInActivity.kt` + `DropInViewModel.kt`: Drop-in is its own Activity.
- `drop-in/.../internal/provider/PaymentMethodAvailabilityProvider.kt`: how unavailable methods are hidden (graceful degradation).
- `*/api/*.api` files + `.github/workflows/validate_public_api.yml`: **binary compatibility validator in CI**.

**Compare with v6** → `worktrees/v6/core/.../components/Checkout.kt`, `CheckoutController.kt`

**Interview takeaway** → `notes/02-public-api.md`
- Write the "5–10 lines a merchant types" for v5 and for v6, side by side. That is study guide step 3 (public API first).
- How Adyen enforces a stable surface: `internal` packages, `.api` dumps, a CI gate.

---

### Phase 3: Make a payment (`/payments` + `/payments/details`)
**Build**
- Server: `POST /api/payments` builds the request from `paymentComponentJson` plus server-owned fields
  (`merchantAccount`, `amount`, `reference`, `returnUrl`), with an **`Idempotency-Key` header**.
  `POST /api/payments/details` forwards `actionComponentJson` unchanged.
- **The server owns the amount.** The app sends only an order/cart id.
- App: `AppDropInService.onSubmit` / `onAdditionalDetails` → `MerchantApi` → map the response to
  `Finished` / `Action` / `Error`.
- Test cards: no 3DS, 3DS2 frictionless, 3DS2 challenge, refused.

**Study (read in v5)**
- `example-app/.../service/ExampleAdvancedDropInService.kt`: Adyen's own response → result mapping.
- `drop-in/.../DropInService.kt` + `internal/service/BaseDropInService.kt`: why is the merchant hook an
  Android **bound Service**? (It decouples merchant networking from Drop-in's Activity lifecycle.)
- `cse/` (`CardEncrypter`, `JSONWebEncryptor`): client-side encryption. Find where the raw PAN stops existing.

**Compare with v6** → `AdvancedCheckoutCallbacks.kt`, `SubmitResult.kt`: suspend callbacks replace the Service.

**Interview takeaway** → `notes/03-payment-correctness.md`
- Idempotency: what happens if `/payments` times out and the shopper taps Pay again?
- "A timeout is not a failure": how we would resolve an unknown outcome.
- PCI scope: draw where card data is plaintext (only inside the SDK) and where it is encrypted.

---

### Phase 4: Webhooks are the source of truth
**Build**
- Server: `POST /webhooks/adyen` verifies the **HMAC signature**, answers `[accepted]`, handles duplicates
  idempotently (keyed on `pspReference` + `eventCode`), and updates `OrderStore`.
- `GET /api/orders/{reference}` lets the app poll for the final status after Drop-in finishes.
- Expose the local server with a tunnel (cloudflared/ngrok) and register it in the Customer Area (test).
- App result screen: show "Authorised (pending confirmation)", then the confirmed status.

**Study**
- Adyen docs: webhook retries and ordering guarantees (or the lack of them).

**Interview takeaway** → `notes/04-async-outcomes.md`
- Sync `resultCode` versus async webhook: who trusts which, and why.
- At-least-once delivery → idempotent consumer. Out-of-order events.

---

### Phase 5: Lifecycle and state survival (deep dive)
**Do**
- Turn on "Don't keep activities". Start a 3DS2 challenge or a redirect, background the app, kill the process, return.
- Rotate during the payment sheet and during the action.
- Write down what survives and what doesn't, in Drop-in and in *our* app state.

**Study (read in v5)**
- `drop-in/.../internal/ui/DropInSavedStateHandleContainer.kt`: how Drop-in persists its state.
- `redirect/` module: `RedirectComponent.getReturnUrl` and how the return intent finds its way back.

**Compare with v6**: how does a Compose-hosted `CheckoutController` survive? (Look at the `coroutineScope` it takes and at `example-app/.../ui/v6/V6ViewModel.kt`.)

**Interview takeaway** → `notes/05-lifecycle.md`
- The explicit payment state machine (study guide p.4): idle → collecting → submitting → awaiting action → result.
  Draw it as Drop-in implements it.

---

### Phase 6: Observability and analytics (deep dive)
**Do**
- Read how the SDK emits funnel events. Add one log line per state transition in our app, with no PII.
- Grep our logs for anything that looks like card data or tokens. There should be none.

**Study (read in v5)**
- `drop-in/.../internal/analytics/DropInEvents.kt`, `components-core/.../internal/analytics/`,
  `card/.../internal/analytics/`: the event names, the fields sent, and how `AnalyticsLevel` opts out.

**Compare with v6**: the recent `main` commits about shared Drop-in analytics (`git log --oneline -20`).

**Interview takeaway** → `notes/06-observability.md`
- Event taxonomy, tagging by SDK version, and the "long tail of old versions" dashboard.
- Why remote config and kill switches matter when you can't hot-fix shipped SDK code.

---

### Phase 7: SDK architecture and delivery (reading only)
**Study**
- `settings.gradle` module list → draw the dependency graph: `core` → `checkout-core` /
  `components-core` → `action-core`, `3ds2`, `cse`, `redirect` → payment-method modules → `drop-in`.
  (Hint: `dependency-graph/` and `.github/workflows/generate_dependency_graph.yml`.)
- `build-logic/convention/`: convention plugins (ktlint, detekt, Sonar, the library plugin with API validation).
- `.github/workflows/`: PR checks, `validate_public_api`, `check_dependency_changes`,
  `update_verification_metadata` (supply chain), release, and Maven Central publishing.
- `README.md` lifecycle table: support windows for v3/v4/v5.

**Interview takeaway** → `notes/07-sdk-platform.md`
- Modularity for binary size. Minimal transitive dependencies (dependency verification metadata).
- The release train and "you cannot roll back an SDK in the field".
- How a new payment method is added without a major version (the module plus the provider registry).

---

### Phase 8: v5 → v6 synthesis
**Study**
- `worktrees/v6/MIGRATION.md` (Sessions, Advanced, Card, Google Pay sections).
- `worktrees/v6/docs/v6/*.md`. Note that `drop-in/` exists in v6 (with `old/` and `internal/` packages)
  even though the docs say Drop-in is out of scope for the alpha.

**Optional build (½ day, only if time allows):** a second screen in our app using a v6 snapshot
card component against the **same** server. This shows the backend contract didn't change.

**Interview takeaway** → `notes/08-v5-to-v6.md`
- What changed and why: Service → callbacks, Views → Compose-first, one `CheckoutController`.
- How Adyen manages the break: a new major version, a migration guide, parallel support windows.
- What we would do differently. Have an opinion, with trade-offs.

---

## 3. Final deliverable: mock interview pack

After Phase 8, build `docs/notes/00-checkout-sdk-design.md`: a 45-minute answer to
**study guide prompt 1 ("Design a mobile checkout SDK")**, built from the six-step frame. Every
claim should be backed by something we built or read:

| Frame step | Evidence from this project |
|---|---|
| Clarify / requirements | Drop-in vs Components, Sessions vs Advanced (notes 02, 08) |
| Public API first | v5 and v6 merchant snippets (note 02) |
| Architecture | Module graph and system diagram (notes 01, 07) |
| Deep dives | Security/CSE (03), correctness/idempotency (03, 04), lifecycle (05), observability (06) |
| Evolution | v5 → v6 (08), API validation in CI (07) |

Then run it as a timed mock. Record it, and note where you hesitated.

---

## 4. Repository layout (target)

```
adyen-testing/
├── app/                      Android sample (Compose + Drop-in v5)
│   └── src/main/java/com/adyen/testing/
│       ├── checkout/         CheckoutViewModel, UiState, AppDropInService
│       ├── data/             MerchantApi, DTOs
│       └── ui/               screens
├── server/                   Ktor merchant backend (JVM)
│   └── local.properties      secrets (git-ignored)
├── docs/
│   ├── plan.md               this file
│   ├── android-integration.md
│   ├── study-guide.pdf
│   └── notes/                one takeaway per phase
└── settings.gradle.kts       includes :app and :server
```

## 5. Open items / risks
- **AGP 9.5 + Kotlin 2.2.10** in our project versus Adyen 5.22.0. Check that Phase 2 compiles before investing more.
- **Webhooks need a public URL.** We need a tunnel tool and a Customer Area webhook config. If that's
  blocked, simulate webhooks with signed `curl` requests. We still learn HMAC and idempotency that way.
- **Test account access:** API key, client key (with the allowed origin/app), merchant account, and HMAC key.

## 6. Progress

- [x] Phase 1: Backend skeleton (`server/`, notes/01-trust-boundaries.md)
- [ ] Phase 2: Drop-in shows payment methods
- [ ] Phase 3: Payments + 3DS2
- [ ] Phase 4: Webhooks
- [ ] Phase 5: Lifecycle
- [ ] Phase 6: Observability
- [ ] Phase 7: SDK architecture (reading)
- [ ] Phase 8: v5 → v6 synthesis
- [ ] Mock interview pack
