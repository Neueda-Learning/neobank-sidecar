package com.neobank.sidecar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole box on H2: wiring, Liquibase, {@code ddl-auto=validate}, and the two halves of the
 * contract meeting in one row.
 *
 * <p>Assertions filter by application id rather than counting rows — the H2 database is shared
 * across every test in this context, so a size assertion would depend on execution order.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SidecarApplicationTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void contextLoadsAndTheSchemaMatchesTheEntities() {
        // Reaching here means Liquibase ran and Hibernate validated `exchange` against it.
    }

    @Test
    void healthIsUpAndDatabaseBacked() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"));
    }

    @Test
    void infoNamesBothAddressesThatMatter() throws Exception {
        mvc.perform(get("/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("neobank-sidecar"))
                .andExpect(jsonPath("$.moduleUrl").value("http://localhost:9"))
                .andExpect(jsonPath("$.modulePath").value("/api/v1/applications"))
                .andExpect(jsonPath("$.callbackPath").value("/api/v1/callbacks"))
                .andExpect(jsonPath("$.scenarioCount").value(org.hamcrest.Matchers.greaterThan(20)));
    }

    @Test
    void theScenarioLibraryIsServedWithEveryEnvelopeAttached() throws Exception {
        mvc.perform(get("/api/v1/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(org.hamcrest.Matchers.greaterThan(20)))
                .andExpect(jsonPath("$.scenarios[0].id").value("SIM-01"))
                .andExpect(jsonPath("$.scenarios[0].request.command").value("process-application"))
                .andExpect(jsonPath("$.scenarios[0].request.application.applicant.fullName")
                        .value("Maria Nowak"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void aDispatchAndItsCallbackMeetInOneRow() throws Exception {
        // The module is a dead port here, so the ack is 0 — the dispatch half is still recorded,
        // which is the behaviour that matters: a send you cannot see is worse than a failed one.
        mvc.perform(post("/api/v1/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"envelope":{"applicationId":"IT-MEET","correlationId":"c-1",
                                 "command":"process-application","application":{"channel":"WEB"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("IT-MEET"))
                .andExpect(jsonPath("$.ackHttpStatus").value(0))
                .andExpect(jsonPath("$.callbackStatus").doesNotExist());

        mvc.perform(post("/api/v1/callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"IT-MEET","serviceId":"attempt01",
                                 "status":"ACCEPTED","comment":"decided"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        mvc.perform(get("/api/v1/dispatches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.applicationId == 'IT-MEET')].callbackStatus")
                        .value(org.hamcrest.Matchers.hasItem("ACCEPTED")))
                .andExpect(jsonPath("$[?(@.applicationId == 'IT-MEET')].callbackComment")
                        .value(org.hamcrest.Matchers.hasItem("decided")))
                .andExpect(jsonPath("$[?(@.applicationId == 'IT-MEET')].unsolicited")
                        .value(org.hamcrest.Matchers.hasItem(false)));
    }

    @Test
    void aCallbackForSomethingNeverSentIsKeptAndFlagged() throws Exception {
        mvc.perform(post("/api/v1/callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"IT-GHOST","serviceId":"attempt07",
                                 "status":"REFERRED","comment":"who asked?"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/dispatches"))
                .andExpect(jsonPath("$[?(@.applicationId == 'IT-GHOST')].unsolicited")
                        .value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.applicationId == 'IT-GHOST')].callbackStatus")
                        .value(org.hamcrest.Matchers.hasItem("REFERRED")));
    }

    @Test
    void namingAScenarioThatDoesNotExistIsA400WithAReadableMessage() throws Exception {
        mvc.perform(post("/api/v1/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"SIM-99\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SIM-99")));
    }

    @Test
    void theLogCanBeCleared() throws Exception {
        mvc.perform(post("/api/v1/callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"IT-CLEAR","serviceId":"attempt01","status":"ACCEPTED"}
                                """))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/v1/dispatches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true));

        mvc.perform(get("/api/v1/dispatches"))
                .andExpect(jsonPath("$").isEmpty());
    }
}
