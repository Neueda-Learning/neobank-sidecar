package com.neobank.sidecar.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.sidecar.SidecarDtos.CallbackBody;
import com.neobank.sidecar.dispatch.DispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the callback wire.
 *
 * <p>This endpoint is a copy of the real orchestrator's, and the copy only has value while it
 * stays exact. If someone widens it — makes a field optional, returns 201, renames
 * {@code received} — then a module can satisfy the sidecar and fail against the orchestrator,
 * which is the one outcome this whole box exists to prevent.</p>
 */
@WebMvcTest(CallbackController.class)
class CallbackControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DispatchService dispatches;

    @Test
    void acceptsTheFourFieldCallbackAndAlwaysAnswers200() throws Exception {
        mvc.perform(post("/api/v1/callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": "SIM-01",
                                  "serviceId": "attempt01",
                                  "status": "ACCEPTED",
                                  "comment": "all three rules passed"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true))
                .andExpect(jsonPath("$.applicationId").value("SIM-01"));

        verify(dispatches).recordCallback(new CallbackBody(
                "SIM-01", "attempt01", "ACCEPTED", "all three rules passed"));
    }

    @Test
    void commentIsOptionalButTheOtherThreeAreNot() throws Exception {
        mvc.perform(post("/api/v1/callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"SIM-02","serviceId":"attempt01","status":"REJECTED"}
                                """))
                .andExpect(status().isOk());

        verify(dispatches).recordCallback(new CallbackBody("SIM-02", "attempt01", "REJECTED", null));
    }

    @Test
    void aCallbackMissingItsStatusIsRejectedBeforeItIsRecorded() throws Exception {
        mvc.perform(post("/api/v1/callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"SIM-03","serviceId":"attempt01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        verifyNoInteractions(dispatches);
    }

    @Test
    void anUnknownApplicationIsStillAccepted() throws Exception {
        // The orchestrator answers 200 to a callback it cannot place, and so must this: a module
        // left retrying because its late callback was refused is a worse failure than a stray row.
        mvc.perform(post("/api/v1/callbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":"NEVER-SENT","serviceId":"attempt01","status":"ACCEPTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(dispatches).recordCallback(any(CallbackBody.class));
    }
}
