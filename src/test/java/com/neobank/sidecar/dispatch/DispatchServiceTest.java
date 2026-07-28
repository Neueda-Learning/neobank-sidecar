package com.neobank.sidecar.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.sidecar.SidecarDtos.ApplicationStatusUpdate;
import com.neobank.sidecar.SidecarDtos.DispatchRequest;
import com.neobank.sidecar.SidecarDtos.ExchangeView;
import com.neobank.sidecar.scenario.ScenarioLibrary;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

/** No Spring, no DB, no live module: the matching rules and the fresh-id rewrite on their own. */
class DispatchServiceTest {

    private ExchangeRepository exchanges;
    private ScenarioLibrary library;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        exchanges = mock(ExchangeRepository.class);
        library = mock(ScenarioLibrary.class);
        // A real client aimed at a dead port: the dispatch path is exercised for real and lands
        // in the "unreachable" branch, which is the branch a student meets most often.
        service = new DispatchService(exchanges, library, RestClient.create(), new ObjectMapper(),
                "http://localhost:9", "/api/v1/applications");
        when(exchanges.save(any(Exchange.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void anUnreachableModuleIsRecordedAsAResultNotSwallowed() {
        ExchangeView view = service.dispatch(new DispatchRequest(
                null, envelope("SIM-01"), null, false));

        assertThat(view.ackHttpStatus()).as("0 means the request never landed").isZero();
        assertThat(view.ackBody()).isNotBlank();
        assertThat(view.applicationId()).isEqualTo("SIM-01");
        assertThat(view.unsolicited()).isFalse();
    }

    @Test
    void aPerDispatchModuleUrlOverridesTheConfiguredOne() {
        ExchangeView view = service.dispatch(new DispatchRequest(
                null, envelope("SIM-01"), "http://localhost:10/", false));

        // Recorded without the trailing slash, or the path would double up the separator.
        assertThat(view.moduleUrl()).isEqualTo("http://localhost:10");
    }

    @Test
    void freshIdRewritesBothCopiesOfTheApplicationId() {
        ArgumentCaptor<Exchange> saved = ArgumentCaptor.forClass(Exchange.class);

        service.dispatch(new DispatchRequest(null, envelope("SIM-01"), null, true));

        verify(exchanges, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        String id = saved.getAllValues().get(0).getApplicationId();
        assertThat(id).startsWith("SIM-01-").isNotEqualTo("SIM-01");
    }

    @Test
    void aDispatchNeedsEitherAScenarioOrAnEnvelope() {
        assertThatThrownBy(() -> service.dispatch(new DispatchRequest(null, null, null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scenarioId or an envelope");
    }

    @Test
    void anUnknownScenarioIsARejectedRequestNotACrash() {
        when(library.envelopeOf("SIM-99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dispatch(new DispatchRequest("SIM-99", null, null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SIM-99");
    }

    @Test
    void aCallbackCompletesTheDispatchThatProvokedIt() {
        Exchange open = Exchange.dispatched("SIM-01", "corr-1", "SIM-01", "http://module", null);
        when(exchanges.findFirstByApplicationIdAndCallbackAtIsNullOrderByIdAsc("SIM-01"))
                .thenReturn(Optional.of(open));

        service.recordStatusUpdate("SIM-01",
                new ApplicationStatusUpdate("attempt01", "ACCEPTED", "rules passed"));

        assertThat(open.hasCallback()).isTrue();
        assertThat(open.getCallbackStatus()).isEqualTo("ACCEPTED");
        assertThat(open.getCallbackServiceId()).isEqualTo("attempt01");
        assertThat(open.getCallbackComment()).isEqualTo("rules passed");
        assertThat(open.isUnsolicited()).isFalse();
        verify(exchanges).save(open);
    }

    @Test
    void aCallbackMatchingNothingIsKeptAndFlagged() {
        when(exchanges.findFirstByApplicationIdAndCallbackAtIsNullOrderByIdAsc("GHOST"))
                .thenReturn(Optional.empty());
        ArgumentCaptor<Exchange> saved = ArgumentCaptor.forClass(Exchange.class);

        service.recordStatusUpdate("GHOST",
                new ApplicationStatusUpdate("attempt01", "ACCEPTED", "late"));

        verify(exchanges).save(saved.capture());
        Exchange row = saved.getValue();
        assertThat(row.isUnsolicited()).as("a misdirected callback must be visible, not dropped").isTrue();
        assertThat(row.getApplicationId()).isEqualTo("GHOST");
        assertThat(row.getCallbackStatus()).isEqualTo("ACCEPTED");
        assertThat(row.getSentAt()).as("nothing was ever sent for it").isNull();
    }

    private static Map<String, Object> envelope(String applicationId) {
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("applicationId", applicationId);
        application.put("channel", "MOBILE_APP");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("applicationId", applicationId);
        envelope.put("correlationId", "corr-" + applicationId);
        envelope.put("command", "process-application");
        envelope.put("application", application);
        return envelope;
    }
}
