package com.hydroyura.article.jvmincidents.incident.gc;

import com.hydroyura.article.jvmincidents.incident.AbstractIncident;
import com.hydroyura.article.jvmincidents.incident.IncidentType;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GC pressure: поток аллоцирует крупные короткоживущие массивы,
 * провоцируя частые сборки мусора и возможные паузы.
 */
@Component
public class GcIncident extends AbstractIncident {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    private volatile byte[] sink;

    public GcIncident() {
        super(IncidentType.GC);
    }

    @Override
    protected void doStart() {
        running.set(true);
        thread = new Thread(() -> {
            while (running.get()) {
                byte[] big = new byte[4 * 1024 * 1024]; // 4 MB, умирает сразу
                big[0] = 1;
                sink = big;
            }
        }, "gc-pressure");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void doFix() {
        stopThread();
    }

    @Override
    protected void doStop() {
        stopThread();
    }

    private void stopThread() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
