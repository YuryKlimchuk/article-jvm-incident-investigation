package com.hydroyura.article.jvmincidents.incident.allocation;

import com.hydroyura.article.jvmincidents.incident.AbstractIncident;
import com.hydroyura.article.jvmincidents.incident.IncidentType;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Allocation churn: поток массово аллоцирует мелкие короткоживущие объекты.
 */
@Component
public class AllocationIncident extends AbstractIncident {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    // volatile-сток, чтобы JIT не устранил аллокации
    private volatile byte[] sink;

    public AllocationIncident() {
        super(IncidentType.ALLOCATION);
    }

    @Override
    protected void doStart() {
        running.set(true);
        thread = new Thread(() -> {
            while (running.get()) {
                for (int i = 0; i < 1_000; i++) {
                    byte[] buf = new byte[1024]; // 1 KB, живёт недолго
                    buf[0] = (byte) i;
                    sink = buf;
                }
            }
        }, "allocation-churner");
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
