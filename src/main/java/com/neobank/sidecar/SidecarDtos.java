package com.neobank.sidecar;

import com.neobank.sidecar.dispatch.Exchange;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** Every shape that crosses this box's HTTP boundary, in one file. */
public final class SidecarDtos {

    private SidecarDtos() {
    }

    /**
     * What a module PUTs to {@code /api/v1/applications/{applicationId}} once it has an answer.
     *
     * <p><b>Do not add fields.</b> This is a copy of the real orchestrator's
     * {@code SagaDtos.ApplicationStatusUpdate} — exactly three, with the application id in the URL
     * rather than the body — and the only reason the sidecar is worth anything is that it accepts
     * precisely what the orchestrator accepts. A module that satisfies this record satisfies the
     * real thing.</p>
     */
    public record ApplicationStatusUpdate(
            @NotBlank String serviceId,
            @NotBlank String status,
            String comment) {
    }

    /**
     * Ask the sidecar to send something. Either name a {@code scenarioId} from the corpus, or
     * pass a hand-edited {@code envelope} — the UI lets you edit a scenario before sending, and
     * an edited envelope is no longer the scenario on disk.
     *
     * @param moduleUrl overrides the configured module address for this one dispatch, which is
     *                  how you point at a backend running in your IDE without a restart
     * @param freshId   rewrites the application id to a unique one, so a scenario can be sent
     *                  repeatedly against a module that is (correctly) idempotent
     */
    public record DispatchRequest(
            String scenarioId,
            Map<String, Object> envelope,
            String moduleUrl,
            Boolean freshId) {
    }

    /** One row of the exchange log: what we sent, and what came back. */
    public record ExchangeView(
            Long id,
            String applicationId,
            String correlationId,
            String scenarioId,
            String moduleUrl,
            String sentAt,
            Integer ackHttpStatus,
            String ackBody,
            String callbackServiceId,
            String callbackStatus,
            String callbackComment,
            String callbackAt,
            boolean unsolicited) {

        public static ExchangeView of(Exchange e) {
            return new ExchangeView(
                    e.getId(),
                    e.getApplicationId(),
                    e.getCorrelationId(),
                    e.getScenarioId(),
                    e.getModuleUrl(),
                    e.getSentAt() == null ? null : e.getSentAt().toString(),
                    e.getAckHttpStatus(),
                    e.getAckBody(),
                    e.getCallbackServiceId(),
                    e.getCallbackStatus(),
                    e.getCallbackComment(),
                    e.getCallbackAt() == null ? null : e.getCallbackAt().toString(),
                    e.isUnsolicited());
        }
    }
}
