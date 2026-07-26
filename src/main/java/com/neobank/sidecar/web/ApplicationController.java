package com.neobank.sidecar.web;

import com.neobank.sidecar.SidecarDtos.ApplicationStatusUpdate;
import com.neobank.sidecar.dispatch.DispatchService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /api/v1/applications/{applicationId}} — the one endpoint your module calls.
 *
 * <p>This is a <b>copy of the real orchestrator's endpoint</b> and has to stay one: the path, the
 * three-field body and the {@code 200 {"received": true, …}} response are all identical, so a module
 * that can talk to this box can talk to the real orchestrator with one environment variable changed.
 * If you find yourself making it more forgiving than the orchestrator, you are building a box that
 * will let a broken module look finished.</p>
 *
 * <p><b>Always 200.</b> Even a late, duplicate or misdirected update is accepted and recorded — as
 * an {@code unsolicited} row if it matches nothing. A module must not be left retrying because its
 * report was refused, and a report you cannot see in the log is a dead end.</p>
 *
 * <h3>The same path means two different things here — worth knowing</h3>
 *
 * <p>{@code /api/v1/applications} appears twice in this service, in opposite directions:</p>
 *
 * <ul>
 *   <li><b>Outbound.</b> {@code MODULE_PATH} (default {@code /api/v1/applications}) is where the
 *       sidecar <em>POSTs an application to your module</em> — on your module's host.</li>
 *   <li><b>Inbound.</b> The {@code PUT} below is where <em>your module reports back</em> — on the
 *       sidecar's host.</li>
 * </ul>
 *
 * <p>Two hosts, two directions, one resource name. That is the contract being symmetrical, not a
 * loop: nothing here calls itself.</p>
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private final DispatchService dispatches;

    public ApplicationController(DispatchService dispatches) {
        this.dispatches = dispatches;
    }

    @PutMapping("/{applicationId}")
    public Map<String, Object> updateStatus(@PathVariable String applicationId,
                                            @Valid @RequestBody ApplicationStatusUpdate update) {
        dispatches.recordStatusUpdate(applicationId, update);
        return Map.of("received", true, "applicationId", applicationId);
    }
}
