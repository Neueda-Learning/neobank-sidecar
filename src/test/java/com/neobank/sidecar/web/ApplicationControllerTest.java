package com.neobank.sidecar.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.sidecar.SidecarDtos.ApplicationStatusUpdate;
import com.neobank.sidecar.dispatch.DispatchService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the status-update wire.
 *
 * <p>This endpoint is a copy of the real orchestrator's, and the copy only has value while it stays
 * exact. If someone widens it — makes a field optional, returns 201, renames {@code received},
 * starts honouring an application id in the body — then a module can satisfy the sidecar and fail
 * against the orchestrator, which is the one outcome this whole box exists to prevent.</p>
 */
@WebMvcTest(ApplicationController.class)
class ApplicationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DispatchService dispatches;

    @Test
    void acceptsTheThreeFieldUpdateAndAlwaysAnswers200() throws Exception {
        mvc.perform(put("/api/v1/applications/{id}", "SIM-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceId": "attempt01",
                                  "status": "ACCEPTED",
                                  "comment": "hello world from processApplication"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.applicationId").value("SIM-01"));

        // The id comes from the path, not the body — that is the whole point of the PUT.
        verify(dispatches).recordStatusUpdate("SIM-01", new ApplicationStatusUpdate(
                "attempt01", "ACCEPTED", "hello world from processApplication"));
    }

    @Test
    void commentIsOptionalButServiceIdAndStatusAreNot() throws Exception {
        mvc.perform(put("/api/v1/applications/{id}", "SIM-02")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"attempt01","status":"REJECTED"}
                                """))
                .andExpect(status().isOk());

        verify(dispatches).recordStatusUpdate("SIM-02",
                new ApplicationStatusUpdate("attempt01", "REJECTED", null));
    }

    @Test
    void anUpdateMissingItsStatusIsRejectedBeforeItIsRecorded() throws Exception {
        mvc.perform(put("/api/v1/applications/{id}", "SIM-03")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"attempt01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        verifyNoInteractions(dispatches);
    }

    @Test
    void anApplicationIdLeftInTheBodyIsIgnored() throws Exception {
        // A module still sending the old four-field shape must not break: the extra field is
        // unknown to the record, Boot ignores it, and the path stays authoritative.
        mvc.perform(put("/api/v1/applications/{id}", "SIM-04")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"SOMETHING-ELSE","serviceId":"attempt01",
                                 "status":"ACCEPTED","comment":"x"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("SIM-04"));

        verify(dispatches).recordStatusUpdate(eq("SIM-04"), any(ApplicationStatusUpdate.class));
    }

    @Test
    void anUnknownApplicationIsStillAccepted() throws Exception {
        // The orchestrator answers 200 to a report it cannot place, and so must this: a module left
        // retrying because its late report was refused is a worse failure than a stray row.
        mvc.perform(put("/api/v1/applications/{id}", "NEVER-SENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"serviceId":"attempt01","status":"ACCEPTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(dispatches).recordStatusUpdate(eq("NEVER-SENT"), any(ApplicationStatusUpdate.class));
    }

    // ---- GET: reading the application back ------------------------------------------------

    private static Map<String, Object> application(String id, String fullName) {
        return Map.of(
                "applicationId", id,
                "channel", "WEB",
                "applicant", Map.of("fullName", fullName, "dateOfBirth", "1996-04-11"),
                "product", Map.of("productCode", "CREDIT_CARD_PLATINUM"));
    }

    /**
     * The §4 application object, exactly as the orchestrator serves it. If this ever starts
     * answering the sidecar's own {@code ExchangeView} instead, a module would read applicant data
     * here that does not exist on the real thing.
     */
    @Test
    void getReturnsTheApplicationThatWasSent() throws Exception {
        when(dispatches.application("SIM-01"))
                .thenReturn(Optional.of(application("SIM-01", "Maria Nowak")));

        mvc.perform(get("/api/v1/applications/{id}", "SIM-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("SIM-01"))
                .andExpect(jsonPath("$.applicant.fullName").value("Maria Nowak"))
                .andExpect(jsonPath("$.product.productCode").value("CREDIT_CARD_PLATINUM"))
                // Not the exchange log's shape — that is the operator's view, not the contract's.
                .andExpect(jsonPath("$.ackHttpStatus").doesNotExist())
                .andExpect(jsonPath("$.callbackStatus").doesNotExist());
    }

    /** 404, not 200-with-nothing: "no application" must not read as "an applicant with no name". */
    @Test
    void anIdWeNeverSentIs404() throws Exception {
        when(dispatches.application("NEVER-SENT")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/applications/{id}", "NEVER-SENT"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nameSearchReturnsApplicationObjects() throws Exception {
        when(dispatches.applicationsByName("nowak"))
                .thenReturn(List.of(application("SIM-01", "Maria Nowak")));

        mvc.perform(get("/api/v1/applications").param("name", "nowak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].applicationId").value("SIM-01"))
                .andExpect(jsonPath("$[0].applicant.fullName").value("Maria Nowak"));
    }

    /**
     * The bare collection is deliberately unmapped. On the orchestrator it is the board — ten step
     * statuses per row — and this box has no journey to build one from. Answering something hollow
     * is how a module comes to depend on data that will not be there in the system stack.
     */
    @Test
    void thereIsNoBareCollection() throws Exception {
        mvc.perform(get("/api/v1/applications"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(dispatches);
    }
}
