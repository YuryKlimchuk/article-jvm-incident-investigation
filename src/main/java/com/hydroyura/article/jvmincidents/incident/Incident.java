package com.hydroyura.article.jvmincidents.incident;

/**
 * Контракт инцидента: один воспроизводимый класс производственной проблемы.
 */
public interface Incident {

    IncidentType type();

    IncidentStatus status();

    void start();

    void fix();

    void stop();
}
