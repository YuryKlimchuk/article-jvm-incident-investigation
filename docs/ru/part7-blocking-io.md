# JVM Incident Investigation: Part 7 — Blocking & I/O

В проде этот симптом выглядит как растущий p95 латентности при низком CPU. В демо мы покажем его механику: потоки, занятые ожиданием ответа от медленного downstream. В Part 6 у нас потоки застревали на мониторах (`BLOCKED`), а здесь они «зависают» иначе — в ожидании ответа от медленного downstream. Проблема не в блокировках и не в CPU, а в блокирующем I/O.

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

Это сильный индикатор блокирующего I/O (подтверждается ниже дампом потоков): **потоки не работают (CPU низкий) и не заблокированы на мониторе — они ждут I/O**. Каждый worker-поток занят ожиданием ответа от медленного downstream и не может делать ничего полезного.

## Дашборд: Part 7 — Blocking & I/O

Карта наблюдения — четыре панели:

| Панель | Метрика | Что показывает | Что ждём при инциденте |
|---|---|---|---|
| Waiting threads | `jvm_threads_states_threads{state="waiting"}` | потоки в ожидании (в т.ч. I/O) | растёт — воркеры ждут downstream |
| Blocked threads | `jvm_threads_states_threads{state="blocked"}` | потоки на мониторе | не растёт (это не deadlock) |
| Runnable threads | `jvm_threads_states_threads{state="runnable"}` | работающие потоки | не растёт (CPU не грузится) |
| Server request duration (p95) | `histogram_quantile(0.95, ...)` | p95 входящих запросов | в проде растёт, когда такие вызовы занимают request-потоки |

Ключевое сочетание — `waiting` растёт, а `blocked` и `runnable` — нет. Для наших двух демо это быстрый фильтр: классический deadlock на `synchronized` рос бы в `blocked`, busy-loop — в `runnable`. (Но по одному состоянию диагноз не ставится — подтверждаем ниже дампом потоков.) Панель p95 показывает «боль» снаружи — но в проде, когда блокирующие вызовы делаются на request-потоках и исчерпывают пул. В демо воркеры — отдельные фоновые потоки, поэтому p95 входящих запросов мы не меряем; наблюдаемым симптомом здесь служит рост `waiting`-потоков.

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

И здесь обнаруживается тонкость: `jfr view jdk.SocketRead` по нашей записи пуст — событий сокетного чтения не оказалось. Причина в том, что мы видели в thread dump: `RestClient` работает через асинхронный `HttpClient`, который читает сокет через NIO-селектор на своих внутренних потоках, а не на worker-потоке. Событие `jdk.SocketRead` эмитится для блокирующего чтения через `SocketInputStream` (легаси-стек) — поэтому для асинхронного клиента его может не быть вовсе или оно окажется на внутреннем потоке, а не на том, что делает `join()`.

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

Демо-ручка показывает первый шаг: убрать нагрузку, а потом чинить причину. Пример настройки таймаутов для `RestClient` (он по умолчанию оборачивает `java.net.http.HttpClient`):

```java
HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))   // таймаут установки соединения
        .build();
JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
factory.setReadTimeout(Duration.ofSeconds(3));  // таймаут ожидания ответа
RestClient client = RestClient.builder().requestFactory(factory).build();
```

Здесь два разных таймаута: `connectTimeout` ограничивает установление соединения, а `readTimeout` — ожидание ответа после того, как соединение установлено. Наш WireMock отвечает через 5 секунд, поэтому именно `readTimeout` спасает от вечного ожидания. Без него медленный downstream держит поток столько, сколько отвечает (или до TCP-таймаута) — а это минуты простоя.

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
симптом (waiting-потоки растут, CPU в норме; в проде это даёт рост p95)
→ вопрос (где потоки проводят время?)
→ thread dump (WAITING (parking) на CompletableFuture)
→ первопричина (блокирующий вызов к медленному downstream без таймаута)
→ fix + проверка (таймауты/изоляция; потоки вернулись к baseline)
```

Ключевой урок этой части: **в этих двух демо «зависший» поток различается состоянием — `BLOCKED` (монитор) против `WAITING (parking)` (I/O/future), и это разные болезни**. В общем случае состояний больше (`TIMED_WAITING`, `RUNNABLE` на сокетном чтении и т.д.), поэтому дамп читают по стеку, а не только по состоянию. Но как быстрый фильтр состояние работает для этих двух демо: `WAITING (parking)` на future при низком CPU здесь — I/O, а не deadlock. В общем случае смотрите, кто должен завершить future: это может быть не I/O, а зависшая задача или истощённый executor.

## Что дальше

В Part 8 возьмём симптом, к которому Part 7 нас подводит напрямую, — **исчерпание пула соединений к БД**: запросы встают в очередь, HikariCP на пределе. Там на сцену выйдут метрики пула и `pg_stat_activity`. Увидимся.
