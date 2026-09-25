package com.huawei.ascend.sit.cases.e2e.deepanalyze;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-054：能力名片（Agent Card）真实性 + 轨迹开关关闭态的外部契约。
 *
 * 覆盖两个场景：
 * 1) da.card.truthfulness —— 设计 §3.2 运行时服务面接线②「对外暴露的 skills 必须是真实可调用能力、
 *    capabilities 不得夸大未激活能力」（验收出口 #10）。本类断言**结构性真实性**（字段齐全、id 唯一、
 *    不声明未启用的能力开关），并把"声明的技能是否真的可调用"作为证据记录；该判定依赖设计问题 D-04
 *    （工具集未交付阶段名片该声明什么），裁决前不断言其通过与否。
 * 2) da.otel.twostate 的**关闭态半边** —— 设计 §3.2 运行时服务面接线④「开关默认关闭、关闭后无任何
 *    可观测行为变化」。本类只验证"关闭态下对外接口行为与默认基线一致"；"轨迹在后端不可见"这一半
 *    需要可达的遥测后端，不在本类覆盖范围。
 *
 * 本类不断言：模型回答质量、能力实际可调用性（需工具集，见 GAP-054-01）、开启 OTel 后的轨迹可见性。
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-054")
@Feature("FEAT-054: deepanalyze-java 宿主装配")
class DaCardTruthfulnessE2EIT extends BaseManagedStackTest {

    private static final String AGENT = "deepanalyze";

    private HttpClient http;
    private String base;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        return SutStack.builder(config)
                .streaming(true)
                .agent(AGENT, agent -> {
                    bindModelEnvironment(agent);
                    DaModelEnvironment.bindModelKey(agent);
                    // 调用半边需要真实任务跑通：运行时 TaskStore/checkpointer 走 Redis，由栈自管拉起。
                    agent.serviceBinding("redis", "REDIS_HOST", "{{host}}")
                            .serviceBinding("redis", "REDIS_PORT", "{{port}}");
                });
    }

    private static void bindModelEnvironment(SutStack.AgentBuilder agent) {
        passThrough(agent, "LLM_API_KEY", "LLM_API_KEY");
        passThrough(agent, "DA_MODEL_BASE_URL", "LLM_API_BASE");
        passThrough(agent, "DA_MODEL_NAME", "LLM_MODEL");
        passThrough(agent, "DA_MODEL_PROVIDER", "LLM_PROVIDER");
    }

    private static void passThrough(SutStack.AgentBuilder agent, String target, String source) {
        String value = System.getenv(source);
        if (value != null && !value.isBlank()) {
            agent.env(target, value);
        }
    }

    /**
     * 调用半边：设计侧 2026-09-22 裁决（B-Q4，随 B-Q1）：零工具最小任务算数 ⇒ 名片声明的 skills
     * 属"真实可调用场景能力"，保留声明合规。本用例用**一次真实任务**把声明落到可观察行为上。
     */
    @Test
    @Story("da.card.truthfulness: 声明的技能真实可调用")
    @DisplayName("da.card.truthfulness/callable: 经标准入口真实调用一次并完成")
    void declaredSkillsAreCallable() {
        initClient();
        var skills = client(AGENT).getAgentCard().skills();
        Allure.addAttachment("名片声明的 skills", "application/json", String.valueOf(skills));
        assertThat(skills)
                .as("零工具替代路径已裁决算数，名片必须声明至少一项真实可调用能力")
                .isNotEmpty();

        com.huawei.ascend.sit.client.InteractionFlow.of(client(AGENT))
                .withTimeoutMs(config.getPollTimeoutSeconds() * 1000L)
                .send("请用一句话说明你能处理哪一类数据分析任务，不要调用任何工具。")
                    .awaitState(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED)
                    .assertAnswer(text -> {
                        Allure.addAttachment("调用产出的正文", "text/plain", text);
                        assertThat(text)
                                .as("声明的能力必须能真实跑通一次任务（否则属夸大）")
                                .isNotBlank();
                    })
                .execute();
    }

    private void initClient() {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            base = client(AGENT).getBaseUrl();
        }
    }

    @Test
    @Story("da.card.truthfulness: 名片字段齐全且不夸大能力")
    @DisplayName("da.card.truthfulness/structural: name 非空、capabilities 与配置一致")
    void cardFieldsAreStructurallyTruthful() {
        initClient();
        AgentCard card = client(AGENT).getAgentCard();

        assertThat(card).as("能力名片必须可取（否则无法判定真实性）").isNotNull();
        assertThat(card.name()).as("name 不得为空（FEAT-027 多实例可区分）").isNotBlank();
        assertThat(card.version()).as("version 不得为空").isNotBlank();
        assertThat(card.capabilities()).as("capabilities 必须在场").isNotNull();
        assertThat(card.capabilities().streaming())
                .as("本宿主暴露标准 A2A 流式入口，streaming 应为 true")
                .isTrue();
        assertThat(card.capabilities().pushNotifications())
                .as("未启用 callback 链路前不得声明 pushNotifications（否则属夸大）")
                .isFalse();
        Allure.addAttachment("Agent Card（SDK 发现路径）", "application/json", String.valueOf(card));
    }

    @Test
    @Story("da.card.truthfulness: skills 结构合法并记录可调用性证据")
    @DisplayName("da.card.truthfulness/skills: skills 结构合法，逐项记录可调用性证据")
    void declaredSkillsAreStructurallyValid() {
        initClient();
        AgentCard card = client(AGENT).getAgentCard();
        List<AgentSkill> skills = card.skills() == null ? List.of() : card.skills();

        List<String> ids = skills.stream().map(AgentSkill::id).toList();
        assertThat(ids).as("skill id 必须非空").allMatch(id -> id != null && !id.isBlank());
        assertThat(ids.stream().distinct().count())
                .as("skill id 不得重复")
                .isEqualTo(ids.size());

        Allure.addAttachment("声明的 skills", "text/plain",
                skills.isEmpty() ? "(未声明任何 skill)" : String.join(", ", ids));
        Allure.addAttachment("可调用性判定", "text/plain",
                "本轮未断言：工具集未交付（GAP-054-01），设计问题 D-04 未裁决。已记录的声明为："
                        + (skills.isEmpty() ? "空" : String.join(", ", ids)));
    }

    @Test
    @Story("da.otel.twostate: 轨迹开关关闭时对外接口行为与基线一致")
    @DisplayName("da.otel.twostate/off-half: 探活与能力名片行为不因轨迹开关而变")
    void otelOffKeepsExternalBehaviourUnchanged() throws Exception {
        initClient();
        HttpResponse<String> health = get("/health");
        Allure.addAttachment("GET /health（otel 关闭态）", "text/plain", health.body());
        assertThat(health.statusCode()).as("关闭态探活仍应 200").isEqualTo(200);

        HttpResponse<String> card = get("/.well-known/agent-card.json");
        Allure.addAttachment("GET /.well-known/agent-card.json（otel 关闭态）",
                "application/json", card.body());
        assertThat(card.statusCode()).as("关闭态能力名片仍应 200").isEqualTo(200);

        Allure.addAttachment("本用例不覆盖的部分", "text/plain",
                "「轨迹在后端不可见」需要可达的遥测后端核对；本用例只证明关闭态的外部接口行为一致。");
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
