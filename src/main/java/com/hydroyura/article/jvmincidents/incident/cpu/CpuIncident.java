package com.hydroyura.article.jvmincidents.incident.cpu;

import com.hydroyura.article.jvmincidents.incident.AbstractIncident;
import com.hydroyura.article.jvmincidents.incident.IncidentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High CPU: фоновые потоки в busy-loop нагружают процессор.
 */
@Component
public class CpuIncident extends AbstractIncident {

    private final int threadCount;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> threads = new ArrayList<>();

    // volatile-сток, чтобы JIT не выкинул вычисления как неиспользуемые
    private volatile double sink;

    public CpuIncident(@Value("${incidents.cpu.threads:2}") int threadCount) {
        super(IncidentType.CPU);
        this.threadCount = threadCount;
    }

    @Override
    protected void doStart() {
        running.set(true);
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> {
                while (running.get()) {
                    double acc = 0.0;
                    for (int j = 0; j < 200_000; j++) {
                        acc += Math.sin(j) * Math.cos(j);
                    }
                    sink = acc;
                }
            }, "cpu-burner-" + i);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
    }

    @Override
    protected void doFix() {
        stopWorkers();
    }

    @Override
    protected void doStop() {
        stopWorkers();
    }

    private void stopWorkers() {
        running.set(false);
        threads.forEach(Thread::interrupt);
        threads.clear();
    }
}
