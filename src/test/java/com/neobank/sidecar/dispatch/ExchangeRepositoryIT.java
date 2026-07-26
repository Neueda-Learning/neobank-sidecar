package com.neobank.sidecar.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * INTEGRATION TEST (name ends in {@code IT} → runs on {@code ./mvnw verify}, needs Docker).
 *
 * <p>Testcontainers boots a real MySQL 8.4, Liquibase creates {@code exchange} on it, and
 * Hibernate runs {@code ddl-auto=validate} against that real DDL. It catches what H2 hides —
 * {@code TIMESTAMP}↔{@code Instant}, {@code BOOLEAN}, and the nullable columns that only matter
 * because half of every row starts empty.</p>
 *
 * <p>{@code disabledWithoutDocker = true}: with Docker stopped this is SKIPPED, not failed.</p>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional // roll back each test so methods don't leak rows into one another
class ExchangeRepositoryIT {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("sidecar_db");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    ExchangeRepository exchanges;

    @Test
    void schemaValidatesAndStartsEmpty() {
        // Reaching here proves Liquibase applied and ddl-auto=validate passed on real MySQL,
        // half-empty nullable columns and all.
        assertThat(exchanges.findAll()).isEmpty();
    }

    @Test
    void bothHalvesOfAnExchangeRoundTripThroughRealMysql() {
        Exchange sent = exchanges.saveAndFlush(
                Exchange.dispatched("APP-1", "COR-1", "SIM-01", "http://module:8080"));

        assertThat(sent.getId()).isNotNull();
        assertThat(sent.getSentAt()).isNotNull();
        assertThat(sent.hasCallback()).isFalse();
        assertThat(sent.isUnsolicited()).isFalse();

        sent.recordAck(202, "{\"status\":\"in-progress\"}");
        sent.recordCallback("attempt01", "ACCEPTED", "all three rules passed");
        exchanges.saveAndFlush(sent);

        Exchange reloaded = exchanges.findById(sent.getId()).orElseThrow();
        assertThat(reloaded.getAckHttpStatus()).isEqualTo(202);
        assertThat(reloaded.getAckBody()).isEqualTo("{\"status\":\"in-progress\"}");
        assertThat(reloaded.getCallbackStatus()).isEqualTo("ACCEPTED");
        assertThat(reloaded.getCallbackComment()).isEqualTo("all three rules passed");
        assertThat(reloaded.getCallbackAt()).isNotNull();
    }

    @Test
    void anUnansweredDispatchIsFoundOldestFirst() {
        // Two dispatches of the SAME application id — the SIM-25 case. Oldest-first is what makes
        // two arriving callbacks pair with the right rows instead of being shuffled.
        Exchange first = exchanges.saveAndFlush(
                Exchange.dispatched("APP-DUP", "COR-1", "SIM-01", "http://module:8080"));
        Exchange second = exchanges.saveAndFlush(
                Exchange.dispatched("APP-DUP", "COR-2", "SIM-25", "http://module:8080"));

        Exchange match = exchanges
                .findFirstByApplicationIdAndCallbackAtIsNullOrderByIdAsc("APP-DUP").orElseThrow();
        assertThat(match.getId()).isEqualTo(first.getId());

        match.recordCallback("attempt01", "ACCEPTED", "first");
        exchanges.saveAndFlush(match);

        // With the first one answered, the next callback finds the second.
        Exchange next = exchanges
                .findFirstByApplicationIdAndCallbackAtIsNullOrderByIdAsc("APP-DUP").orElseThrow();
        assertThat(next.getId()).isEqualTo(second.getId());
    }

    @Test
    void anUnsolicitedCallbackPersistsWithNoDispatchHalf() {
        Exchange ghost = Exchange.unsolicited("APP-GHOST");
        ghost.recordCallback("attempt03", "REJECTED", "nobody asked");
        Exchange saved = exchanges.saveAndFlush(ghost);

        Exchange reloaded = exchanges.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isUnsolicited()).isTrue();
        assertThat(reloaded.getSentAt()).isNull();
        assertThat(reloaded.getModuleUrl()).isNull();
        assertThat(reloaded.getAckHttpStatus()).isNull();
        assertThat(reloaded.getCallbackStatus()).isEqualTo("REJECTED");
    }
}
