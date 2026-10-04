package com.hydroyura.article.jvmincidents.incident.blockingio;

import com.hydroyura.article.jvmincidents.incident.AbstractIncident;
import com.hydroyura.article.jvmincidents.incident.IncidentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Blocking & I/O: WireMock переводится на медленный ответ (через REST admin API),
 * а worker-потоки делают блокирующие HTTP-вызовы, занимая потоки.
 */
@Component
public class BlockingIoIncident extends AbstractIncident {

    private final String downstreamUrl;
    private final String wiremockAdminUrl;
    private final RestClient restClient;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> workers = new ArrayList<>();

    public BlockingIoIncident(
            @Value("${incidents.blocking-io.downstream-url:http://localhost:8082/api/slow}") String downstreamUrl,
            @Value("${incidents.blocking-io.wiremock-admin-url:http://localhost:8082}") String wiremockAdminUrl) {
        super(IncidentType.BLOCKING_IO);
        this.downstreamUrl = downstreamUrl;
        this.wiremockAdminUrl = wiremockAdminUrl;
        this.restClient = RestClient.create();
    }

    @Override
    protected void doStart() {
        configureSlowDownstream();
        running.set(true);
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
            workers.add(t);
        }
    }

    private void configureSlowDownstream() {
        try {
            String mapping = """
                    {
                      "request": { "method": "GET", "url": "/api/slow" },
                      "response": { "status": 200, "fixedDelayMilliseconds": 5000, "jsonBody": { "ok": true } }
                    }
                    """;
            restClient.post()
                    .uri(wiremockAdminUrl + "/__admin/mappings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapping)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // WireMock недоступен — workers просто будут падать быстро (локальный запуск без стенда)
        }
    }

    @Override
    protected void doFix() {
        stopWorkersAndReset();
    }

    @Override
    protected void doStop() {
        stopWorkersAndReset();
    }

    private void stopWorkersAndReset() {
        running.set(false);
        workers.forEach(Thread::interrupt);
        workers.clear();
        resetDownstream();
    }

    private void resetDownstream() {
        try {
            restClient.delete().uri(wiremockAdminUrl + "/__admin/mappings").retrieve().toBodilessEntity();
        } catch (Exception e) {
            // ignore
        }
    }
}
