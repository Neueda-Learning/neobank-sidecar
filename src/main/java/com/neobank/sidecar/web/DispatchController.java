package com.neobank.sidecar.web;

import com.neobank.sidecar.SidecarDtos.DispatchRequest;
import com.neobank.sidecar.SidecarDtos.ExchangeView;
import com.neobank.sidecar.dispatch.DispatchService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Playing the orchestrator: send an application, then read the log of what came back.
 *
 * <p>These endpoints are the sidecar's own operator API, <em>not</em> part of the module contract
 * — your module never calls them and must not know they exist.</p>
 */
@RestController
public class DispatchController {

    private final DispatchService dispatches;

    public DispatchController(DispatchService dispatches) {
        this.dispatches = dispatches;
    }

    /** Send one application: name a corpus scenario, or pass an envelope you edited. */
    @PostMapping("/api/v1/dispatch")
    public ExchangeView dispatch(@RequestBody DispatchRequest request) {
        return dispatches.dispatch(request);
    }

    /** The exchange log, newest first: what was sent, and what the module answered. */
    @GetMapping("/api/v1/dispatches")
    public List<ExchangeView> log() {
        return dispatches.log();
    }

    @DeleteMapping("/api/v1/dispatches")
    public Map<String, Object> clear() {
        dispatches.clear();
        return Map.of("cleared", true);
    }
}
