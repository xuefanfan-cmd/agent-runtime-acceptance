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
 * part of a requirement, and the per-scenario report of a run. The repo stores no testplan copy: the text is
 * fetched from the docs repository at the pinned commit by {@code scripts/fetch-expectations.sh}
 * (pins in {@code scripts/expectation-sources.env}).
 */
public final class Feat112Coverage {
    private Feat112Coverage() {}

    /** Scenarios in the testplan with no executable SIT test. */
    public static final Map<String, String> UNCOVERED = Map.of();

    /** Covered scenarios whose tests carry only part of the requirement; the remainder is a registered gap. */
    public static final Map<String, String> PARTIAL = Map.of(
            "FEAT-112-E3", "出厂配置下 business_failure 落入默认 ask，探针先停在待确认；该工具是否需要用户确认待特性设计确认",
            "FEAT-112-E7", "取消请求到达瞬间的窗口在一次同步调用内，对外与日志均不可观测（经开发核查接受关闭：落定语义=控制面终态事实链，E7 按调用级粒度验证，不另提 Issue）",
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
     * The testplan the tests implement, fetched from the docs repository at the pinned commit. The pinned commit
     * fixes the text, so a changed expectation is always a reviewable pin change, never a silent drift.
     */
    public static Path testplan() {
        Path fetched = repositoryRoot().resolve("target/expectations/FEAT-112-task-degradation-gate-testplan.md");
        if (!Files.isRegularFile(fetched)) {
            throw new IllegalStateException("expectation documents not fetched: run scripts/fetch-expectations.sh"
                    + " (pins: scripts/expectation-sources.env); expected " + fetched);
        }
        return fetched;
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

    /** Writes {@code summary.md} for a run directory (default: this JVM's run) and prints it. */
    public static void main(String[] args) throws IOException {
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
