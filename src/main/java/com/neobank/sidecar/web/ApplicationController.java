package com.neobank.sidecar.web;

import com.neobank.sidecar.SidecarDtos.ApplicationStatusUpdate;
import com.neobank.sidecar.dispatch.DispatchService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * {@code GET /api/v1/applications/{applicationId}} — <b>read the application back</b>. Also a
     * copy of the real orchestrator's endpoint, and also one your module may call.
     *
     * <p>Answers the api-contract §4 application object: the very thing that was POSTed to your
     * module in the {@code application} field of its envelope. A module that needs applicant data
     * it correctly did not store locally reads it here. One object, two ways to get it — pushed to
     * you, or pulled by you — and if those two ever disagreed, pulling would be worthless.</p>
     *
     * <p><b>404 on an unknown id</b>, and on an id that only ever appeared on an unsolicited
     * callback. There is no application behind those, and {@code 200} with an empty body would let
     * a module treat "nothing here" as "an applicant with no name".</p>
     */
    @GetMapping("/{applicationId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String applicationId) {
        return ResponseEntity.of(dispatches.application(applicationId));
    }

    /**
     * {@code GET /api/v1/applications?name=} — the orchestrator's name search, same shape: a list
     * of §4 application objects, matched on a case-insensitive substring of
     * {@code applicant.fullName}.
     *
     * <p><b>There is deliberately no handler for the bare collection.</b> On the real orchestrator
     * {@code GET /api/v1/applications} returns its board — ten step statuses per row — and this box
     * has no board, no journey and no steps. Serving a hollow imitation is exactly how a module
     * comes to depend on something that will not be there, so asking for it here gets you nothing
     * rather than something misleading.</p>
     */
    @GetMapping(params = "name")
    public List<Map<String, Object>> byName(@RequestParam String name) {
        return dispatches.applicationsByName(name);
    }
}
