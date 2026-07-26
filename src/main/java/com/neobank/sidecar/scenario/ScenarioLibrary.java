package com.neobank.sidecar.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The library of applications the orchestrator could send you.
 *
 * <p>{@code scenarios/index.json} is the catalogue — what each scenario is for, which of the ten
 * modules care about it, the reason codes it should provoke, and the arithmetic behind any
 * boundary it sits on. One file per scenario holds the exact envelope. Every scenario is SIM-01
 * with a single field changed; see this project's README for the whole table and the planted
 * conventions.</p>
 *
 * <p><b>Named files, not a wildcard.</b> Every scenario is read by name from {@code index.json}.
 * Classpath wildcard scanning behaves differently inside a Boot fat jar than in an IDE, and a
 * corpus that silently shrinks when you containerise is a bad hour. The cost — adding a scenario
 * means adding a row to {@code index.json} — is where its title and expected codes belong
 * anyway.</p>
 *
 * <p>A scenario whose file is missing or unparseable is <em>not</em> fatal: it is logged and
 * served with an {@code error} field, so one broken JSON file still leaves a running box and a
 * visible reason. {@code ScenarioLibraryTest} fails if any scenario shipped here is in that
 * state.</p>
 */
@Component
public class ScenarioLibrary {

    static final String ROOT = "scenarios/";
    static final String INDEX = ROOT + "index.json";

    private static final Logger log = LoggerFactory.getLogger(ScenarioLibrary.class);

    /**
     * Dates that must stay relative to today: {@code {{today}}}, {@code {{today-18y}}},
     * {@code {{today-18y+1d}}}, {@code {{today-18y-1d}}}. Each resolves to an ISO date.
     *
     * <p>This exists because the corpus previously carried a fixed {@code anchorDate}, and the
     * day after it was written the "one day short of 18" scenario turned 18 — so the failing
     * side of an age boundary silently started testing the passing side. A corpus that rots
     * quietly is worse than no corpus. Tokens are substituted in the raw JSON text before
     * parsing, so they work wherever a date appears, including inside a timestamp
     * ({@code "{{today}}T09:14:00Z"}).</p>
     *
     * <p>Only genuinely relative dates should use them. A document that expired in 2025 stays
     * expired forever and wants a literal date — hardcoding it is not a bug there.</p>
     */
    private static final Pattern DATE_TOKEN =
            Pattern.compile("\\{\\{today(?:-(\\d+)y)?(?:([+-])(\\d+)d)?}}");

    private final ObjectMapper json;
    private final String overlayDir;
    private final Map<String, Object> catalogue;

    public ScenarioLibrary(ObjectMapper json,
                           @Value("${sidecar.scenarios-dir:}") String overlayDir) {
        this.json = json;
        this.overlayDir = overlayDir;
        this.catalogue = load();
    }

    /** The whole catalogue: index metadata, with each scenario's envelope attached. */
    public Map<String, Object> catalogue() {
        return catalogue;
    }

    /** One scenario's envelope by scenario id (e.g. {@code SIM-01}), for dispatching it. */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> envelopeOf(String scenarioId) {
        return scenarios().stream()
                .filter(s -> scenarioId.equals(String.valueOf(s.get("id"))))
                .map(s -> (Map<String, Object>) s.get("request"))
                .filter(java.util.Objects::nonNull)
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> scenarios() {
        return (List<Map<String, Object>>) catalogue.getOrDefault("scenarios", List.of());
    }

    /** Substitute the relative-date tokens. Package-private so a test can pin the arithmetic. */
    static String resolveDates(String raw, LocalDate today) {
        Matcher matcher = DATE_TOKEN.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            LocalDate date = matcher.group(1) == null
                    ? today
                    : today.minusYears(Long.parseLong(matcher.group(1)));
            if (matcher.group(2) != null) {
                long days = Long.parseLong(matcher.group(3));
                date = "+".equals(matcher.group(2)) ? date.plusDays(days) : date.minusDays(days);
            }
            matcher.appendReplacement(out, date.toString());
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        LocalDate today = LocalDate.now();

        Map<String, Object> index;
        try {
            index = json.readValue(resolveDates(readClasspath(INDEX), today), Map.class);
        } catch (Exception e) {
            log.error("Scenario index {} could not be read: {}", INDEX, e.toString());
            return Map.of("scenarios", List.of(), "count", 0,
                    "error", "cannot read " + INDEX + ": " + e);
        }

        Map<String, String> overlay = readOverlay();

        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) index.getOrDefault("scenarios", List.of());
        List<Map<String, Object>> loaded = new ArrayList<>(entries.size());
        for (Map<String, Object> entry : entries) {
            Map<String, Object> scenario = new LinkedHashMap<>(entry);
            String file = String.valueOf(entry.get("file"));
            try {
                String raw = overlay.containsKey(file) ? overlay.remove(file) : readClasspath(ROOT + file);
                scenario.put("request", json.readValue(resolveDates(raw, today), Map.class));
            } catch (Exception e) {
                // Loud, but never fatal — one broken fixture must not stop the box.
                log.warn("Scenario {} could not be read: {}", file, e.toString());
                scenario.put("request", null);
                scenario.put("error", e.toString());
            }
            // unmodifiableMap, not Map.copyOf: null values are legitimate here (the invalid-envelope
            // scenario has no applicationId on purpose) and Map.copyOf rejects them.
            loaded.add(Collections.unmodifiableMap(scenario));
        }

        // Whatever is left in the overlay is a file the baked-in index does not know about, so
        // it is appended rather than dropped. Named after its file, because deriving a title
        // from a filename is guesswork and a wrong title is worse than an honest one.
        overlay.forEach((file, raw) -> {
            Map<String, Object> scenario = new LinkedHashMap<>();
            String id = file.replaceFirst("\\.json$", "");
            scenario.put("id", id);
            scenario.put("file", file);
            scenario.put("title", "overlay: " + id);
            scenario.put("trait", "Supplied from SCENARIOS_DIR, not part of the shipped corpus.");
            try {
                scenario.put("request", json.readValue(resolveDates(raw, today), Map.class));
            } catch (Exception e) {
                log.warn("Overlay scenario {} could not be read: {}", file, e.toString());
                scenario.put("request", null);
                scenario.put("error", e.toString());
            }
            loaded.add(Collections.unmodifiableMap(scenario));
        });

        Map<String, Object> result = new LinkedHashMap<>(index);
        result.put("scenarios", List.copyOf(loaded));
        result.put("count", loaded.size());
        log.info("Scenario library loaded: {} applications{}", loaded.size(),
                overlayDir == null || overlayDir.isBlank() ? "" : " (overlay: " + overlayDir + ")");
        return Collections.unmodifiableMap(result);
    }

    private static String readClasspath(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Extra {@code *.json} from a mounted directory, keyed by filename. A published image cannot
     * have its baked-in corpus edited, so this is the only way a team can add its own
     * applications; a same-named file replaces the shipped one.
     */
    private Map<String, String> readOverlay() {
        if (overlayDir == null || overlayDir.isBlank()) {
            return new LinkedHashMap<>();
        }
        Path dir = Path.of(overlayDir);
        if (!Files.isDirectory(dir)) {
            log.warn("SCENARIOS_DIR {} is not a directory — ignoring it", overlayDir);
            return new LinkedHashMap<>();
        }
        Map<String, String> found = new LinkedHashMap<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".json") && !name.equals("index.json")) {
                    found.put(name, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            log.warn("SCENARIOS_DIR {} could not be listed: {}", overlayDir, e.toString());
        }
        if (!found.isEmpty()) {
            log.info("Scenario overlay: {} file(s) from {}", found.size(), overlayDir);
        }
        return found;
    }
}
