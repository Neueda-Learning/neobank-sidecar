package com.neobank.sidecar.dispatch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One contract exchange: what the sidecar sent to a module, and what the module said back.
 *
 * <p>A row starts as a dispatch (module url, sent at, the ack) and is completed later by the
 * callback — or never, which is itself the interesting case. A callback that matches no
 * dispatch gets its own row with {@link #isUnsolicited()} set, because a misdirected callback
 * must be <em>visible</em>: silently dropping it is how you spend an afternoon wondering why
 * the log is empty.</p>
 */
@Entity
@Table(name = "exchange")
public class Exchange {

    /**
     * Column width for {@link #applicationJson}, and a hard ceiling rather than a comfortable one.
     *
     * <p>MySQL caps a row at 65535 bytes and counts <em>declared</em> VARCHAR sizes at 4 bytes per
     * character, so this table cannot afford a second 8000. The largest application in the corpus
     * is ~1100 characters; 4000 is 3.6x that and leaves 13 KB of row spare. See
     * {@code 002-add-application-json.yaml} for the arithmetic.</p>
     */
    public static final int APPLICATION_JSON_MAX = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nullable: scenario 26 is an invalid envelope with no applicationId, and it still logs. */
    @Column(name = "application_id", length = 64)
    private String applicationId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Which corpus scenario this came from, or null for a hand-edited envelope. */
    @Column(name = "scenario_id", length = 32)
    private String scenarioId;

    @Column(name = "module_url", length = 255)
    private String moduleUrl;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "ack_http_status")
    private Integer ackHttpStatus;

    @Column(name = "ack_body", length = 8000)
    private String ackBody;

    @Column(name = "callback_service_id", length = 64)
    private String callbackServiceId;

    @Column(name = "callback_status", length = 32)
    private String callbackStatus;

    @Column(name = "callback_comment", length = 500)
    private String callbackComment;

    @Column(name = "callback_at")
    private Instant callbackAt;

    /**
     * The api-contract §4 application we sent, as JSON — what {@code GET
     * /api/v1/applications/{id}} reads back.
     *
     * <p>The envelope's {@code application} field, not the whole envelope: that is the object the
     * real orchestrator serves from the same URL, and keeping the two identical is the only reason
     * reading it here is worth anything. Null on an unsolicited row (nobody dispatched it) and on
     * an envelope that carried no application.</p>
     */
    @Column(name = "application_json", length = APPLICATION_JSON_MAX)
    private String applicationJson;

    protected Exchange() {
        // JPA
    }

    /** A dispatch we are about to make. */
    public static Exchange dispatched(String applicationId, String correlationId,
                                      String scenarioId, String moduleUrl,
                                      String applicationJson) {
        Exchange exchange = new Exchange();
        exchange.applicationId = applicationId;
        exchange.correlationId = correlationId;
        exchange.scenarioId = scenarioId;
        exchange.moduleUrl = moduleUrl;
        exchange.applicationJson = applicationJson;
        exchange.sentAt = Instant.now();
        return exchange;
    }

    /**
     * A callback that matched nothing we sent. Kept and shown rather than dropped.
     *
     * <p>It is marked by what it lacks: no {@code sentAt}, because nobody dispatched it. There is
     * no stored flag to contradict.</p>
     */
    public static Exchange unsolicited(String applicationId) {
        Exchange exchange = new Exchange();
        exchange.applicationId = applicationId;
        return exchange;
    }

    public void recordAck(int httpStatus, String body) {
        this.ackHttpStatus = httpStatus;
        this.ackBody = truncate(body, 8000);
    }

    public void recordCallback(String serviceId, String status, String comment) {
        this.callbackServiceId = serviceId;
        this.callbackStatus = status;
        this.callbackComment = truncate(comment, 500);
        this.callbackAt = Instant.now();
    }

    /** True once the module has answered — the question this whole box exists to answer. */
    public boolean hasCallback() {
        return callbackAt != null;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }

    public Long getId() {
        return id;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public String getModuleUrl() {
        return moduleUrl;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Integer getAckHttpStatus() {
        return ackHttpStatus;
    }

    public String getAckBody() {
        return ackBody;
    }

    public String getCallbackServiceId() {
        return callbackServiceId;
    }

    public String getCallbackStatus() {
        return callbackStatus;
    }

    public String getCallbackComment() {
        return callbackComment;
    }

    public Instant getCallbackAt() {
        return callbackAt;
    }

    public String getApplicationJson() {
        return applicationJson;
    }

    /**
     * Derived, not stored: only a callback nobody asked for can have no dispatch timestamp.
     * Keeping it computed means the flag and the row can never disagree.
     */
    public boolean isUnsolicited() {
        return sentAt == null;
    }
}
