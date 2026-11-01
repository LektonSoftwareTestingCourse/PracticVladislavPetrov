# А6. Состояния карты и операции

**Не хватало:** диаграммы состояний и различия между решением по покупке и резервированием.
**Добавлено:** статусы карты, результат авторизации и откат резерва. Переходы здесь являются действиями бизнес-логики, а не сетевыми связями.

```mermaid
stateDiagram-v2
    state "Карта" as Card {
        [*] --> ACTIVE: создание обычной карты
        ACTIVE --> INACTIVE: PATCH status
        INACTIVE --> ACTIVE: PATCH status
        ACTIVE --> BLOCKED: PATCH status
        BLOCKED --> ACTIVE: PATCH status
        ACTIVE --> EXPIRED: PATCH status
        EXPIRED --> ACTIVE: PATCH status
        ACTIVE --> DELETED: DELETE
        INACTIVE --> DELETED: DELETE
        BLOCKED --> DELETED: DELETE
        EXPIRED --> DELETED: DELETE
        DELETED --> [*]: скрыта из API
    }
    state "Решение по покупке" as Decision {
        [*] --> APPROVED: проверки и резервирование успешны
        [*] --> DECLINED: карта, срок, баланс, лимиты или ошибка сервиса
        APPROVED --> DECLINED: ошибка публикации, ответ клиенту 96
    }
    state "Резервирование" as Reservation {
        [*] --> RESERVED: reserve
        RESERVED --> ROLLED_BACK: rollback, баланс восстановлен
        ROLLED_BACK --> ROLLED_BACK: повторный rollback отклонён
    }
```

Показаны основные переходы карты. `patchCard` не задаёт отдельной матрицы разрешённых переходов между неудалёнными статусами, поэтому допускает и прямые переходы вроде `BLOCKED` в `INACTIVE`. Генератор может сразу создать `INACTIVE` или `BLOCKED`.

Прошедший срок вызывает отказ `54`, но автоматически не меняет сохранённый `Card.status`. Статус `EXPIRED` на схеме устанавливается явно. В enum транзакции есть только `APPROVED` и `DECLINED`. `ROLLED_BACK` является состоянием резерва. Стрелка решения `APPROVED` в `DECLINED` описывает изменение ответа клиенту, а не обновление записи Logger.
