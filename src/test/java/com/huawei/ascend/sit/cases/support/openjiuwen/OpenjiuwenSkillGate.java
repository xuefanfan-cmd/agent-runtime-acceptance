package com.huawei.ascend.sit.cases.support.openjiuwen;

import com.huawei.ascend.sit.lifecycle.ManagedSutInstance;
import com.huawei.ascend.sit.lifecycle.SutStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hard gates for OJ-11 hotel {@code skill} profile tests.
 */
public final class OpenjiuwenSkillGate {

    private static final String SKILL_PROFILE_MARKER = "profile is active: \"skill\"";
    private static final String SKILL_HUB_REGISTERED_MARKER = "Registered skill hub from";
    private static final String STABLE_SKILLS_DIR = ".travel-hotel-skills";
    private static final String READ_FILE_SUCCESS_MARKER = "End to read file";

    private OpenjiuwenSkillGate() {
    }

    /** OJ-11.C meta — hotel started with {@code skill} profile and registered skill hub. */
    public static void assertHotelUsesSkillProfile(SutStack stack) throws IOException {
        Path log = hotelStdoutLog(stack);
        String content = Files.readString(log, StandardCharsets.UTF_8);
        String runSlice = sliceSinceLast(content, SKILL_PROFILE_MARKER);

        assertThat(lastLineContaining(runSlice, SKILL_PROFILE_MARKER))
                .as("OJ-11.C hotel active profile skill (see %s)", log)
                .isNotNull();
        String registrationLine = lastLineContaining(runSlice, SKILL_HUB_REGISTERED_MARKER);
        assertThat(registrationLine)
                .as("OJ-11.C hotel skill hub registration (rebuild agent-openjiuwen-travel-hotel:0.1.0 if missing; see %s)", log)
                .isNotNull();
        String materializedLine = lastLineContaining(runSlice, "Materialized bundled skills to");
        if (materializedLine != null) {
            assertThat(materializedLine)
                    .as("OJ-11.C jar materialization should use stable dir %s (see %s)", STABLE_SKILLS_DIR, log)
                    .contains(STABLE_SKILLS_DIR);
            assertThat(registrationLine)
                    .as("OJ-11.C skill hub path should match materialized dir (see %s)", log)
                    .contains(STABLE_SKILLS_DIR);
        }
    }

    /**
     * OJ-11.E — skill workflow tools must succeed in logs for the current hotel run:
     * readFile(SKILL.md) without sandbox/404 errors, and at least one successful read.
     */
    public static void assertSkillWorkflowToolsSucceeded(SutStack stack) throws IOException {
        Path log = hotelStdoutLog(stack);
        String runSlice = sliceSinceLast(Files.readString(log, StandardCharsets.UTF_8), SKILL_HUB_REGISTERED_MARKER);

        long readFileSkillFailures = runSlice.lines()
                .filter(line -> line.contains("readFile") && line.contains("SKILL.md"))
                .filter(line -> line.contains("199003") || line.contains("traverses outside"))
                .count();
        assertThat(readFileSkillFailures)
                .as("OJ-11.E readFile(SKILL.md) must not fail (199003 / traverses outside); see %s", log)
                .isZero();

        assertThat(runSlice)
                .as("OJ-11.E readFile(SKILL.md) must succeed (sys_operation '%s'); see %s",
                        READ_FILE_SUCCESS_MARKER, log)
                .contains(READ_FILE_SUCCESS_MARKER);
    }

    /** OJ-11.P — hotel HTTP probe after skill scenario. */
    public static void assertHotelResponds(SutStack stack) throws IOException, InterruptedException {
        OpenjiuwenSandboxGate.assertHotelResponds(stack);
    }

    private static String sliceSinceLast(String logContent, String marker) {
        int lastIdx = logContent.toLowerCase(Locale.ROOT).lastIndexOf(marker.toLowerCase(Locale.ROOT));
        if (lastIdx < 0) {
            return logContent;
        }
        return logContent.substring(lastIdx);
    }

    private static String lastLineContaining(String logContent, String needle) {
        String last = null;
        for (String line : logContent.split("\n")) {
            if (line.contains(needle)) {
                last = line;
            }
        }
        return last;
    }

    private static Path hotelStdoutLog(SutStack stack) {
        var instance = stack.managedInstance(OpenjiuwenStackSupport.HOTEL);
        assertThat(instance)
                .as("managed hotel agent for OJ-11 log gate")
                .isNotNull()
                .isInstanceOf(ManagedSutInstance.class);
        return ((ManagedSutInstance) instance).logFile();
    }
}
