package com.neobank.sidecar.dispatch;

import com.neobank.sidecar.SidecarDtos.CallbackBody;
import com.neobank.sidecar.SidecarDtos.DispatchRequest;
import com.neobank.sidecar.SidecarDtos.ExchangeView;
import com.neobank.sidecar.scenario.ScenarioLibrary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * The whole behaviour of the sidecar: POST an application envelope at a module, record what it
 * acknowledged, and later pair the module's callback with the row that provoked it.
 *
 * <p>There is deliberately no state machine, no sequencing and no retry. The real orchestrator
 * has all three ({@code SagaEngine}); a box whose only job is to let one team see both halves of
 * one exchange does not, and adding them would make it a second thing to keep in step with the
 * first.</p>
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final ExchangeRepository exchanges;
    private final ScenarioLibrary library;
    private final RestClient restClient;
    private final String defaultModuleUrl;
    private final String modulePath;

    /** Only has to be unique within a run — it is a suffix on an application id, not an id. */
    private final AtomicLong freshCounter = new AtomicLong();

    public DispatchService(ExchangeRepository exchanges,
                           ScenarioLibrary library,
                           RestClient restClient,
                           @Value("${sidecar.module-url:http://backend:8080}") String defaultModuleUrl,
                           @Value("${sidecar.module-path:/api/v1/applications}") String modulePath) {
        this.exchanges = exchanges;
        this.library = library;
        this.restClient = restClient;
        this.defaultModuleUrl = defaultModuleUrl;
        this.modulePath = modulePath;
    }

    public String defaultModuleUrl() {
        return defaultModuleUrl;
    }

    public String modulePath() {
        return modulePath;
    }

    /**
     * Send one application to the module and record both the attempt and its acknowledgement.
     *
     * <p>A refused or unreachable module is a <em>result</em>, not an error: the row is written
     * with the status code (or 0 and the exception text) so the UI can show it. That is the
     * point of scenario 26, which must come back 400.</p>
     */
    public ExchangeView dispatch(DispatchRequest request) {
        Map<String, Object> envelope = resolveEnvelope(request);
        String moduleUrl = trimTrailingSlash(
                isBlank(request.moduleUrl()) ? defaultModuleUrl : request.moduleUrl());

        if (Boolean.TRUE.equals(request.freshId())) {
            envelope = withFreshId(envelope);
        }

        Exchange exchange = exchanges.save(Exchange.dispatched(
                text(envelope.get("applicationId")),
                text(envelope.get("correlationId")),
                request.scenarioId(),
                moduleUrl));

        String url = moduleUrl + modulePath;
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(url)
                    .body(envelope)
                    .retrieve()
                    // Do not throw on 4xx/5xx: a 400 from the module is the answer we asked for.
                    .onStatus(status -> true, (req, res) -> { })
                    .toEntity(String.class);
            exchange.recordAck(response.getStatusCode().value(), response.getBody());
            log.info("SENT {} -> {} ({})", exchange.getApplicationId(), url,
                    response.getStatusCode().value());
        } catch (Exception e) {
            // Unreachable module, DNS failure, timeout. Record it as status 0 with the reason —
            // "nothing happened" is the single most confusing thing a tool like this can show.
            exchange.recordAck(0, e.toString());
            log.warn("SENT {} -> {} failed: {}", exchange.getApplicationId(), url, e.toString());
        }
        return ExchangeView.of(exchanges.save(exchange));
    }

    /**
     * Record a module's callback against the dispatch that provoked it.
     *
     * <p>Always succeeds. A callback for an application we never sent, or for one that already
     * answered, is stored as an {@code unsolicited} row instead of being refused — the same
     * reasoning the real orchestrator uses: a module must not be left retrying because its late
     * or duplicate callback was rejected, and a callback nobody can see is a debugging dead
     * end.</p>
     */
    @Transactional
    public void recordCallback(CallbackBody callback) {
        Optional<Exchange> match = exchanges
                .findFirstByApplicationIdAndCallbackAtIsNullOrderByIdAsc(callback.applicationId());

        Exchange exchange = match.orElseGet(() -> {
            log.warn("CALLBACK {} from {} matched no open dispatch — recorded as unsolicited",
                    callback.applicationId(), callback.serviceId());
            return Exchange.unsolicited(callback.applicationId());
        });

        exchange.recordCallback(callback.serviceId(), callback.status(), callback.comment());
        exchanges.save(exchange);
        log.info("CALLBACK {} {} from {} ({})", callback.applicationId(), callback.status(),
                callback.serviceId(), callback.comment());
    }

    @Transactional(readOnly = true)
    public List<ExchangeView> log() {
        return exchanges.findAllByOrderByIdDesc().stream().map(ExchangeView::of).toList();
    }

    public void clear() {
        exchanges.deleteAll();
        log.info("Exchange log cleared");
    }

    private Map<String, Object> resolveEnvelope(DispatchRequest request) {
        if (request.envelope() != null && !request.envelope().isEmpty()) {
            return request.envelope();
        }
        if (isBlank(request.scenarioId())) {
            throw new IllegalArgumentException("give either a scenarioId or an envelope");
        }
        return library.envelopeOf(request.scenarioId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "no scenario " + request.scenarioId() + " in the library"));
    }

    /**
     * Give the application a unique id so a scenario can be sent again against a module that is
     * (correctly) idempotent on applicationId.
     *
     * <p>Both copies move together — the envelope's {@code applicationId} and the nested
     * {@code application.applicationId}. Rewriting one and not the other is the classic version
     * of this bug: the module logs one id and stores the other, and nothing lines up
     * afterwards.</p>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withFreshId(Map<String, Object> envelope) {
        String original = text(envelope.get("applicationId"));
        if (original == null) {
            // The invalid-envelope scenario has no id on purpose; leave it broken, that is its job.
            return envelope;
        }
        String fresh = original + "-" + freshCounter.incrementAndGet();

        Map<String, Object> copy = new LinkedHashMap<>(envelope);
        copy.put("applicationId", fresh);
        if (copy.get("application") instanceof Map<?, ?> application) {
            Map<String, Object> app = new LinkedHashMap<>((Map<String, Object>) application);
            if (app.containsKey("applicationId")) {
                app.put("applicationId", fresh);
            }
            copy.put("application", app);
        }
        return copy;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
