package com.neobank.sidecar.web;

import com.neobank.sidecar.SidecarDtos.CallbackBody;
import com.neobank.sidecar.dispatch.DispatchService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/callbacks} — the one endpoint your module calls.
 *
 * <p>This is a copy of the real orchestrator's callback endpoint, and it has to stay one. The
 * path, the four-field body and the {@code 200 {"received": true, ...}} response are all
 * identical, so a module that can talk to this box can talk to the real orchestrator with one
 * environment variable changed. If you find yourself wanting to make it more forgiving than the
 * orchestrator, you are building a box that will let a broken module look finished.</p>
 *
 * <p><b>Always 200.</b> Even a late, duplicate or misdirected callback is accepted and recorded —
 * as an {@code unsolicited} row if it matches nothing. A module must not be left retrying
 * because its callback was refused, and a callback you cannot see in the log is a dead end.</p>
 */
@RestController
@RequestMapping("/api/v1/callbacks")
public class CallbackController {

    private final DispatchService dispatches;

    public CallbackController(DispatchService dispatches) {
        this.dispatches = dispatches;
    }

    @PostMapping
    public Map<String, Object> receive(@Valid @RequestBody CallbackBody body) {
        dispatches.recordCallback(body);
        return Map.of("received", true, "applicationId", body.applicationId());
    }
}
