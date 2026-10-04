package com.hydroyura.article.jvmincidents.incident.dbpool;

import com.hydroyura.article.jvmincidents.incident.AbstractIncident;
import com.hydroyura.article.jvmincidents.incident.IncidentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connection pool exhaustion: потоки берут соединения из HikariCP и не возвращают их.
 */
@Component
public class DbPoolIncident extends AbstractIncident {

    private final DataSource dataSource;
    private final int threadCount;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> workers = new ArrayList<>();
    private final List<Connection> heldConnections = new ArrayList<>();

    public DbPoolIncident(
            DataSource dataSource,
            @Value("${incidents.db-pool.threads:12}") int threadCount) {
        super(IncidentType.DB_POOL);
        this.dataSource = dataSource;
        this.threadCount = threadCount;
    }

    @Override
    protected void doStart() {
        running.set(true);
        for (int i = 0; i < threadCount; i++) {
            Thread t = new Thread(() -> {
                try {
                    Connection conn = dataSource.getConnection();
                    synchronized (heldConnections) {
                        heldConnections.add(conn);
                    }
                    while (running.get()) {
                        Thread.sleep(1000); // удерживаем соединение
                    }
                } catch (Exception e) {
                    // пул исчерпан или БД недоступна — это и есть симптом
                }
            }, "db-pool-holder-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
    }

    @Override
    protected void doFix() {
        releaseConnections();
    }

    @Override
    protected void doStop() {
        releaseConnections();
    }

    private void releaseConnections() {
        running.set(false);
        workers.forEach(Thread::interrupt);
        workers.clear();
        synchronized (heldConnections) {
            for (Connection conn : heldConnections) {
                try {
                    conn.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            heldConnections.clear();
        }
    }
}
