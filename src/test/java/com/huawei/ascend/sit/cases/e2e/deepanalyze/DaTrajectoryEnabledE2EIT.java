package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.InteractionFlow;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.utils.RedisProbe;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054 {@code da.otel.twostate} 的**开启态半边**：轨迹装配开启后，运行轨迹真实落盘且可外部观察。
 *
 * <p>关键发现（2026-09-22 取数）：runtime-ext 的轨迹子系统把运行记录写进 **Redis**
 * （`RedisTrajectoryStore`，键前缀 `runtime:run:` / `runtime:run-idx:*` / `runtime:audit:*`），
 * 装配开关是 `openjiuwen.service.trajectory.link.enabled` ⇒ **不需要外部遥测后端**即可验证开启态：
 * 用栈自管的 Redis 探测键空间即可。
 *
 * <p>本类负责"开启态"；"关闭态"半边已由 {@code DaCardTruthfulnessE2EIT}（默认关闭时行为与基线一致）承接。
 *
 * <p>主断言：开启后跑一次真实任务，Redis 出现 `runtime:run:*` 轨迹键；若轨迹没落盘，断言必然失败。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaTrajectoryEnabledE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";
    private static final List<String> TRAJECTORY_GLOBS =
            List.of("runtime:run:*", "runtime:run-idx:*", "runtime:audit:*");

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(false)
                .agent(AGENT, agent -> {
                    passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
                    passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
                    passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
                    passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
                    agent.property("openjiuwen.service.trajectory.link.enabled", "true")
                            .serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    @Test
    @Story("da.otel.twostate: 轨迹装配开启后运行记录落盘可观察")
    @DisplayName("da.otel.twostate/enabled: 开启轨迹装配 → 任务跑通且 Redis 出现 runtime:run 轨迹键")
    void trajectoryIsPersistedWhenEnabled() throws Exception {
        InteractionFlow.of(client(AGENT))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send("请用一句话说明你收到了消息，不要调用任何工具。")
                    .awaitState(TaskState.TASK_STATE_COMPLETED)
                    .assertAnswer(text -> assertThat(text).isNotBlank())
                .execute();

        URI redis = URI.create(stack.serviceUrl("redis"));
        RedisProbe probe = new RedisProbe(redis.getHost(), redis.getPort());
        List<String> keys = probe.keysAny(TRAJECTORY_GLOBS.toArray(String[]::new));
        Allure.addAttachment("轨迹键（Redis keysAny " + TRAJECTORY_GLOBS + "）", "text/plain",
                keys.size() + " keys: " + keys);
        assertThat(keys)
                .as("开启轨迹装配后，运行记录必须真实落盘（否则开启态无可观察效果）")
                .isNotEmpty();
    }
}
