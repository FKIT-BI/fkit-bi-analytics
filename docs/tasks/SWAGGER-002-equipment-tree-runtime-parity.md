# SWAGGER-002 — Реализация equipment-tree и проверка runtime-контракта

- Добавлен `GET /api/v1/enterprises/{enterpriseId}/equipment-tree`: предприятие, линии и назначенные машины, отдельный список свободных машин, фильтры `status` и `search`.
- Реализация использует ограниченные SQL-запросы без N+1; отсутствующее предприятие возвращает общий `404 NOT_FOUND`.
- Статический OpenAPI дополнен ответами `400` и `422`, совпадающими с проверками фильтров.
- `AnalyticsApplicationTest.publishedOpenApiAndRuntimeMappingsHaveTheSameHttpOperations` сравнивает HTTP method/path для каждой операции статического OpenAPI с runtime `/v3/api-docs`. Проверка обнаруживает как задокументированные, но отсутствующие маршруты, так и маршруты приложения, отсутствующие в Swagger.
- `EquipmentConfigurationApiTest.equipmentTreeGroupsAssignedAndUnassignedMachinesAndAppliesFilters` проверяет пустое предприятие через существующую тестовую схему, группировку, поиск, отсутствующий ресурс и неверные фильтры.
- Проверка: Docker Maven/JDK 21, `./mvnw -q -Dtest=EquipmentConfigurationApiTest,AnalyticsApplicationTest test` — успешно.
