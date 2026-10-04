package com.hydroyura.article.jvmincidents.incident;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Типы инцидентов. Код ({@link #getCode()}) используется в URL-пути и в Swagger UI.
 */
public enum IncidentType {

    CPU("cpu"),
    ALLOCATION("allocation"),
    GC("gc"),
    MEMORY_LEAK("memory-leak"),
    NATIVE_MEMORY("native-memory"),
    DEADLOCK("deadlock"),
    THREADS("threads"),
    BLOCKING_IO("blocking-io"),
    DB_POOL("db-pool");

    private final String code;

    IncidentType(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    public static IncidentType fromCode(String code) {
        for (IncidentType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown incident type: " + code);
    }
}
