# Практика 4. Юнит-тесты

| Сервис | Проверок | Что проверяется |
|---|---:|---|
| Gateway | 45 | Валидация запроса, лимит запросов, circuit breaker |
| Switch | 49 | BIN, нормализация, авторизация, публикация и откат |
| Authorization | 29 | Статусы и срок карты, баланс, лимиты, ошибки и откат |
| Card Management | 33 | Создание и изменение карты, пагинация, резерв, откат, outbox |
| Итого | 156 | Все проверки прошли |

Покрытие ветвей Switch: 57 из 72, или 79,17%.

## Запуск

```bash
mvn -B -ntp -f services/pom.xml -pl gateway,switch,authorization,card-management -am clean verify
```

```bash
mvn -B -ntp -f services/pom.xml -Pswitch clean verify
```

## Файлы и отчёты

- Тесты находятся в `services/{service}/src/test/java/`.
- HTML-отчёт покрытия: `services/{service}/target/site/jacoco/index.html`.
- XML-отчёт покрытия: `services/{service}/target/site/jacoco/jacoco.xml`.
- Результаты JUnit: `services/{service}/target/surefire-reports/`.
- CI запускает тесты и сохраняет четыре отчёта с именами `jacoco-report-{service}`.
