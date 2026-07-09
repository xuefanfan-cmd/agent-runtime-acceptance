package com.huawei.ascend.sit.cases.openjiuwen.integration;

import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenRedisCheckpointerGate;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStackSupport;
import com.huawei.ascend.sit.cases.openjiuwen.OpenjiuwenStreamingTwoTurnRunner;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.BackingServices;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.lifecycle.TestContainerFactory;
import com.huawei.ascend.sit.model.openjiuwen.OpenjiuwenTwoTurnScenarioData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OJ-07 — openjiuwen checkpointer config switch InMemory → Redis (B-04 semantic).
 *
 * <p>Single test method, two phases: Phase1 default in_memory mainplan, Phase2 {@code redis}
 * profile + Testcontainers Redis, same {@link OpenjiuwenTwoTurnScenarioData} dialogue each phase.</p>
 *
 * <p>See {@code docs/cases/OJ-07-openjiuwen-checkpointer-config-switch.md}.</p>
 *
 * <p>LLM credentials are not checked in this class — configure {@code LLM_*} before launch.
 * Phase2 requires Docker for Testcontainers Redis.</p>
 */
@Tag("integration")
@Tag("openjiuwen")
@Tag("nightly")
class OpenjiuwenCheckpointerConfigSwitchTest {

    private static final Logger LOG = Logger.getLogger(OpenjiuwenCheckpointerConfigSwitchTest.class.getName());

    private static final String MAINPLAN = OpenjiuwenStackSupport.MAINPLAN;

    @Test
    @DisplayName("OJ-07: InMemory → Redis profile 切换 — 两阶段流式对话各自达标")
    void oj07_inMemoryThenRedis_sameDialogue_bothPassSemantics() throws Exception {
        TestConfig config = TestConfig.load();
        OpenjiuwenTwoTurnScenarioData scenario = OpenjiuwenTwoTurnScenarioData.loadOj06();

        MainplanPhaseConfig phase1Config = MainplanPhaseConfig.inMemory();
        MainplanPhaseConfig phase2Config = MainplanPhaseConfig.redis();
        assertConfigDiffGate(phase1Config, phase2Config);

        LOG.info("OJ-07 Phase1 managed in-memory (agent=" + MAINPLAN + ", no redis profile)");
        try (SutStack phase1 = phase1Config.toStackBuilder(config, null, null).start()) {
            OpenjiuwenRedisCheckpointerGate.assertAgentsUseInMemoryCheckpointer(phase1, MAINPLAN);
            OpenjiuwenStreamingTwoTurnRunner.run(phase1.client(MAINPLAN), scenario, "OJ-07.P1");
        }

        LOG.info("OJ-07 Phase2 managed redis profile + Testcontainers Redis (agent=" + MAINPLAN + ")");
        try (BackingServices redisBacking = new BackingServices(config, Set.of("redis"), new TestContainerFactory(null))) {
            OpenjiuwenStackSupport.RedisEndpoint redis =
                    OpenjiuwenStackSupport.parseRedisEndpoint(redisBacking.url("redis"));
            try (SutStack phase2 = phase2Config.toStackBuilder(config, redis.host(), redis.port())
                    .backingServices(redisBacking)
                    .start()) {
                OpenjiuwenRedisCheckpointerGate.assertAgentsUseRedisCheckpointer(phase2, MAINPLAN);
                OpenjiuwenStreamingTwoTurnRunner.Result result = OpenjiuwenStreamingTwoTurnRunner.run(
                        phase2.client(MAINPLAN), scenario, "OJ-07.P2");
                OpenjiuwenRedisCheckpointerGate.assertRedisHasCheckpointData(
                        redis.host(),
                        OpenjiuwenRedisCheckpointerGate.parsePort(redis.port()),
                        "OJ-07.P2 after dialogue contextId=" + result.contextId());
            }
        }
    }

    private static void assertConfigDiffGate(MainplanPhaseConfig phase1, MainplanPhaseConfig phase2) {
        assertThat(phase1.profile()).isBlank();
        assertThat(phase2.profile()).isEqualTo(OpenjiuwenStackSupport.REDIS_PROFILE);
        assertThat(phase1.redisBacked()).isFalse();
        assertThat(phase2.redisBacked()).isTrue();
        assertThat(phase1.streaming()).isTrue();
        assertThat(phase2.streaming()).isTrue();
        assertThat(phase1.agentName()).isEqualTo(phase2.agentName());
    }

    /**
     * Snapshot of per-phase mainplan wiring for OJ-07.0c meta gate.
     */
    private record MainplanPhaseConfig(String profile, boolean redisBacked, boolean streaming, String agentName) {

        static MainplanPhaseConfig inMemory() {
            return new MainplanPhaseConfig("", false, true, MAINPLAN);
        }

        static MainplanPhaseConfig redis() {
            return new MainplanPhaseConfig(OpenjiuwenStackSupport.REDIS_PROFILE, true, true, MAINPLAN);
        }

        SutStack.Builder toStackBuilder(TestConfig config, String redisHost, String redisPort) {
            if (redisBacked()) {
                assertThat(redisHost).as("OJ-07 Phase2 redis host").isNotBlank();
                assertThat(redisPort).as("OJ-07 Phase2 redis port").isNotBlank();
                return OpenjiuwenStackSupport.mainplanRedisStreaming(config, redisHost, redisPort);
            }
            return OpenjiuwenStackSupport.mainplanStreaming(config);
        }
    }
}
