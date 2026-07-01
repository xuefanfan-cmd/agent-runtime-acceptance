package com.huawei.ascend.sit.cases.integration.checkpointer;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.model.integration.checkpointer.RedisMultiTurnScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-04 — Checkpointer config switch InMemory → Redis (特性 2-4).
 *
 * <p>Single test method, two phases: Phase1 {@code in-memory} mainplan, Phase2 {@code redis}
 * mainplan, same {@link RedisMultiTurnScenarioData} dialogue each phase.</p>
 *
 * <p>Managed Phase2 uses {@code mainplan-redis} + {@code sut.services.redis} backing service
 * ({@code application-local.yml}); redis-url is injected by {@link SutStack}, not test code.</p>
 *
 * <p><b>Remote mode</b>: requires two pre-deployed mainplan endpoints in {@code application-sit.yml}:
 * {@code sut.agents.mainplan.url-inmemory} (Phase1) and {@code sut.agents.mainplan.url} (Phase2 redis).
 * Both must be up before the test runs; no local Docker or jar launch.</p>
 *
 * <p>LLM credentials are not checked in this class — configure {@code LLM_*} (or equivalent)
 * before launch for managed phases; remote mode uses LLM on the pre-deployed SUT. See
 * {@code docs/cases/B-04-checkpointer-config-switch.md}.</p>
 */
@Tag("integration")
@Tag("smoke")
class CheckpointerConfigSwitchTest {

    private static final Logger LOG = Logger.getLogger(CheckpointerConfigSwitchTest.class.getName());

    private static final String CHECKPOINTER_KEY = "main-plan-agent.checkpointer";
    private static final String REDIS_URL_KEY = "main-plan-agent.redis-url";
    private static final String MAINPLAN = "mainplan";
    private static final String MAINPLAN_REDIS = RedisCheckpointerMultiTurnTest.MAINPLAN_REDIS;

    @Test
    @DisplayName("B-04: InMemory → Redis 配置切换 — 两阶段对话各自达标")
    void b04_inMemoryThenRedis_sameDialogue_bothPassSemantics() throws Exception {
        TestConfig config = TestConfig.load();
        RedisMultiTurnScenarioData scenario = RedisMultiTurnScenarioData.loadDefault();

        if (CheckpointerSwitchRemoteSupport.isRemoteMode()) {
            runRemotePhases(config, scenario);
        } else {
            runManagedPhases(config, scenario);
        }
    }

    private static void runRemotePhases(TestConfig config, RedisMultiTurnScenarioData scenario) throws Exception {
        MainplanPhaseConfig phase1Config = MainplanPhaseConfig.remoteInMemory(
                CheckpointerSwitchRemoteSupport.phase1RemoteUrl(config));
        MainplanPhaseConfig phase2Config = MainplanPhaseConfig.remoteRedis(
                CheckpointerSwitchRemoteSupport.phase2RemoteUrl(config));
        assertConfigDiffGate(phase1Config, phase2Config);

        LOG.info("B-04 Phase1 remote in-memory: " + phase1Config.remoteUrl());
        try (SutStack phase1 = phase1Config.toStackBuilder(config).start()) {
            TwoTurnDialogueRunner.run(phase1.client(MAINPLAN), scenario, "B-04.P1");
        }

        LOG.info("B-04 Phase2 remote redis: " + phase2Config.remoteUrl());
        try (SutStack phase2 = phase2Config.toStackBuilder(config).start()) {
            TwoTurnDialogueRunner.run(phase2.client(MAINPLAN), scenario, "B-04.P2");
        }
    }

    private static void runManagedPhases(TestConfig config, RedisMultiTurnScenarioData scenario) throws Exception {
        MainplanPhaseConfig phase1Config = MainplanPhaseConfig.managedInMemory();
        MainplanPhaseConfig phase2Config = MainplanPhaseConfig.managedRedis();
        assertConfigDiffGate(phase1Config, phase2Config);

        LOG.info("B-04 Phase1 managed in-memory (agent=" + MAINPLAN + ")");
        try (SutStack phase1 = phase1Config.toStackBuilder(config).start()) {
            TwoTurnDialogueRunner.run(phase1.client(MAINPLAN), scenario, "B-04.P1");
        }

        LOG.info("B-04 Phase2 managed redis (agent=" + MAINPLAN_REDIS + ", backing service from yml)");
        try (SutStack phase2 = phase2Config.toStackBuilder(config).start()) {
            TwoTurnDialogueRunner.run(phase2.client(MAINPLAN_REDIS), scenario, "B-04.P2");
        }
    }

    private static void assertConfigDiffGate(MainplanPhaseConfig phase1, MainplanPhaseConfig phase2) {
        assertThat(phase1.checkpointer()).isEqualTo("in-memory");
        assertThat(phase2.checkpointer()).isEqualTo("redis");
        assertThat(phase2.redisUrl()).isNotBlank();
        assertThat(phase1.redisUrl()).isNull();

        Map<String, String> diff = phase1.diffFrom(phase2);
        assertThat(diff.keySet())
                .as("B-04.0c allowed config diff")
                .containsExactlyInAnyOrder(CHECKPOINTER_KEY, REDIS_URL_KEY);
        assertThat(diff.get(CHECKPOINTER_KEY)).isEqualTo("in-memory -> redis");
    }

    /**
     * Snapshot of per-phase mainplan wiring for B-04.0c meta gate.
     */
    private record MainplanPhaseConfig(
            String mode,
            String remoteUrl,
            String checkpointer,
            String redisUrl,
            String managedAgentName,
            boolean streaming
    ) {
        static MainplanPhaseConfig remoteInMemory(String url) {
            return new MainplanPhaseConfig("remote", url, "in-memory", null, MAINPLAN, true);
        }

        static MainplanPhaseConfig remoteRedis(String url) {
            return new MainplanPhaseConfig("remote", url, "redis", "<server-configured>", MAINPLAN, true);
        }

        static MainplanPhaseConfig managedInMemory() {
            return new MainplanPhaseConfig("managed", null, "in-memory", null, MAINPLAN, true);
        }

        static MainplanPhaseConfig managedRedis() {
            return new MainplanPhaseConfig(
                    "managed", null, "redis", "<backing-service>", MAINPLAN_REDIS, true);
        }

        SutStack.Builder toStackBuilder(TestConfig config) {
            SutStack.Builder builder = SutStack.builder(config).streaming(streaming);
            if ("remote".equals(mode)) {
                return builder.remoteAgent(MAINPLAN, remoteUrl);
            }
            return builder.agent(managedAgentName, a -> a.property(CHECKPOINTER_KEY, checkpointer));
        }

        Map<String, String> diffFrom(MainplanPhaseConfig other) {
            Map<String, String> diff = new LinkedHashMap<>();
            if (!checkpointer.equals(other.checkpointer)) {
                diff.put(CHECKPOINTER_KEY, checkpointer + " -> " + other.checkpointer);
            }
            String leftRedis = redisUrl == null ? "" : redisUrl;
            String rightRedis = other.redisUrl == null ? "" : other.redisUrl;
            if (!leftRedis.equals(rightRedis)) {
                diff.put(REDIS_URL_KEY, leftRedis + " -> " + rightRedis);
            }
            return diff;
        }
    }
}
