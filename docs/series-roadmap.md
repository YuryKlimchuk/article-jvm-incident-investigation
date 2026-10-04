# JVM Incident Investigation — структура серии

Практическая серия статей для Medium о профилировании и расследовании инцидентов в JVM/Spring-приложениях. Каждая статья разбирает один класс производственных инцидентов: от симптома к первопричине, с воспроизводимым демо-сценарием.

## Концепция серии

- Один класс инцидентов — одна статья.
- Каждая статья ведёт читателя по одному и тому же циклу расследования: симптом → доказательства → гипотеза → инструмент → расследование → первопричина → исправление → проверка.
- К каждой части прилагается демо-сценарий: REST-ручка в демо-приложении, которая «включает» инцидент.
- Обсервабилити-стек (Micrometer → Prometheus → Grafana) и демо-приложение запускаются через Docker; все файлы — в репозитории.

## Сквозной workflow расследования

```
Incident → Symptoms → Measurements/Evidence → Hypothesis →
Choose tool → Investigation → Root Cause → Fix → Verification
```

Каждая часть серии — одна итерация этого цикла, применённая к своему классу инцидентов.

## Части серии

### Part 0 — The Investigation Toolkit

- **Тема:** инструментарий расследования и матрица принятия решений. Не обучает инструментам вглубь.
- **Симптомы:** обзорный охват всех симптомов серии.
- **Инструменты:** вводит весь набор (JFR, async-profiler, jcmd, jstack, GC-логи, NMT, JMC, MAT, Micrometer, Prometheus, Grafana, HikariCP, PostgreSQL) и матрицу «симптом → вопрос → доказательства → инструмент → ожидаемый результат».
- **Демо-сценарий:** обзор демо-приложения и способов активации инцидентов (без отдельного инцидента).
- **Связь с серией:** фундамент для Part 1–8.

### Part 1 — CPU Profiling

- **Тема:** диагностика высокого CPU.
- **Симптомы:** high CPU.
- **Инструменты:** async-profiler (cpu), JFR (hot methods), JMC.
- **Демо-сценарий:** `POST /incidents/cpu/start` — busy loop / тяжёлые вычисления.
- **Связь с серией:** первое применение workflow из Part 0 к реальному симптому.

### Part 2 — Allocation Profiling / Allocation Churn

- **Тема:** темп аллокаций и churn (короткоживущие объекты).
- **Симптомы:** high allocation rate.
- **Инструменты:** async-profiler (alloc), JFR (allocation events).
- **Демо-сценарий:** `POST /incidents/allocation/start`.
- **Связь с серией:** подводит к Part 3 (churn → давление на GC).

### Part 3 — GC Profiling

- **Тема:** диагностика сборщика мусора.
- **Симптомы:** frequent GC, long GC pauses.
- **Инструменты:** GC-логи, JFR (GC events), jcmd.
- **Демо-сценарий:** `POST /incidents/gc/start`.
- **Связь с серией:** опирается на Part 2 (откуда берётся мусор); развивается в Part 4 (куча растёт).

### Part 4 — Heap Dump & Memory Leaks

- **Тема:** утечки памяти и анализ heap dump.
- **Симптомы:** growing heap, suspected memory leak.
- **Инструменты:** heap dump, MAT, JFR.
- **Демо-сценарий:** `POST /incidents/memory-leak/start`.
- **Связь с серией:** развивает Part 3 (куча растёт → ищем, что удерживает); расширяется в Part 5 (куча в норме → native).

### Part 5 — Native Memory

- **Тема:** native-память вне Java-кучи.
- **Симптомы:** high RSS при нормальной куче.
- **Инструменты:** NMT (jcmd VM.native_memory), jcmd.
- **Демо-сценарий:** `POST /incidents/native-memory/start`.
- **Связь с серией:** расширяет Part 4 (heap в норме → native).

### Part 6 — Threads, Locks & Deadlocks

- **Тема:** потоки, блокировки и взаимные блокировки.
- **Симптомы:** too many threads, blocked threads, deadlock.
- **Инструменты:** jstack, JFR (lock events).
- **Демо-сценарий:** `POST /incidents/deadlock/start`, `POST /incidents/threads/start`.
- **Связь с серией:** отдельное измерение — от CPU/памяти к конкурентности.

### Part 7 — Blocking & I/O

- **Тема:** блокирующий I/O и медленные внешние вызовы.
- **Симптомы:** slow external HTTP calls, блокировки на I/O.
- **Инструменты:** JFR (socket I/O), thread dumps, Micrometer.
- **Демо-сценарий:** `POST /incidents/blocking-io/start`.
- **Связь с серией:** подводит к Part 8 (I/O → пулы соединений).

### Part 8 — Connection Pool Exhaustion

- **Тема:** исчерпание пула соединений и медленный доступ к БД.
- **Симптомы:** connection pool exhaustion, slow database requests.
- **Инструменты:** HikariCP metrics, PostgreSQL statistics, Prometheus/Grafana.
- **Демо-сценарий:** `POST /incidents/db-pool/start`.
- **Связь с серией:** кульминация — связывает application-метрики и JVM-уровень.

## Распределение инструментов по частям

| Инструмент | Вводится впервые | Глубокий разбор |
|---|---|---|
| JFR | Part 0 | Part 1 (CPU), Part 2, 3, 6, 7 |
| async-profiler | Part 0 | Part 1 (CPU), Part 2 (alloc) |
| jcmd | Part 0 | Part 3, Part 5 (NMT) |
| jstack | Part 0 | Part 6 |
| GC-логи | Part 0 | Part 3 |
| NMT | Part 0 | Part 5 |
| JMC | Part 0 | Part 1 (анализ JFR) |
| MAT | Part 0 | Part 4 |
| Micrometer | Part 0 | Part 7, 8 |
| Prometheus / Grafana | Part 0 | Part 8 |
| HikariCP metrics | Part 0 | Part 8 |
| PostgreSQL statistics | Part 0 | Part 8 |

## Матрица «симптом → часть серии»

| Симптом | Часть |
|---|---|
| High CPU | Part 1 |
| High allocation rate / churn | Part 2 |
| Frequent GC | Part 3 |
| Long GC pauses | Part 3 |
| Growing heap | Part 3, 4 |
| Suspected memory leak | Part 4 |
| High RSS, нормальная куча | Part 5 |
| Too many threads | Part 6 |
| Blocked threads | Part 6 |
| Deadlock | Part 6 |
| Slow external HTTP calls | Part 7 |
| Slow database requests | Part 8 |
| Connection pool exhaustion | Part 8 |

## Демо-ручки активации инцидентов

| Часть | REST-ручка |
|---|---|
| Part 0 | обзор демо-приложения (без отдельной ручки) |
| Part 1 | `POST /incidents/cpu/start` |
| Part 2 | `POST /incidents/allocation/start` |
| Part 3 | `POST /incidents/gc/start` |
| Part 4 | `POST /incidents/memory-leak/start` |
| Part 5 | `POST /incidents/native-memory/start` |
| Part 6 | `POST /incidents/deadlock/start`, `POST /incidents/threads/start` |
| Part 7 | `POST /incidents/blocking-io/start` |
| Part 8 | `POST /incidents/db-pool/start` |
