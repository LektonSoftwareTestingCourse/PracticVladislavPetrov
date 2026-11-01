# А2. Сервисы и связи

**Не хватало:** разделения синхронных вызовов и очередей, пути outbox и сверки с Compose.
**Добавлено:** все 10 прикладных сервисов из Compose, PostgreSQL и RabbitMQ. `common` является библиотекой. Мониторинг включается отдельным профилем.

Сплошные стрелки показывают синхронные HTTP/JDBC-вызовы. Пунктир показывает AMQP и WebSocket, где ответ пользователю не означает завершения доставки.

```mermaid
flowchart TB
    T[terminal-simulator] -->|HTTP: покупка| G[gateway]
    T -->|HTTP: карты| C[card-management]
    M[merchant-acquirer] -->|HTTP: покупка и карты| G
    D[dashboard] -->|HTTP: API| G
    G -->|HTTP: транзакция| S[switch]
    G -->|HTTP: CRUD карт| C
    G -->|HTTP: поиск и статистика| L[transaction-logger]
    G -->|HTTP: запуск| T
    G -->|HTTP: запуск| M
    S -->|HTTP: authorize и rollback| A[authorization]
    S -->|HTTP: комиссия| M
    A -->|HTTP: карта и резервирование| C
    A -->|HTTP: BIN| B[bin-lookup]
    A -->|JDBC: limit_usage и RRN| P[(PostgreSQL)]
    C -->|JDBC: карты, резервы и outbox| P
    M -->|JDBC: мерчанты и комиссии| P
    L -->|JDBC: транзакции| P
    N[notification-service] -->|JDBC: уведомления| P
    S -.->|AMQP: smp.transactions / transaction.log| R[RabbitMQ]
    R -.->|AMQP: transaction-log| L
    C -.->|AMQP: outbox / smp.card-events / card.*| R
    R -.->|AMQP: card-notifications| N
    L -.->|WebSocket через nginx| D
```

Публикация в очередь отделена от сохранения потребителем в БД. Подтверждение брокера не означает завершение обработки сообщения.
