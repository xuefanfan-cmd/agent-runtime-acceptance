package com.huawei.ascend.sit.fixtures.task_degradation_gate;

import com.huawei.ascend.sit.judge.AcceptanceLayer;
import com.huawei.ascend.sit.judge.Covers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural rules of the FEAT-112 acceptance code. They hold the methodology in place: verdicts are derived from
 * recorded findings, every scenario traces to the testplan and every expectation to the feature document, judges
 * stay independent of execution, and deployment forms are declared rather than assumed.
 */
@Tag("selftest")
class Feat112ArchitectureTest {
    private static final Path SIT = Feat112Coverage.repositoryRoot().resolve("src/test/java/com/huawei/ascend/sit");
    private static final Path COMPONENT = SIT.resolve("cases/component/task_degradation_gate");
    private static final Path INTEGRATION = SIT.resolve("cases/integration/task_degradation_gate");
    private static final Path FIXTURES = SIT.resolve("fixtures/task_degradation_gate");

    private static List<Path> sources(Path... roots) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.getFileName().toString().equals("Feat112ArchitectureTest.java"))
                        .sorted().forEach(files::add);
            }
        }
        return files;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Class<?> classOf(Path source) throws ClassNotFoundException {
        String relative = SIT.relativize(source).toString().replace('\\', '/').replace('/', '.');
        return Class.forName("com.huawei.ascend.sit." + relative.substring(0, relative.length() - ".java".length()));
    }

    private static Map<Method, Covers> testMethods(Path root) throws Exception {
        Map<Method, Covers> methods = new LinkedHashMap<>();
        for (Path source : sources(root)) {
            for (Method m : classOf(source).getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class)) methods.put(m, m.getAnnotation(Covers.class));
            }
        }
        return methods;
    }

    /**
     * SIT only: every FEAT-112 test drives an owned DA process through its public surface, logs or persisted data.
     * Component-level tests that load product classes in the test JVM and feed them hand-made facts are not
     * acceptance; what cannot be observed from outside is registered as an unobservable-feature gap instead.
     */
    @Test
    void everyTestIsSystemLevelAcceptance() throws Exception {
        assertFalse(sources(INTEGRATION).isEmpty());
        assertEquals(List.of(), sources(COMPONENT), "FEAT-112 has no component-level acceptance (SIT only)");
        for (var test : testMethods(INTEGRATION).entrySet()) {
            assertNotNull(test.getValue(), test.getKey() + " has no @Covers");
            assertEquals(AcceptanceLayer.PUBLIC_SURFACE, test.getValue().layer(), test.getKey() + " layer");
        }
        for (Path source : sources(INTEGRATION, FIXTURES)) {
            if (source.getFileName().toString().endsWith("Test.java") && source.startsWith(FIXTURES)) continue;
            assertFalse(read(source).contains("AcceptanceLayer.COMPONENT"), source.getFileName() + " declares component-level acceptance");
        }
        assertFalse(Files.exists(SIT.resolve("cases/e2e/task_degradation_gate")),
                "FEAT-112 has no scenario meeting the repository's no-mock e2e definition");
    }

    @Test
    void everyTestplanScenarioIsCoveredOrRegistered() throws Exception {
        List<String> testplan = Feat112Coverage.testplanIds();
        assertEquals(84, testplan.size(), "testplan section 5 scenario count");
        Set<String> covered = new HashSet<>();
        for (Path root : List.of(COMPONENT, INTEGRATION)) {
            testMethods(root).values().forEach(c -> covered.addAll(List.of(c.value())));
        }
        Set<String> unknown = new HashSet<>(covered);
        unknown.removeAll(testplan);
        assertEquals(Set.of(), unknown, "@Covers IDs that are not testplan scenarios");
        Set<String> missing = new HashSet<>(testplan);
        missing.removeAll(covered);
        missing.removeAll(Feat112Coverage.UNCOVERED.keySet());
        assertEquals(Set.of(), missing, "testplan scenarios with neither a test nor a registered gap");
        Set<String> contradictory = new HashSet<>(covered);
        contradictory.retainAll(Feat112Coverage.UNCOVERED.keySet());
        assertEquals(Set.of(), contradictory, "scenarios both covered and registered as uncovered");
        assertTrue(covered.containsAll(Feat112Coverage.PARTIAL.keySet()), "partial coverage notes need a covering test");
    }

    /**
     * ID binding does not notice a changed expectation (C7 and E3 drifted with every ID still matching). The tests are
     * pinned to the fingerprint of the testplan text they were reviewed against; a changed row fails here until the
     * covering tests are reviewed and the fingerprints regenerated.
     */
    @Test
    void testsWereReviewedAgainstTheCurrentTestplanText() throws Exception {
        Map<String, String> current = Feat112Coverage.expectationFingerprints();
        Map<String, String> reviewed = Feat112Coverage.readReviewedFingerprints();
        assertEquals(Feat112Coverage.testplanIds().size(), current.size(), "every section 5 row yields a fingerprint");
        List<String> drifted = new ArrayList<>();
        current.forEach((id, fp) -> {
            if (!fp.equals(reviewed.get(id))) drifted.add(id + (reviewed.containsKey(id) ? " (text changed)" : " (new)"));
        });
        reviewed.keySet().stream().filter(id -> !current.containsKey(id)).forEach(id -> drifted.add(id + " (removed)"));
        assertEquals(List.of(), drifted, "testplan rows changed since the tests were last reviewed: review the covering tests, "
                + "then run feat112/tools/run-feat112.sh fingerprints");
    }

    @Test
    void casesJudgeOnlyThroughRecordedFindings() throws Exception {
        Pattern direct = Pattern.compile("\\bAssertions\\b|\\bAssumptions\\b|\\bassume(True|False|That)\\("
                + "|\\bassert(Equals|NotEquals|True|False|Null|NotNull|Same|Throws|All|InstanceOf)\\(|\\bfail\\(");
        for (Path source : sources(COMPONENT, INTEGRATION)) {
            assertFalse(direct.matcher(read(source)).find(), source.getFileName() + " asserts directly instead of recording findings");
        }
    }

    @Test
    void expectationsCiteOnlyThePinnedFeatureDocument() throws Exception {
        for (Path source : sources(COMPONENT, INTEGRATION, FIXTURES)) {
            String text = read(source);
            if (source.getFileName().toString().equals("Feat112Contract.java")) continue;
            if (source.getFileName().toString().endsWith("Test.java") && source.startsWith(FIXTURES)) continue;
            assertFalse(text.contains("new Rule(") || text.contains("new EvidenceChecks.Rule("),
                    source.getFileName() + " builds a rule without Feat112Contract");
            assertFalse(text.contains("testplan.md §") || text.contains("cases sections"),
                    source.getFileName() + " cites a derived document as an expectation's basis");
        }
        assertTrue(Files.isRegularFile(Feat112Coverage.repositoryRoot().resolve("feat112/baseline-05af9cc3/feature.md")),
                "pinned feature snapshot present");
    }

    @Test
    void dependenciesPointFromCasesToFixturesAndJudgesStayIndependent() throws Exception {
        for (Path source : sources(FIXTURES)) {
            String text = read(source);
            assertFalse(text.contains("com.huawei.ascend.sit.cases."), source.getFileName() + " depends on a case class");
        }
        for (Path source : sources(FIXTURES.resolve("judge"))) {
            String text = read(source);
            if (source.getFileName().toString().endsWith("Test.java")) continue;
            assertFalse(text.contains(".task_degradation_gate.drive") || text.contains(".task_degradation_gate.deploy"),
                    source.getFileName() + ": judges consume captured evidence only, never drivers or deployments");
        }
        for (Path source : sources(INTEGRATION)) {
            String text = read(source);
            assertFalse(text.contains("Class.forName") || text.contains("getDeclared") || text.contains("setAccessible"),
                    source.getFileName() + ": public-surface cases do not reach into product classes");
        }
        for (Path source : sources(COMPONENT)) {
            String text = read(source);
            assertFalse(text.contains("DaEvidenceClient") || text.contains("GateDriver") || text.contains("GateDeployment"),
                    source.getFileName() + ": component cases drive product classes, not a deployed process");
        }
    }

    @Test
    void deploymentFormsAreDeclaredNotAssumed() throws Exception {
        for (Path source : sources(COMPONENT, INTEGRATION, FIXTURES)) {
            String text = read(source);
            assertFalse(text.contains("System.getProperty(\"feat112.") || text.contains("feat112.sut-url")
                    || text.contains("GateDriver.remote"), source.getFileName() + " reads an externally assumed deployment");
        }
    }
}
