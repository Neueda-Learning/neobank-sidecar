package com.neobank.sidecar.web;

import com.neobank.sidecar.scenario.ScenarioLibrary;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/scenarios} — the library of applications, with each envelope attached.
 *
 * <p>Read this to see exactly what the orchestrator can send you. Each entry carries the
 * envelope itself plus what it is for: which modules care, the reason codes it should provoke,
 * the expected HTTP status, and the arithmetic behind any boundary it sits on.</p>
 */
@RestController
public class ScenarioController {

    private final ScenarioLibrary library;

    public ScenarioController(ScenarioLibrary library) {
        this.library = library;
    }

    @GetMapping("/api/v1/scenarios")
    public Map<String, Object> scenarios() {
        return library.catalogue();
    }
}
