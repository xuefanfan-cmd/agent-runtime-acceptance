package com.huawei.ascend.sit.fixtures.task_degradation_gate;

import com.huawei.ascend.sit.judge.AcceptanceExtension;
import com.huawei.ascend.sit.judge.AcceptanceReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FEAT-112 scope against the testplan: which scenarios have no executable test (and why), which tests carry only
 * part of a requirement, and the per-scenario report of a run. The testplan file is the mirror pinned in
 * {@code feat112/README.md}.
 */
public final class Feat112Coverage {
    private Feat112Coverage() {}

    /** Scenarios in the testplan with no executable SIT test. */
    public static final Map<String, String> UNCOVERED = Map.of(
            "FEAT-112-A1", "仓内除 DA 外没有装配降级门的可运行宿主（ext 无自动装配），SIT 无法构造；待特性设计确认 ext 单独装配是否另行验收",
            "FEAT-112-E17", "公开面造不出“无错误码、无类目、非 core”的任务失败，成员失败到任务失败的因果未建立；待特性设计确认",
            "FEAT-112-E18", "“护栏拦截导致任务失败”的确定路径尚未建立；待特性设计确认");

    /** Covered scenarios whose tests carry only part of the requirement; the remainder is a registered gap. */
    public static final Map<String, String> PARTIAL = Map.of(
            "FEAT-112-A2", "键缺席时如何处理特性档未规定，待特性设计确认；确认前结论只说明实现行为",
            "FEAT-112-C4", "故障代际值不在诊断面与日志中（特性不可观测 Issue 待提，见 feat112/issues-unobservable-20261117.md），“递增代际”的效果由 C5、C8 验证",
            "FEAT-112-E3", "出厂配置下 business_failure 落入默认 ask，探针先停在待确认；该工具是否需要用户确认待特性设计确认",
            "FEAT-112-E7", "取消请求到达瞬间的窗口在一次同步调用内，对外与日志均不可观测（特性不可观测 Issue 待提，见 feat112/issues-unobservable-20261117.md）",
            "FEAT-112-I1", "启动记录的正式载体待 DA L2 明确；监控保留原故障告警属部署验收，不在本场景");

    /** A section 5 scenario row: {@code {domain}{seq}[-{variant}][.{testpoint}]} (testplan §5 ID rule). */
    private static final Pattern ROW = Pattern.compile("^\\| ([A-K]\\d+(?:-[a-z][a-z0-9]*)?(?:\\.\\d+)?) \\|");
    /** The retired-ID register closing section 5; its rows name old IDs, not scenarios. */
    private static final String RETIRED_REGISTER = "### 已撤销 ID";

    public static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom) && Files.readString(pom).contains("<artifactId>spring-ai-ascend-sit</artifactId>")) {
                    return dir;
                }
            } catch (IOException ignored) {
                // keep walking up
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("acceptance repository root not found above " + Path.of("").toAbsolutePath());
    }

    /**
     * The testplan the tests implement: the pending revision prepared for the docs repository when present
     * (README “待提交修订”), otherwise the mirror of the merged remote file. The run provenance records which one.
     */
    public static Path testplan() {
        Path revision = repositoryRoot().resolve("feat112/testplan-revision-20261112.revised.md");
        return Files.isRegularFile(revision) ? revision : repositoryRoot().resolve("feat112/FEAT-112-task-degradation-gate-testplan.md");
    }

    /** Full scenario IDs of testplan section 5, in document order. */
    public static List<String> testplanIds() throws IOException {
        List<String> ids = new ArrayList<>();
        boolean section5 = false;
        for (String line : Files.readAllLines(testplan(), StandardCharsets.UTF_8)) {
            if (line.startsWith("## 5. ")) section5 = true;
            else if (line.startsWith("## 6. ") || line.startsWith(RETIRED_REGISTER)) break;
            Matcher m = ROW.matcher(line);
            if (section5 && m.find() && !ids.contains(Feat112Contract.id(m.group(1)))) ids.add(Feat112Contract.id(m.group(1)));
        }
        return ids;
    }

    /** Where the fingerprints of the testplan text the tests were last reviewed against are pinned. */
    public static Path reviewedFingerprints() {
        return repositoryRoot().resolve("feat112/expectation-fingerprints.txt");
    }

    /**
     * Per scenario, a short SHA-256 of the scenario, precondition, step and expectation cells of testplan section 5
     * (whitespace-normalised). ID binding alone does not notice a changed expectation; this does.
     */
    public static Map<String, String> expectationFingerprints() throws IOException {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        boolean section5 = false;
        for (String line : Files.readAllLines(testplan(), StandardCharsets.UTF_8)) {
            if (line.startsWith("## 5. ")) section5 = true;
            else if (line.startsWith("## 6. ") || line.startsWith(RETIRED_REGISTER)) break;
            Matcher m = ROW.matcher(line);
            if (!section5 || !m.find()) continue;
            String[] cells = line.substring(1, line.lastIndexOf('|')).split("\\|", -1);
            if (cells.length < 8) continue;
            String text = String.join("\u0001", cells[3].strip(), cells[4].strip(), cells[5].strip(), cells[6].strip())
                    .replaceAll("\\s+", " ");
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
                fingerprints.put(Feat112Contract.id(m.group(1)), java.util.HexFormat.of().formatHex(digest).substring(0, 16));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return fingerprints;
    }

    /** Pinned fingerprints; empty when none have been reviewed yet. */
    public static Map<String, String> readReviewedFingerprints() throws IOException {
        Map<String, String> pinned = new LinkedHashMap<>();
        if (!Files.isRegularFile(reviewedFingerprints())) return pinned;
        for (String line : Files.readAllLines(reviewedFingerprints(), StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+");
            pinned.put(parts[0], parts[1]);
        }
        return pinned;
    }

    /** Records the current testplan text as reviewed: run only after checking the covering tests against it. */
    public static void writeReviewedFingerprints() throws IOException {
        StringBuilder out = new StringBuilder("# FEAT-112 testplan text the tests were reviewed against (scenario/precondition/step/expectation).\n"
                + "# A mismatch fails Feat112ArchitectureTest: review the covering tests, then regenerate with\n"
                + "#   feat112/tools/run-feat112.sh fingerprints\n"
                + "# testplan: " + repositoryRoot().relativize(testplan()).toString().replace('\\', '/') + "\n");
        expectationFingerprints().forEach((id, fp) -> out.append(id).append(' ').append(fp).append('\n'));
        Files.writeString(reviewedFingerprints(), out.toString(), StandardCharsets.UTF_8);
        System.out.print(out);
    }

    /** Writes {@code summary.md} for a run directory (default: this JVM's run) and prints it. */
    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("--write-fingerprints")) {
            writeReviewedFingerprints();
            return;
        }
        Path run = args.length > 0 ? Path.of(args[0]) : AcceptanceExtension.runDirectory();
        Map<String, String> notes = new LinkedHashMap<>();
        UNCOVERED.forEach((id, why) -> notes.put(id, "未承接：" + why));
        PARTIAL.forEach((id, why) -> notes.put(id, "部分承接：" + why));
        Map<String, AcceptanceReport.ScenarioSummary> results = AcceptanceReport.aggregate(run);
        String table = AcceptanceReport.markdown(results, testplanIds(), notes);
        String header = AcceptanceReport.header(run, results, testplanIds());
        Files.createDirectories(run);
        Files.writeString(run.resolve("summary.md"), "# FEAT-112 场景结论（" + run.getFileName() + "）\n\n" + header + "\n" + table,
                StandardCharsets.UTF_8);
        System.out.print(header + "\n" + table);
    }
}
