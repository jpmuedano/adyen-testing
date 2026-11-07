# Merchant server (Ktor)

Proxies the app to the Adyen Checkout API. Secrets live only here.

```bash
cp server/local.properties.example server/local.properties   # fill in ADYEN_API_KEY, MERCHANT_ACCOUNT
./gradlew :server:run                                         # listens on 0.0.0.0:8080 (emulator: 10.0.2.2:8080)
./gradlew :server:test
```

```bash
curl -s localhost:8080/health
curl -s -X POST localhost:8080/api/paymentMethods \
  -H 'Content-Type: application/json' -d '{"countryCode":"NL","shopperLocale":"nl-NL"}' | jq '.paymentMethods[].type'
```

| Endpoint | Phase | Notes |
|---|---|---|
| `POST /api/paymentMethods` | 1 | Server adds `merchantAccount`, `amount`, `channel=Android` |
