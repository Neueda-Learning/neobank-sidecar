package com.neobank.sidecar;

import com.neobank.sidecar.config.SchemaHint;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The mock orchestrator box.
 *
 * <p>It does two things, and deliberately nothing else: it POSTs an application envelope at your
 * module's {@code /api/v1/applications}, and it receives your module's callback on
 * {@code PUT /api/v1/applications/{applicationId}}. Both halves land in one row of one table and
 * are shown on one page.
 *
 * <p>What it is <em>not</em>: a journey. The real orchestrator sequences ten modules, times them
 * out and owns an overall outcome ({@code SagaEngine} in attempt-b00). This box has no state
 * machine, no ordering and no retry, because every one of those is a second implementation of
 * something that already exists — and a mock that drifts from the thing it mocks is worse than
 * no mock. Its value is narrow and real: the contract is two-way, and until something answers on
 * {@code /api/v1/applications/{applicationId}} you can only ever see half of it.</p>
 */
@SpringBootApplication
public class SidecarApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SidecarApplication.class);
        // Registered here rather than as a @Component: it has to be listening before the context
        // exists, since the failure it explains happens while the context is starting.
        app.addListeners(new SchemaHint());
        app.run(args);
    }
}
