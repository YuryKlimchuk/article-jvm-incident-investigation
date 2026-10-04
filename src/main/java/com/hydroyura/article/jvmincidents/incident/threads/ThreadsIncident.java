package com.hydroyura.article.jvmincidents.incident.threads;

import com.hydroyura.article.jvmincidents.incident.AbstractIncident;
import com.hydroyura.article.jvmincidents.incident.IncidentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Too many threads: неограниченное создание потоков без пула.
 * Каждый поток «спит вечно», удерживая нативный стек.
 */
@Component
public class ThreadsIncident extends AbstractIncident {

    private final int threadCount;
    private final Set<Thread> created = ConcurrentHashMap.newKeySet();

    public ThreadsIncident(@Value("${incidents.threads.count:500}") int threadCount) {
        super(IncidentType.THREADS);
        this.threadCount = threadCount;
    }

    @Override
    protected void doStart() {
        Thread spawner = new Thread(() -> {
            for (int i = 0; i < threadCount; i++) {
                try {
                    Thread t = new Thread(() -> {
                        try {
                            Thread.sleep(Long.MAX_VALUE);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }, "idle-thread-" + i);
                    t.setDaemon(true);
                    t.start();
                    created.add(t);
                } catch (OutOfMemoryError e) {
                    // симптом: unable to create native thread
                    break;
                }
            }
        }, "thread-spawner");
        spawner.setDaemon(true);
        spawner.start();
    }

    @Override
    protected void doFix() {
        cleanup();
    }

    @Override
    protected void doStop() {
        cleanup();
    }

    private void cleanup() {
        created.forEach(Thread::interrupt);
        created.clear();
    }
}
