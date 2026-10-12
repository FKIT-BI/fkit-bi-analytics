# SWAGGER-001 — публичный Swagger Analytics

## Объём

Нормативный `docs/contracts/analytics-api-draft.yaml` включён в jar Analytics и используется Swagger UI как статический контракт. Business endpoints защищены общим Bearer JWT. Контракт Analytics также содержит login и создание bootstrap-пользователя.

## Публичные URL

- Service domain UI: `https://api.bi.kitexp.ru/swagger-ui.html`
- Service domain contract: `https://api.bi.kitexp.ru/openapi/analytics-api-draft.yaml`
- Path-based UI: `https://bi.kitexp.ru/api/analytics/swagger-ui.html`
- Path-based contract: `https://bi.kitexp.ru/api/analytics/openapi/analytics-api-draft.yaml`
- Сгенерированный OpenAPI JSON `/v3/api-docs` также доступен публично; используется тот же service domain или префикс `/api/analytics/`.

OpenAPI server задан относительно URL контракта, поэтому `Try it out` остаётся на service domain или под path-prefix UI. Login публичен; остальные business operations используют Bearer JWT.

## Проверка

- `AnalyticsApplicationTest` и `AnalyticsOpenApiContractTest`: 19/19 — прошли проверки анонимных UI/config/raw YAML/JSON, forwarded prefix, успешного Bearer-запроса и 401 для анонимного business API.
- `AnalyticsOpenApiContractTest`: прошёл в полном `verify`; проверяет полный список операций, описания, примеры JSON body и byte-for-byte упаковку YAML.
- Полный `./mvnw verify`: один воспроизводимый сбой существующего `AuthFlowTest.loginLimiterReturnsRetryAfterAndDoesNotTrustUnconfiguredForwardingHeaders` (ожидался 429, получен 401); Swagger/security проверки прошли.
- `docker compose config -q` с временными фиктивными значениями обязательных переменных: прошёл.
- `git diff --check`: прошёл.
