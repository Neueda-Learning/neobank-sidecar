package com.neobank.sidecar.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Guards the corpus itself.
 *
 * <p>Ten teams read these files, and every one of them trusts the index to describe what each
 * scenario does. A broken file, a duplicate id, an invented reason code or an index that has
 * drifted from the folder is a support call from all ten at once — so it fails here instead.</p>
 *
 * <p>The directory-scanning test is only safe in this position: tests run against exploded
 * {@code target/classes}, never a fat jar. The production loader deliberately does not scan.</p>
 */
class ScenarioLibraryTest {

    private static Map<String, Object> catalogue;
    private static List<Map<String, Object>> scenarios;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadOnce() {
        catalogue = new ScenarioLibrary(new ObjectMapper(), "").catalogue();
        scenarios = (List<Map<String, Object>>) catalogue.get("scenarios");
    }

    @Test
    void loadsEveryScenarioInTheIndex() {
        assertThat(scenarios).hasSizeGreaterThanOrEqualTo(20);
        assertThat(catalogue.get("count")).isEqualTo(scenarios.size());

        List<String> broken = scenarios.stream()
                .filter(s -> s.get("error") != null)
                .map(s -> s.get("file") + ": " + s.get("error"))
                .toList();
        assertThat(broken).as("scenarios that failed to parse").isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyScenarioIsASendableEnvelope() {
        for (Map<String, Object> scenario : scenarios) {
            String id = String.valueOf(scenario.get("id"));
            Map<String, Object> request = (Map<String, Object>) scenario.get("request");

            assertThat(request).as("%s has a request", id).isNotNull();
            assertThat(request.get("command")).as("%s command", id).isNotNull();
            assertThat(request.get("application")).as("%s application", id).isNotNull();
            assertThat(scenario.get("title")).as("%s title", id).isNotNull();
            assertThat(scenario.get("trait")).as("%s trait", id).isNotNull();

            // The envelope's applicationId is what the index promises, and it is present on
            // everything except the one scenario that exists to be rejected with a 400.
            Integer expectHttp = (Integer) scenario.get("expectHttp");
            if (expectHttp != null && expectHttp == 202) {
                assertThat(request.get("applicationId"))
                        .as("%s applicationId", id)
                        .isEqualTo(scenario.get("applicationId"));
                assertThat(request.get("applicationId")).as("%s applicationId", id).isNotNull();
            }
        }
    }

    @Test
    void scenarioIdsAndFilenamesAreUnique() {
        assertThat(scenarios.stream().map(s -> s.get("id")).collect(Collectors.toSet()))
                .hasSize(scenarios.size());
        assertThat(scenarios.stream().map(s -> s.get("file")).collect(Collectors.toSet()))
                .hasSize(scenarios.size());
    }

    @Test
    void reasonCodesUseTheLockedRegistryPrefixes() {
        // api-contract.md §4: the registry is closed. Prefixes are the cheap half of that check
        // and catch the common slip — inventing a code for your own domain.
        Set<String> prefixes = new HashSet<>(Arrays.asList(
                "VER_", "POL_", "KYC_", "SCR_", "CRE_", "AGR_", "ACC_", "CRD_"));
        for (Map<String, Object> scenario : scenarios) {
            @SuppressWarnings("unchecked")
            List<String> codes = (List<String>) scenario.getOrDefault("reasonCodes", List.of());
            for (String code : codes) {
                assertThat(prefixes.stream().anyMatch(code::startsWith))
                        .as("%s reason code %s", scenario.get("id"), code)
                        .isTrue();
            }
        }
    }

    @Test
    void noScenarioFileIsMissingFromTheIndex() throws Exception {
        Resource[] onDisk = new PathMatchingResourcePatternResolver()
                .getResources("classpath:" + ScenarioLibrary.ROOT + "*.json");

        List<String> files = new ArrayList<>();
        for (Resource resource : onDisk) {
            String name = resource.getFilename();
            if (name != null && !name.equals("index.json")) {
                files.add(name);
            }
        }
        Set<Object> indexed = scenarios.stream().map(s -> s.get("file")).collect(Collectors.toSet());

        assertThat(files)
                .as("every .json in %s must have a row in index.json, or nothing will send it",
                        ScenarioLibrary.ROOT)
                .allMatch(indexed::contains);
        assertThat(files).hasSize(scenarios.size());
    }

    // ------------------------------------------------------------------ the date tokens

    @Test
    void resolvesRelativeDateTokens() {
        LocalDate today = LocalDate.of(2026, 7, 26);

        assertThat(ScenarioLibrary.resolveDates("{{today}}", today)).isEqualTo("2026-07-26");
        assertThat(ScenarioLibrary.resolveDates("{{today-18y}}", today)).isEqualTo("2008-07-26");
        assertThat(ScenarioLibrary.resolveDates("{{today-18y+1d}}", today)).isEqualTo("2008-07-27");
        assertThat(ScenarioLibrary.resolveDates("{{today-18y-1d}}", today)).isEqualTo("2008-07-25");
        // Inside a timestamp, and more than one per string.
        assertThat(ScenarioLibrary.resolveDates("\"{{today}}T09:14:00Z\" {{today-1y}}", today))
                .isEqualTo("\"2026-07-26T09:14:00Z\" 2025-07-26");
        // Anything that is not a token is left exactly alone.
        assertThat(ScenarioLibrary.resolveDates("2025-11-30", today)).isEqualTo("2025-11-30");
    }

    /**
     * The reason the tokens exist. These two scenarios are the two sides of the 18-year age
     * boundary, and they used to be fixed dates — so the day after the corpus was written, "one
     * day short of 18" turned 18 and silently started testing the passing side. Whatever day
     * this test runs, one must be exactly 18 and the other must not be.
     */
    @Test
    void theAgeBoundaryScenariosCannotAgeOut() {
        LocalDate today = LocalDate.now();

        LocalDate exactly18 = LocalDate.parse(dateOfBirth("SIM-03"));
        LocalDate oneDayShort = LocalDate.parse(dateOfBirth("SIM-04"));

        assertThat(ChronoUnit.YEARS.between(exactly18, today))
                .as("SIM-03 must be exactly 18 today, not %s", exactly18)
                .isEqualTo(18);
        assertThat(ChronoUnit.YEARS.between(oneDayShort, today))
                .as("SIM-04 must still be 17 today, not %s", oneDayShort)
                .isEqualTo(17);
        assertThat(oneDayShort).isEqualTo(exactly18.plusDays(1));
    }

    @SuppressWarnings("unchecked")
    private static String dateOfBirth(String scenarioId) {
        Map<String, Object> request = scenarios.stream()
                .filter(s -> scenarioId.equals(s.get("id")))
                .map(s -> (Map<String, Object>) s.get("request"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no scenario " + scenarioId));
        Map<String, Object> application = (Map<String, Object>) request.get("application");
        Map<String, Object> applicant = (Map<String, Object>) application.get("applicant");
        return String.valueOf(applicant.get("dateOfBirth"));
    }
}
