package com.hydroyura.article.jvmincidents.incident.memoryleak;

import com.hydroyura.article.jvmincidents.incident.AbstractIncident;
import com.hydroyura.article.jvmincidents.incident.IncidentType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Memory leak: объекты добавляются в static-коллекцию и никогда не удаляются.
 */
@Component
public class MemoryLeakIncident extends AbstractIncident {

    private static final List<byte[]> LEAKED = new ArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public MemoryLeakIncident() {
        super(IncidentType.MEMORY_LEAK);
    }

    @Override
    protected void doStart() {
        running.set(true);
        thread = new Thread(() -> {
            while (running.get()) {
                LEAKED.add(new byte[1024 * 1024]); // 1 MB удерживается
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "memory-leaker");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void doFix() {
        stopAndClear();
    }

    @Override
    protected void doStop() {
        stopAndClear();
    }

    private void stopAndClear() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        LEAKED.clear();
    }
}
