# Тестовый стенд (Docker)

Полный стенд для серии «JVM Incident Investigation»: приложение с ручками инцидентов, PostgreSQL, Prometheus, Grafana (дашборд на каждую часть), cAdvisor, postgres-exporter и WireMock.

## Требования

- Docker + Docker Compose (v2+).

## Сборка образа приложения

```bash
cd infra
docker compose build
```

Сборка multi-stage: сначала Maven собирает jar, затем кладёт его в образ с полным JDK (jcmd/jstack) и async-profiler.

## Запуск стенда

```bash
cd infra
docker compose up -d
```

Остановить:

```bash
docker compose down          # контейнеры и сеть
docker compose down -v       # + удалить volume с данными PostgreSQL
```

## Доступные URL

| Сервис | URL | Что там |
|---|---|---|
| Приложение | http://localhost:8080 | REST-ручки инцидентов |
| Swagger UI | http://localhost:8080/swagger-ui.html | интерактивный вызов ручек |
| Метрики приложения | http://localhost:8080/actuator/prometheus | Micrometer → Prometheus |
| Grafana | http://localhost:3001 | дашборды (admin / admin, анонимный доступ включён) |
| Prometheus | http://localhost:9091 | запросы PromQL |
| cAdvisor | http://localhost:8081 | метрики контейнеров |
| postgres-exporter | http://localhost:9188 | статистика PostgreSQL |
| WireMock | http://localhost:8082 | mock внешнего сервиса (admin API `/__admin`) |
| PostgreSQL | localhost:5432 | postgres / postgres, БД `jvmincidents` |

## Дашборды Grafana (по одной на часть)

| Дашборд | Часть |
|---|---|
| Part 0 — Overview | общий обзор (CPU, heap, GC, threads) |
| Part 1 — CPU Profiling | process/system/container CPU |
| Part 2 — Allocation Profiling | allocation rate, GC frequency, buffers |
| Part 3 — GC Profiling | GC frequency, max pause, promoted, heap |
| Part 4 — Heap Dump & Memory Leaks | heap used/committed, live data |
| Part 5 — Native Memory | container RSS, heap, direct buffers |
| Part 6 — Threads, Locks & Deadlocks | live/peak threads, по состояниям |
| Part 7 — Blocking & I/O | waiting/blocked threads, server latency |
| Part 8 — Connection Pool Exhaustion | HikariCP active/pending/timeouts, PG connections |

## Как воспроизвести инцидент

Через Swagger UI (`:8080/swagger-ui.html`) или curl:

```bash
# список инцидентов и статусы
curl http://localhost:8080/incidents

# активировать проблему
curl -X POST http://localhost:8080/incidents/cpu/start

# статус
curl http://localhost:8080/incidents/cpu/status

# демо-фикс
curl -X POST http://localhost:8080/incidents/cpu/fix

# сброс
curl -X POST http://localhost:8080/incidents/cpu/stop
```

Типы: `cpu`, `allocation`, `gc`, `memory-leak`, `native-memory`, `deadlock`, `threads`, `blocking-io`, `db-pool`.

## Артефакты расследования

Всё, что снимается внутри контейнера, попадает в `../artifacts/` на хосте:

- `gc.log` — GC-логи (пишутся автоматически);
- heap dump, JFR-записи, NMT-отчёты — по командам ниже.

```bash
docker exec -it app bash

# heap dump
jcmd 1 GC.heap_dump /artifacts/heap.hprof

# JFR (60 секунд)
jcmd 1 JFR.start duration=60s filename=/artifacts/recording.jfr

# NMT
jcmd 1 VM.native_memory summary

# thread dump
jstack 1 > /artifacts/threads.txt

# async-profiler (CPU, 30 сек)
/opt/async-profiler/profiler.sh -d 30 -f /artifacts/cpu.html 1
```

> **Note:** async-profiler в контейнере требует `cap_add: [SYS_PTRACE, SYS_ADMIN]` (уже настроено в compose). Если perf-события на хосте заблокированы — используй JFR.

## Проверка стенда

```bash
cd infra
docker compose config          # валидация compose-файла
docker compose ps              # статусы контейнеров
curl http://localhost:8080/actuator/health
```
