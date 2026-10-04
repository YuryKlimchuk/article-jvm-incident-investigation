package com.hydroyura.article.jvmincidents.incident;

/**
 * DTO для ответа REST API.
 */
public record IncidentView(String type, String status, String description) {
}
