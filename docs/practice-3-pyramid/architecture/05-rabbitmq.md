# А5. RabbitMQ

**Не хватало:** наглядной топологии двух потоков, DLX/DLQ и отделения повторов outbox от повторной доставки потребителю.
**Добавлено:** имена exchange, очередей, ключей и потребителей. Пунктир показывает передачу сообщений по AMQP. Вызов отправки из outbox и сохранение его состояния выполняются внутри сервиса.

```mermaid
flowchart TB
    S[Switch: LoggerClient] -.->|transaction.log| TE[smp.transactions: topic]
    TE -.->|transaction.log| TQ[transaction-log]
    TQ -.-> TL[TransactionLogListener]
    TL --> TS[TransactionService: БД и WebSocket]
    TQ -.->|dead-letter при отклонении без повторной постановки| TD[smp.transactions.dlx: direct]
    TD -.->|transaction-log| TDL[transaction-log-dlq: TTL 60 с]

    O[(Outbox в PostgreSQL)] --> OP[OutboxProcessor: каждые 1000 мс]
    OP --> EP[OutboxEventProcessorImpl]
    EP -.->|card. + имя класса события| CE[smp.card-events: topic]
    CE -.->|card.*| CQ[card-notifications]
    CQ -.-> CL[CardEventListener]
    CL --> NR[БД: уведомления]
    CQ -.->|dead-letter при отклонении без повторной постановки| CD[smp.card-events.dlx: direct]
    CD -.->|card-notifications| CDL[card-notifications-dlq: TTL 60 с]
    EP -->|Ошибка: увеличить retryCount, после 3 ошибок FAILED| O
    EP -->|Нет исключения: PROCESSED| O
```

- Для publisher confirm включён режим `correlated`.
- У основных очередей задан `x-max-delivery=3`. Повторы обработки и переход в DLQ проверяются в N-12.
- После трёх ошибок отправки событие outbox получает `FAILED`. Это отдельный механизм от повторов обработки в Logger и Notification.
- TTL у DLQ составляет 60 секунд.
