package com.hydroyura.article.jvmincidents.incident;

/**
 * Базовая реализация: идемпотентность и переходы состояний.
 * Конкретный инцидент реализует только doStart/doFix/doStop.
 */
public abstract class AbstractIncident implements Incident {

    private final IncidentType type;
    private volatile IncidentStatus status = IncidentStatus.IDLE;

    protected AbstractIncident(IncidentType type) {
        this.type = type;
    }

    @Override
    public final IncidentType type() {
        return type;
    }

    @Override
    public final IncidentStatus status() {
        return status;
    }

    @Override
    public final synchronized void start() {
        if (status != IncidentStatus.IDLE) {
            return; // идемпотентно
        }
        doStart();
        status = IncidentStatus.ACTIVE;
    }

    @Override
    public final synchronized void fix() {
        if (status != IncidentStatus.ACTIVE) {
            return; // идемпотентно
        }
        doFix();
        status = IncidentStatus.FIXED;
    }

    @Override
    public final synchronized void stop() {
        if (status == IncidentStatus.IDLE) {
            return; // идемпотентно
        }
        doStop();
        status = IncidentStatus.IDLE;
    }

    protected abstract void doStart();

    protected abstract void doFix();

    protected abstract void doStop();
}
