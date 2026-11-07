# Test 01 · `POST /api/paymentMethods` (NL, Dutch)

- **Date:** 2026-11-07
- **Phase:** 1 (merchant backend skeleton)
- **Environment:** Adyen TEST, merchant account `ElEncantoTattooECOM`, Checkout API `v71`
- **Server:** `LOG_ADYEN_TRAFFIC=true ./gradlew :server:run` (localhost:8080)
- **Result:** ✅ 200 OK

## Request (app → our server)

```bash
curl -s -X POST localhost:8080/api/paymentMethods \
  -H 'Content-Type: application/json' \
  -d '{"countryCode":"NL","shopperLocale":"nl-NL"}' | jq
```

## What our server sent to Adyen (from the server log)

The app sent only `countryCode` and `shopperLocale`. The server added the other fields:

```json
{
  "merchantAccount": "ElEncantoTattooECOM",
  "countryCode": "NL",
  "shopperLocale": "nl-NL",
  "channel": "Android",
  "amount": { "currency": "EUR", "value": 1000 }
}
```

Header `X-API-Key: ***` (masked in the log; the key never leaves the server).

## Response (passed through unchanged to the app)

```json
{
  "paymentMethods": [
    {
      "brands": ["amex", "cup", "diners", "discover", "mc", "visa"],
      "name": "Credit en Debit card",
      "type": "scheme"
    },
    {
      "name": "Achteraf betalen. Klarna.",
      "type": "klarna"
    },
    {
      "name": "Betaal in 3 delen",
      "type": "klarna_account"
    }
  ]
}
```

## What this shows

- **Credentials work end to end.** The API key authenticates, and the merchant account is valid.
- **`shopperLocale` translates the names.** `nl-NL` returns Dutch names ("Credit en Debit card",
  "Achteraf betalen"). Drop-in displays `name` as is, so the app doesn't need its own translations.
- **`countryCode` filters the list.** NL includes Klarna (`klarna`, `klarna_account`). The same call with
  `countryCode=US` returns `scheme` only.
- **`scheme` means cards.** `brands` lists the card networks enabled on the merchant account.
- **No `storedPaymentMethods`.** We don't send a `shopperReference` yet, so there are no saved cards.
- **The server doesn't read this JSON.** It passes it through to the app, and in Phase 2 Drop-in parses it.
