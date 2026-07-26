package com.neobank.sidecar.ops;

import com.neobank.sidecar.dispatch.DispatchService;
import com.neobank.sidecar.scenario.ScenarioLibrary;
import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /health} and {@code GET /info} — is this box up, and where is it pointing. */
@RestController
public class OpsController {

    private final DataSource dataSource;
    private final DispatchService dispatches;
    private final ScenarioLibrary library;
    private final String version;

    public OpsController(DataSource dataSource,
                        DispatchService dispatches,
                        ScenarioLibrary library,
                        @Value("${sidecar.version:1.0.0}") String version) {
        this.dataSource = dataSource;
        this.dispatches = dispatches;
        this.library = library;
        this.version = version;
    }

    /**
     * DB-backed on purpose: the sidecar without its database can still answer HTTP but cannot
     * record anything, and "up" would then be a lie the compose healthcheck believes.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbUp;
        String detail = "ok";
        try (Connection connection = dataSource.getConnection()) {
            dbUp = connection.isValid(2);
        } catch (Exception e) {
            dbUp = false;
            detail = e.getMessage();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("service", "neobank-sidecar");
        body.put("timestamp", Instant.now().toString());
        body.put("database", Map.of("status", dbUp ? "UP" : "DOWN", "detail", detail));
        return ResponseEntity.status(dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** Where dispatches go and where callbacks are expected — the two addresses that matter. */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "neobank-sidecar");
        body.put("version", version);
        body.put("role", "mock orchestrator — sends applications to your module and receives its callback");
        body.put("moduleUrl", dispatches.defaultModuleUrl());
        body.put("modulePath", dispatches.modulePath());
        body.put("callbackPath", "/api/v1/callbacks");
        body.put("scenarioCount", library.catalogue().get("count"));
        return body;
    }
}
