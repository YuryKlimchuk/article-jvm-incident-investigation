package com.hydroyura.article.jvmincidents.incident;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Преобразует сегмент URL (например, "cpu") в {@link IncidentType}.
 */
@Component
public class IncidentTypeConverter implements Converter<String, IncidentType> {

    @Override
    public IncidentType convert(String source) {
        return IncidentType.fromCode(source);
    }
}
