package com.neobank.sidecar.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** The one infrastructure bean: the HTTP client that dispatches to the module under test. */
@Configuration
public class AppConfig {

    /**
     * Short timeouts on purpose. A module is supposed to return its {@code 202} within
     * milliseconds — it decides afterwards, off-thread. Left at the default (infinite), a
     * student's half-started backend would hang the UI's send button with no explanation
     * instead of reporting a timeout in the log.
     */
    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(factory).build();
    }
}
