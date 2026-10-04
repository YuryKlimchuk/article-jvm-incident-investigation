package com.hydroyura.article.jvmincidents.incident;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/incidents")
public class IncidentController {

    private final Map<IncidentType, Incident> incidents;

    public IncidentController(List<Incident> incidents) {
        this.incidents = incidents.stream()
                .collect(Collectors.toUnmodifiableMap(Incident::type, Function.identity()));
    }

    @GetMapping
    public List<IncidentView> list() {
        return incidents.values().stream()
                .map(this::view)
                .sorted(Comparator.comparing(IncidentView::type))
                .toList();
    }

    @GetMapping("/{type}/status")
    public IncidentView status(@PathVariable IncidentType type) {
        return view(get(type));
    }

    @PostMapping("/{type}/start")
    public IncidentView start(@PathVariable IncidentType type) {
        Incident incident = get(type);
        incident.start();
        return view(incident);
    }

    @PostMapping("/{type}/fix")
    public IncidentView fix(@PathVariable IncidentType type) {
        Incident incident = get(type);
        incident.fix();
        return view(incident);
    }

    @PostMapping("/{type}/stop")
    public IncidentView stop(@PathVariable IncidentType type) {
        Incident incident = get(type);
        incident.stop();
        return view(incident);
    }

    private Incident get(IncidentType type) {
        Incident incident = incidents.get(type);
        if (incident == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No incident registered for type: " + type);
        }
        return incident;
    }

    private IncidentView view(Incident incident) {
        return new IncidentView(
                incident.type().getCode(),
                incident.status().getCode(),
                incident.status().getDescription());
    }
}
