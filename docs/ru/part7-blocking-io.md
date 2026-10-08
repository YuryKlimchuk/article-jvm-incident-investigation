# JVM Incident Investigation: Part 7 — Blocking & I/O

Запросы тормозят, p95 латентности ползёт вверх, а CPU при этом низкий. В Part 6 у нас потоки застревали на мониторах (`BLOCKED`), а здесь они «зависают» иначе — в ожидании ответа от медленного downstream. Проблема не в блокировках и не в CPU, а в блокирующем I/O.

У нас есть демо-приложение с «красной кнопкой»: ручка, которая переводит WireMock в медленный режим и запускает 20 потоков, делающих блокирующие HTTP-вызовы. Пройдём весь путь от симптома до первопричины — с реальным thread dump.

## Симптом: тормозит, но CPU в норме

Включаем проблему:

```bash
scripts/incidents.sh start blocking-io
```

Ответ:

```json
{ "type": "blocking-io", "status": "active", "description": "проблема активирована" }
```

Смотрим на потоки. Метрика `jvm_threads_states_threads{state="waiting"}` растёт с 11 до ~33 (20 воркеров плюс пара служебных потоков), а `state="runnable"` не меняется (8). CPU при этом почти нулевой — `process_cpu_usage` ~0.01 после того, как потоки «устаканились».

Это сильный индикатор блокирующего I/O (подтверждается ниже thread dump'ом): **потоки не работают (CPU низкий) и не заблокированы на мониторе — они ждут I/O**. Каждый worker-поток занят ожиданием ответа от медленного downstream и не может делать ничего полезного.

## Дашборд: Part 7 — Blocking & I/O

Карта наблюдения — четыре панели:

| Панель | Метрика | Что показывает | Что ждём при инциденте |
|---|---|---|---|
| Waiting threads | `jvm_threads_states_threads{state="waiting"}` | потоки в ожидании (в т.ч. I/O) | растёт — воркеры ждут downstream |
| Blocked threads | `jvm_threads_states_threads{state="blocked"}` | потоки на мониторе | не растёт (это не deadlock) |
| Runnable threads | `jvm_threads_states_threads{state="runnable"}` | работающие потоки | не растёт (CPU не грузится) |
| Server request duration (p95) | `histogram_quantile(0.95, ...)` | p95 входящих запросов | растёт, когда воркеры исчерпывают пул |

Ключевое сочетание — `waiting` растёт, а `blocked` и `runnable` — нет. Это сразу отсекает deadlock (там рос бы `blocked`) и busy-loop (там рос бы `runnable`). А панель p95 показывает «боль» снаружи: когда блокирующие вызовы занимают все потоки обработки, входящие запросы встают в очередь и латентность растёт.

## Вопрос

Симптом — «тормозит, CPU в норме, потоки в waiting». По методике из Part 0 формулируем вопрос: **«где именно потоки проводят время и почему они ждут?»**.

Ответа нет ни в профайлере CPU (потоки не исполняются), ни в heap dump (память в порядке). Нужен инструмент, который покажет, *на чём* застрял каждый поток — thread dump.

## Диагностика: thread dump

Снимаем:

```bash
docker exec app jcmd 1 Thread.print
```

Находим наших воркеров:

```text
"blocking-io-worker-0" #61 daemon prio=5 os_prio=0 cpu=3.18ms elapsed=15.33s ... nid=63 waiting on condition
   java.lang.Thread.State: WAITING (parking)
	at jdk.internal.misc.Unsafe.park(java.base@25.0.4.1/Native Method)
	- parking to wait for  <0x000000061f850260> (a java.util.concurrent.CompletableFuture$Signaller)
	at java.util.concurrent.locks.LockSupport.park(...)
	at java.util.concurrent.CompletableFuture$Signaller.block(...)
```

Поток в состоянии `WAITING (parking)` — он «спит» в ожидании `CompletableFuture$Signaller`. Это блокирующая обёртка `RestClient`: вызов `.retrieve().toBodilessEntity()` внутри паркует поток на future, пока медленный downstream не ответит.

Важный нюанс виден прямо здесь: поток застрял не на сокете и не на мониторе, а на `CompletableFuture`. Под капотом Spring `RestClient` использует асинхронный `java.net.http.HttpClient`, а «блокирующим» его делает вызов `join()` — поэтому состояние `WAITING (parking)`, а не `BLOCKED`. Для читателя это отличие важно: оно и отличает этот инцидент от Part 6.

## Диагностика: JFR socket I/O

Для сокетного I/O у JFR есть готовые события — `jdk.SocketRead`/`jdk.SocketWrite`. Снимем запись:

```bash
docker exec app jcmd 1 JFR.start duration=15s filename=/artifacts/blocking-io.jfr settings=profile
```

И здесь обнаруживается та самая тонкость: `jfr view jdk.SocketRead` по нашей записи пуст. Причина ровно в том, что мы видели в thread dump: `RestClient` работает через асинхронный `HttpClient`, и сокетное чтение выполняют его внутренние селектор-потоки, а не наш worker-поток. Событие `jdk.SocketRead` (когда оно возникает) приписывается тому потоку, который реально делает блокирующее чтение сокета, — в легаси-стеке это был бы сам worker (`SocketInputStream`), а у асинхронного клиента это внутренний поток, которого в нашем срезе по worker-потокам просто нет.

Отсюда практический вывод: **для блокирующего I/O на современных HTTP-клиентах thread dump часто информативнее JFR-событий сокетов**. JFR socket-вьюхи отлично работают для легаси-стека (`java.net.HttpURLConnection`, `SocketInputStream`), а здесь главный сигнал — состояние потока в дампе.

## Первопричина

Смотрим код инцидента:

```java
for (int i = 0; i < 20; i++) {
    Thread t = new Thread(() -> {
        while (running.get()) {
            try {
                restClient.get().uri(downstreamUrl).retrieve().toBodilessEntity();
            } catch (Exception e) {
                // блокирующий вызов упал/таймаут — продолжаем
            }
        }
    }, "blocking-io-worker-" + i);
    t.setDaemon(true);
    t.start();
}
```

А downstream в этот момент настроен отвечать медленно (WireMock `fixedDelayMilliseconds=5000`). Первопричина — **блокирующий вызов к медленному downstream без таймаута**: каждый из 20 потоков в цикле делает синхронный HTTP-запрос и ждёт ответа 5 секунд, не делая полезной работы.

В проде это классика: синхронный `RestTemplate`/`RestClient`/`HttpClient` без `connectTimeout`/`readTimeout`, вызывающий внешний сервис, который иногда «тупит». Пока поток ждёт — он выключен из пула; когда такие вызовы множатся, пул потоков исчерпывается, и очередь входящих запросов растёт — отсюда и p95.

## Фикс и проверка

Выключаем проблему:

```bash
scripts/incidents.sh fix blocking-io
scripts/incidents.sh stop blocking-io
```

`fix`/`stop` останавливают воркеров и сбрасывают WireMock-маппинг. Проверяем: `waiting`-потоки падают с 33 обратно к baseline (~11–17), воркеры `blocking-io-worker-*` исчезают из thread dump.

В реальном коде фикс — не «остановить потоки», а устранить причину блокировки:

- **таймауты** — `connectTimeout`/`readTimeout` на HTTP-клиенте, чтобы медленный downstream не держал поток вечно;
- **изоляция** — вызовы к медленным внешним сервисам через отдельный пул/очередь, а не на request-потоках;
- **асинхронность** — не блокировать поток на I/O там, где можно ждать ответ реактивно.

Демо-ручка показывает первый шаг: убрать нагрузку, а потом чинить причину. Пример настройки таймаута для `RestClient` (он по умолчанию оборачивает `java.net.http.HttpClient`):

```java
HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();
RestClient client = RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory(http))
        .build();
```

Без таймаута медленный downstream держит поток столько, сколько отвечает (или до TCP-таймаута) — а это минуты простоя.

## Отличие от Part 6: waiting на I/O vs blocked на мониторе

Оба инцидента про «потоки застряли», но состояние потока разное — и это главный диагностический признак:

| | Part 6 deadlock | Part 7 blocking I/O |
|---|---|---|
| Состояние потока | `BLOCKED` (на мониторе) | `WAITING (parking)` (на I/O/future) |
| Thread dump | «Found one Java-level deadlock» | `CompletableFuture$Signaller` / сокет |
| CPU | ~0 | ~0 |
| Фикс | порядок локов (реальный deadlock → рестарт) | таймауты/изоляция/async |

В thread dump это читается буквально: `BLOCKED` = жду монитор (кто-то его держит), `WAITING (parking)` = жду условие/событие (ответ, сигнал). Один и тот же инструмент, но разная интерпретация.

## Что мы сделали

Полный алгоритм из Part 0 на конкретном инциденте:

```
симптом (p95 растёт, CPU в норме, waiting-потоки растут)
→ вопрос (где потоки проводят время?)
→ thread dump (WAITING (parking) на CompletableFuture)
→ первопричина (блокирующий вызов к медленному downstream без таймаута)
→ fix + проверка (таймауты/изоляция; потоки вернулись к baseline)
```

Ключевой урок этой части: **«зависший» поток бывает двух сортов — `BLOCKED` и `WAITING` — и это разные болезни**. Прежде чем искать deadlock, посмотрите на состояние потока в дампе: `WAITING (parking)` на I/O лечится таймаутами, а не порядком локов.

## Что дальше

В Part 8 возьмём симптом, к которому Part 7 нас подводит напрямую, — **исчерпание пула соединений к БД**: запросы встают в очередь, HikariCP на пределе. Там на сцену выйдут метрики пула и `pg_stat_activity`. Увидимся.
