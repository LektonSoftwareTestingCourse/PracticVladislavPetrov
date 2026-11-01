# А3. Компоненты ядра

**Не хватало:** внутренних связей авторизации и управления картами.
**Добавлено:** контроллеры, бизнес-логика, HTTP-клиенты, репозитории и обработка outbox. Сплошные стрелки являются вызовами внутри процесса или синхронными HTTP/JDBC-связями. AMQP выделен пунктиром.

```mermaid
flowchart TB
    subgraph Authorization
        AC[AuthControllerImpl] --> AS[AuthServiceImpl]
        AS --> CC[CardManagementClientImpl]
        AS --> BC[BinLookupClient]
        AS --> LR[LimitUsageRepository]
    end
    subgraph CardManagement
        CT[CardController] --> CS[CardServiceImpl]
        CGC[CardGeneratorController] --> CG[CardGeneratorService]
        CG --> CS
        CS --> PG[PanGenerator]
        CS --> TR[TransactionRunnerImpl]
        CS --> CR[CardRepository]
        CS --> RR[ReservationRepository и ReservationRollbackRepository]
        CS --> BI[BinIssuerServiceImpl и BinIssuerRepository]
        CS --> EN[CardEventNotifierImpl]
        EN --> EP[OutboxEventProcessorImpl]
        EP --> OR[OutboxRepository]
        OP[OutboxProcessor: периодический запуск] --> OR
        OP --> EP
    end
    CC -->|HTTP: карта, reserve, rollback| CT
    BC -->|HTTP| BIN[bin-lookup]
    LR -->|JDBC| DB[(PostgreSQL)]
    CR -->|JPA / JDBC| DB
    RR -->|JPA / JDBC| DB
    BI -->|JPA / JDBC| DB
    OR -->|JPA / JDBC| DB
    EP -.->|AMQP: smp.card-events| MQ[RabbitMQ]
```

Репозитории на схеме являются интерфейсами, адаптеры связывают их с JPA.

Изолированная логика и моки клиентов подходят для юнит-тестов. SQL, транзакционное сохранение outbox и доставка RabbitMQ требуют интеграционных проверок. Обработчик запускается раз в 1000 мс по умолчанию, после трёх ошибок событие получает `FAILED`.
