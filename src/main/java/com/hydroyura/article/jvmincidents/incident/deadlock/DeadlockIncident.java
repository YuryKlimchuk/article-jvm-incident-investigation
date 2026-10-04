package com.hydroyura.article.jvmincidents.incident.deadlock;

import com.hydroyura.article.jvmincidents.incident.AbstractIncident;
import com.hydroyura.article.jvmincidents.incident.IncidentType;
import org.springframework.stereotype.Component;

/**
 * Deadlock: два потока захватывают два лока в противоположном порядке.
 * Обратите внимание: реальный deadlock нельзя снять без перезапуска —
 * зависшие потоки остаются висеть, это ожидаемое поведение демо.
 */
@Component
public class DeadlockIncident extends AbstractIncident {

    private final Object lockA = new Object();
    private final Object lockB = new Object();

    public DeadlockIncident() {
        super(IncidentType.DEADLOCK);
    }

    @Override
    protected void doStart() {
        Thread t1 = new Thread(() -> {
            synchronized (lockA) {
                sleep(50);
                synchronized (lockB) {
                    // никогда не достигнется
                }
            }
        }, "deadlock-a-then-b");
        Thread t2 = new Thread(() -> {
            synchronized (lockB) {
                sleep(50);
                synchronized (lockA) {
                    // никогда не достигнется
                }
            }
        }, "deadlock-b-then-a");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
    }

    @Override
    protected void doFix() {
        // Демонстрация правильного порядка блокировок на «свежих» локах:
        // оба потока берут lockA, затем lockB — и нормально завершаются.
        Object a = new Object();
        Object b = new Object();
        Runnable correctOrder = () -> {
            synchronized (a) {
                synchronized (b) {
                    // работает без deadlock
                }
            }
        };
        Thread ok1 = new Thread(correctOrder, "fixed-lock-order-1");
        Thread ok2 = new Thread(correctOrder, "fixed-lock-order-2");
        ok1.setDaemon(true);
        ok2.setDaemon(true);
        ok1.start();
        ok2.start();
    }

    @Override
    protected void doStop() {
        // Зависшие на мониторе потоки не прерываются; для полного сброса нужен restart.
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
