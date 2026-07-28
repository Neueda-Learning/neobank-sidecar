package com.neobank.sidecar.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.sidecar.SidecarDtos.ApplicationStatusUpdate;
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
    private final ObjectMapper json;
    private final String defaultModuleUrl;
    private final String modulePath;

    /** Only has to be unique within a run — it is a suffix on an application id, not an id. */
    private final AtomicLong freshCounter = new AtomicLong();

    public DispatchService(ExchangeRepository exchanges,
                           ScenarioLibrary library,
                           RestClient restClient,
                           ObjectMapper json,
                           @Value("${sidecar.module-url:http://backend:8080}") String defaultModuleUrl,
                           @Value("${sidecar.module-path:/api/v1/applications}") String modulePath) {
        this.exchanges = exchanges;
        this.library = library;
        this.restClient = restClient;
        this.json = json;
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
                moduleUrl,
                // Stored AFTER any freshId rewrite, so what can be read back is what was sent.
                writeJson(envelope.get("application"))));

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
    public void recordStatusUpdate(String applicationId, ApplicationStatusUpdate update) {
        Optional<Exchange> match = exchanges
                .findFirstByApplicationIdAndCallbackAtIsNullOrderByIdAsc(applicationId);

        Exchange exchange = match.orElseGet(() -> {
            log.warn("STATUS UPDATE {} from {} matched no open dispatch — recorded as unsolicited",
                    applicationId, update.serviceId());
            return Exchange.unsolicited(applicationId);
        });

        exchange.recordCallback(update.serviceId(), update.status(), update.comment());
        exchanges.save(exchange);
        log.info("STATUS UPDATE {} {} from {} ({})", applicationId, update.status(),
                update.serviceId(), update.comment());
    }

    @Transactional(readOnly = true)
    public List<ExchangeView> log() {
        return exchanges.findAllByOrderByIdDesc().stream().map(ExchangeView::of).toList();
    }

    /**
     * The application behind an id, as the api-contract §4 object — a copy of the real
     * orchestrator's {@code GET /api/v1/applications/{id}}.
     *
     * <p>Unlike everything else in this class, this is <b>contract surface</b>: your module calls
     * it when it needs applicant data it correctly did not store locally, and it must answer with
     * the same shape the orchestrator does. The sidecar can be truthful here because it is not
     * inventing anything — it returns the application it sent you.</p>
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> application(String applicationId) {
        return exchanges
                .findFirstByApplicationIdAndApplicationJsonIsNotNullOrderByIdDesc(applicationId)
                .map(e -> readJson(e.getApplicationJson()));
    }

    /**
     * Applications whose applicant name contains {@code name}, newest first — a copy of the
     * orchestrator's {@code GET /api/v1/applications?name=}.
     *
     * <p>Matched by parsing each stored application rather than by a denormalised column. The
     * orchestrator has a whole board to index; this box has a few dozen rows, and a second copy of
     * the name would be a second thing that can drift from the row it describes.</p>
     *
     * <p>One entry per applicationId: SIM-25 deliberately re-sends SIM-01's id, and a search that
     * returned the same application twice would look like a bug in the module.</p>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> applicationsByName(String name) {
        if (isBlank(name)) {
            return List.of();
        }
        String needle = name.strip().toLowerCase();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Exchange e : exchanges.findByApplicationJsonIsNotNullOrderByIdDesc()) {
            Map<String, Object> application = readJson(e.getApplicationJson());
            if (application.isEmpty() || !fullNameOf(application).contains(needle)) {
                continue;
            }
            byId.putIfAbsent(String.valueOf(e.getApplicationId()), application);
        }
        return List.copyOf(byId.values());
    }

    @SuppressWarnings("unchecked")
    private static String fullNameOf(Map<String, Object> application) {
        Object applicant = application.get("applicant");
        if (!(applicant instanceof Map<?, ?> map)) {
            return "";
        }
        Object fullName = ((Map<String, Object>) map).get("fullName");
        return fullName == null ? "" : String.valueOf(fullName).toLowerCase();
    }

    /**
     * The application, as JSON, ready to store — or null, which costs only the read-back.
     *
     * <p><b>Never truncated.</b> Half a JSON document is not a smaller JSON document, it is a
     * parse error waiting to be served to a module as if it were an application. Too big is
     * therefore stored as nothing, loudly, and the dispatch still goes out: sending is this box's
     * job, keeping a copy is a convenience.</p>
     */
    private String writeJson(Object application) {
        if (!(application instanceof Map<?, ?>)) {
            // Scenario 08 and friends send an envelope with no application. Nothing to keep.
            return null;
        }
        try {
            String encoded = json.writeValueAsString(application);
            if (encoded.length() > Exchange.APPLICATION_JSON_MAX) {
                log.warn("Application is {} chars, over the {} the column holds — it was still "
                                + "sent, but GET /api/v1/applications/{} will answer 404",
                        encoded.length(), Exchange.APPLICATION_JSON_MAX,
                        text(((Map<?, ?>) application).get("applicationId")));
                return null;
            }
            return encoded;
        } catch (Exception e) {
            log.warn("Could not store the application we sent: {}", e.toString());
            return null;
        }
    }

    private Map<String, Object> readJson(String applicationJson) {
        try {
            return applicationJson == null ? Map.of()
                    : json.readValue(applicationJson, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("Unreadable stored application: {}", e.toString());
            return Map.of();
        }
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
