package com.huawei.ascend.sit.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.ascend.sit.judge.EvidenceChecks.Outcome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Per-scenario summary of one run, built only from the result records each test execution wrote. A scenario
 * carried by several executions takes the most severe outcome; a scenario with no record is reported as not
 * executed rather than silently missing.
 */
public final class AcceptanceReport {
    private AcceptanceReport() {}

    public record ScenarioSummary(String id, Outcome outcome, String reason, String detail, List<String> tests,
                                  List<String> artifacts) {}

    public static Map<String, ScenarioSummary> aggregate(Path runDirectory) throws IOException {
        ObjectMapper json = new ObjectMapper();
        Map<String, ScenarioSummary> byId = new TreeMap<>();
        if (!Files.isDirectory(runDirectory)) return byId;
        List<Path> files;
        try (Stream<Path> listing = Files.list(runDirectory)) {
            files = listing.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            JsonNode root = json.readTree(file.toFile());
            String test = root.path("test").asText(file.getFileName().toString());
            List<String> artifacts = new ArrayList<>();
            for (JsonNode a : root.path("artifacts")) {
                artifacts.add(a.path("role").asText() + " " + a.path("coordinates").asText() + " sha256="
                        + (a.path("sha256").isNull() ? "unidentified(" + a.path("note").asText() + ")" : a.path("sha256").asText()));
            }
            for (JsonNode s : root.path("scenarios")) {
                String id = s.path("id").asText();
                Outcome outcome = Outcome.valueOf(s.path("outcome").asText());
                ScenarioSummary previous = byId.get(id);
                List<String> tests = new ArrayList<>(previous == null ? List.of() : previous.tests());
                tests.add(test);
                List<String> combinedArtifacts = new ArrayList<>(previous == null ? List.of() : previous.artifacts());
                artifacts.stream().filter(a -> !combinedArtifacts.contains(a)).forEach(combinedArtifacts::add);
                boolean replace = previous == null || severity(outcome) > severity(previous.outcome());
                byId.put(id, replace
                        ? new ScenarioSummary(id, outcome, s.path("reason").asText(), s.path("detail").asText(), tests, combinedArtifacts)
                        : new ScenarioSummary(id, previous.outcome(), previous.reason(), previous.detail(), tests, combinedArtifacts));
            }
        }
        return byId;
    }

    static int severity(Outcome outcome) {
        return switch (outcome) {
            case EXCLUDED -> 0;
            case UNSUPPORTED -> 1;
            case PASS -> 2;
            case WARN -> 3;
            case NOT_OBSERVED -> 4;
            case FAIL -> 5;
        };
    }

    /**
     * @param expectedIds every scenario in scope, in display order
     * @param notes       scenario ID to a standing remark (for example a registered coverage gap)
     */
    public static String markdown(Map<String, ScenarioSummary> results, List<String> expectedIds, Map<String, String> notes) {
        StringBuilder out = new StringBuilder("| 场景 | 结论 | 原因 | 承接用例 | 说明 |\n|---|---|---|---|---|\n");
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : expectedIds) {
            ScenarioSummary s = results.get(id);
            String outcome = s == null ? "NOT_EXECUTED" : s.outcome().name();
            counts.merge(outcome, 1, Integer::sum);
            String detail = s == null ? notes.getOrDefault(id, "无执行记录") : join(s.detail(), notes.get(id));
            out.append("| ").append(id).append(" | ").append(outcome).append(" | ")
                    .append(s == null ? "" : s.reason()).append(" | ")
                    .append(s == null ? "" : String.join("<br>", s.tests())).append(" | ")
                    .append(detail.replace("|", "\\|").replace("\n", " ")).append(" |\n");
        }
        for (String unexpected : results.keySet()) {
            if (!expectedIds.contains(unexpected)) out.append("| ").append(unexpected).append(" | 不在范围内的场景 ID | | | |\n");
        }
        out.append("\n合计：").append(counts).append('\n');
        return out.toString();
    }

    private static String join(String a, String b) {
        return b == null || b.isBlank() ? a : a + "；" + b;
    }

    /**
     * Report header: what the verdicts below were judged against and how they add up. Provenance values that differ
     * between result records of the same run are all listed and flagged, because then the run mixed products, judges
     * or expectations and its totals must not be read as one measurement.
     */
    public static String header(Path runDirectory, Map<String, ScenarioSummary> results, List<String> expectedIds)
            throws IOException {
        ObjectMapper json = new ObjectMapper();
        Map<String, List<String>> provenance = new TreeMap<>();
        List<String> artifacts = new ArrayList<>();
        int files = 0;
        if (Files.isDirectory(runDirectory)) {
            List<Path> records;
            try (Stream<Path> listing = Files.list(runDirectory)) {
                records = listing.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            }
            for (Path file : records) {
                JsonNode root = json.readTree(file.toFile());
                files++;
                root.path("provenance").fields().forEachRemaining(e -> {
                    List<String> values = provenance.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                    if (!values.contains(e.getValue().asText())) values.add(e.getValue().asText());
                });
                for (JsonNode a : root.path("artifacts")) {
                    String line = a.path("role").asText() + "｜" + a.path("coordinates").asText() + "｜sha256 "
                            + (a.path("sha256").isNull() ? "未识别（" + a.path("note").asText() + "）" : a.path("sha256").asText())
                            + (a.path("sourceRevision").isNull() ? "" : "｜来源 " + a.path("sourceRevision").asText());
                    if (!artifacts.contains(line)) artifacts.add(line);
                }
            }
        }
        Map<String, Integer> byOutcome = new LinkedHashMap<>();
        Map<String, Integer> byReason = new TreeMap<>();
        int executed = 0;
        for (String id : expectedIds) {
            ScenarioSummary s = results.get(id);
            byOutcome.merge(s == null ? "NOT_EXECUTED" : s.outcome().name(), 1, Integer::sum);
            if (s != null) {
                executed++;
                if (s.outcome() != Outcome.PASS) byReason.merge(s.outcome().name() + "/" + s.reason(), 1, Integer::sum);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("- 运行：").append(runDirectory.getFileName()).append("；结果记录 ").append(files).append(" 份\n");
        out.append("- 口径：范围内场景 ").append(expectedIds.size()).append(" 个，有执行记录 ").append(executed)
                .append(" 个；按结论 ").append(byOutcome).append("；非 PASS 按原因 ").append(byReason).append('\n');
        if (provenance.isEmpty()) {
            out.append("- 来源：结果记录未声明（启动时未传 acceptance.provenance.*）\n");
        } else {
            out.append("- 来源：\n");
            provenance.forEach((k, v) -> out.append("  - ").append(k).append("：").append(String.join(" ／ ", v))
                    .append(v.size() > 1 ? "（⚠ 本次运行的记录不一致）" : "").append('\n'));
        }
        out.append("- 被测物：").append(artifacts.isEmpty() ? "结果记录未登记" : "").append('\n');
        artifacts.forEach(a -> out.append("  - ").append(a).append('\n'));
        return out.toString();
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("usage: AcceptanceReport <run-directory>");
        Map<String, ScenarioSummary> results = aggregate(Path.of(args[0]));
        System.out.print(markdown(results, List.copyOf(results.keySet()), Map.of()));
    }
}
