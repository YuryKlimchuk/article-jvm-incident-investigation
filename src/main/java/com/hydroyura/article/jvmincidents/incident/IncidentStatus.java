package com.hydroyura.article.jvmincidents.incident;

public enum IncidentStatus {

    IDLE("idle", "инцидент не активирован"),
    ACTIVE("active", "проблема активирована"),
    FIXED("fixed", "применён демо-фикс");

    private final String code;
    private final String description;

    IncidentStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
