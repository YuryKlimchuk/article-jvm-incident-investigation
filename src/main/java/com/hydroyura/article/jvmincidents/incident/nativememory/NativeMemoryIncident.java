package com.hydroyura.article.jvmincidents.incident.nativememory;

import com.hydroyura.article.jvmincidents.incident.AbstractIncident;
import com.hydroyura.article.jvmincidents.incident.IncidentType;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native memory: direct ByteBuffers выделяются и не освобождаются,
 * из-за чего растёт RSS при нормальной Java-куче.
 */
@Component
public class NativeMemoryIncident extends AbstractIncident {

    private static final List<ByteBuffer> BUFFERS = new ArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public NativeMemoryIncident() {
        super(IncidentType.NATIVE_MEMORY);
    }

    @Override
    protected void doStart() {
        running.set(true);
        thread = new Thread(() -> {
            while (running.get()) {
                BUFFERS.add(ByteBuffer.allocateDirect(1024 * 1024)); // 1 MB native
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "native-allocator");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void doFix() {
        release();
    }

    @Override
    protected void doStop() {
        release();
    }

    private void release() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        BUFFERS.clear();
        System.gc(); // даём Cleaner'у шанс освободить direct-память
    }
}
