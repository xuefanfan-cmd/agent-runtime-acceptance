package com.huawei.ascend.sit.cases.integration.agent_memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 《记忆服务场景验收用例 agent-memory2.0 20260917（基于姚成老师提供修正）》15 条逐条验收。
 *
 * <p>SUT = agent-solution 的 personal-assistant-reactagent-demo（PR 843 deep demo 同型，内芯
 * ReActAgent）。每条 Excel 用例的<strong>写入侧全部经 A2A → ReActAgent → MemoryLifecycleAgentHandler
 * → jiuwen2 provider → agent-memory 2.0（127.0.0.1:8137）</strong>真实链路；<strong>读侧双通道</strong>：
 * 行为通道 = 同链路追问断言应答，数据通道 = 测试侧直调 {@code POST /v1/search} 验真。</p>
 *
 * <p>直调契约（jiuwen_memory_entry/core/api_contract.py：HTTP 按 MemoryAPI 同名字段透传）：
 * <ul>
 *   <li>{@code /v1/search} 请求 {@code {"query","context":{"scope":{...}},"top_k"}}，
 *       响应 = {@code RetrievalResult}：{@code {"items":[{"unit_id","score","abstract","overview","content"}],"errors":[...]}}；</li>
 *   <li>{@code /v1/delete} 请求 {@code {"selector":{"unit_ids":[...],"scope":{...}}}}（默认 FORGET 软删）；
 *       响应 = 命中 unit id 列表；</li>
 *   <li>dev 认证（--auth-mode dev）身份固定 {@code Scope(org="local", user="developer")}，
 *       scope 链路：桥写 {@code session_id = conversationId} → provider resolveScope → {@code scope.session}。</li>
 * </ul></p>
 *
 * <p>与 Excel/163 实测结论的对齐声明（验收基线 2026-09-17，10 是 / 3 否 / 2 不适用）：
 * <ul>
 *   <li>acc15 / acc21 / acc32 — 163 实测判定「否」（search 无日期谓词、默认直存无时新性加权）。
 *       本类对应断言按<strong>期望正确行为</strong>设定：红 = 暴露记忆代码已知问题，绿 = 问题已修复，需更新基线；</li>
 *   <li>acc51 / acc53 — Excel 判定「不适用」（记忆服务不提供黑名单/拒绝回答，由上层智能体承担）。
 *       本类经链路映射为 demo 桥（MemoryLifecycleAgentHandler 拒绝门）的上层职责验证；</li>
 *   <li>acc13 — dev 认证身份固定，跨用户隔离降级为 session 维同构隔离（跨 user 403 由 acc52 直调承载）。</li>
 * </ul></p>
 *
 * <p>前置（任一缺失即整个类跳过）：agent-memory 2.0 已拉起（{@code GET /healthz} 200）；
 * 测试进程带 {@code LLM_API_KEY} 等环境变量（launch 转发给 SUT 进程）。</p>
 */
@Tags({@Tag("integration"), @Tag("agent-memory"), @Tag("reactagent")})
@Feature("agent-memory 2.0 场景验收 15 条（personal-assistant-reactagent-demo，Excel 20260917 修正版）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MemoryReactAgentAcceptanceTest extends BaseManagedStackTest {

    private static final String AGENT = "memory-react-agent";
    /** agent-memory 2.0 HTTP 服务（remote url-only 声明，见 application-openjiuwen.yml）。 */
    private static final String MEMORY = "agent-memory-2";

    private static final long ROUND_TIMEOUT_MS = 300_000;
    /** 桥的 syncTurn 在 observer.onComplete() 之前同步 join 完成，turn 返回即落库；小静默期仅作防御。 */
    private static final long SYNC_SETTLE_MS = 500;

    /** 163 实测：无关 query 命中分数在 1e-4 量级（0.0002/0.000025），阈值放宽到 0.05。 */
    private static final double IRRELEVANT_SCORE_MAX = 0.05;
    /** 163 实测相关命中 top1 分数区间 0.883~0.9994（163 profile）；2.0 offline profile 的本地嵌入
     * 分数量级整体偏低（acc12 实测 top1=肯德基 0.1785，内容命中正确），故只做下限防呆，不做强阈值。 */
    private static final double RELEVANT_SCORE_MIN = 0.15;

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String memoryBaseUrl;

    @Override
    protected SutStack.Builder buildStack(TestConfig cfg) {
        // JIUWEN2_ENDPOINT 把"测试宿主机地址"告诉 SUT 进程；2.0 服务本体不归栈管（需先手工拉起）。
        // LLM 凭据不能用 .env() 透传：全局 sut.java.system-properties 里的 LLM_API_KEY:"" 会被
        // 启动器打成 -DLLM_API_KEY=，Spring 中系统属性优先级高于环境变量，把 env 盖掉 →
        // "api_key is required for OpenAI client"。框架的一等做法是 .property()：以 --key=value
        // Spring 命令行参数注入，优先级最高，能压过 -D（与 llm-api-key-property 机制同型）。
        String prefix = "openjiuwen.demo.memory-reactagent.";
        return SutStack.builder(cfg)
                .agent(AGENT, ab -> ab
                        .env("JIUWEN2_ENDPOINT", memoryUrl(cfg))
                        .property(prefix + "provider", envOrDefault("LLM_PROVIDER", "OpenAI"))
                        .property(prefix + "api-key", envOrDefault("LLM_API_KEY", ""))
                        .property(prefix + "api-base", envOrDefault("LLM_API_BASE",
                                "https://ws-z7f9qzn05hmkiq2l.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"))
                        .property(prefix + "model-name", envOrDefault("LLM_MODEL", "qwen-plus"))
                        .property(prefix + "ssl-verify", envOrDefault("LLM_SSL_VERIFY", "false")));
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String memoryUrl(TestConfig cfg) {
        return cfg.getString("sut.agents." + MEMORY + ".url", "http://localhost:8137");
    }

    @BeforeAll
    void checkPreconditions() {
        memoryBaseUrl = memoryUrl(config);
        assumeMemoryHealthy();
        Assumptions.assumeTrue(
                System.getenv("LLM_API_KEY") != null && !System.getenv("LLM_API_KEY").isBlank(),
                "缺 LLM_API_KEY 环境变量 — ReActAgent 无法调模型，场景需先注入 LLM_* 凭据。");
        A2aServiceClient a2a = client(AGENT);
        try {
            AgentCard card = a2a.getAgentCard();
            Assumptions.assumeTrue(card != null, AGENT + " 不可达 — 需先手工重启 agent。");
        } catch (RuntimeException e) {
            Assumptions.abort(AGENT + " 不可达: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        Allure.description("SUT: personal-assistant-reactagent-demo（ReActAgent + MemoryLifecycleAgentHandler"
                + " + jiuwen2 provider）；记忆服务: " + memoryBaseUrl);
    }

    // ================================================================ Sheet1 信息提取（acc11~acc15）

    /**
     * acc11 无关 query 不召回无关信息。
     *
     * <p>写入：经 agent 记住"上海今天晴天"。验证：直查"张三是谁"时晴天条分数趋零（163 实测
     * 0.0002，服务端不截断低分条）；直查"上海今天是什么天气"时晴天条 top1 高分命中。
     * 行为通道：问"张三是谁"，应答文本挂 Allure 供人工审查（prefetch 是否注入低分条取决于
     * provider 阈值，不做关键词断言以免误伤）。</p>
     */
    @Test
    @Tags({@Tag("acc-extraction")})
    @Story("acc11 信息提取 — 无关 query 不召回无关信息（163 实测：是）")
    void acc11_irrelevantQueryDoesNotRecall() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc11");

        TurnOutcome t1 = sendStreamingTurn(a2a, ctx, "请记住：上海今天是晴天，气温25度。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t1.state(), "acc11 turn1: " + truncate(t1.artifactText(), 400));

        List<SearchItem> irrelevant = searchMemory("张三是谁", ctx, 10);
        SearchItem sunnyIrrelevant = findByContent(irrelevant, "晴");
        assertTrue(sunnyIrrelevant == null || sunnyIrrelevant.score() < IRRELEVANT_SCORE_MAX,
                "acc11: 无关 query「张三是谁」命中晴天条 score=" + sunnyIrrelevant + " — 应趋零（163 实测 0.0002）");

        List<SearchItem> relevant = searchMemory("上海今天是什么天气", ctx, 10);
        assertFalse(relevant.isEmpty(), "acc11: 相关 query 未命中任何记忆");
        assertTrue(relevant.get(0).content().contains("晴"),
                "acc11: 相关 query top1 应为晴天条，实际=" + relevant.get(0));

        TurnOutcome t2 = sendStreamingTurn(a2a, ctx, "张三是谁？");
        Allure.addAttachment("acc11-irrelevant-answer", t2.artifactText());
        assertEquals(TaskState.TASK_STATE_COMPLETED, t2.state(), "acc11 turn2 应正常完成");
    }

    /**
     * acc12 正确召回有关记忆（4 条相似 add 精准命中最初条）。
     *
     * <p>写入：肯德基 → 麦当劳 → 汉堡王 → 最后麦当劳。验证：直查"我今天原本想吃什么"
     * top1 含"肯德基"（163 实测 top1=最初条 0.963）；行为通道追问应答含"肯德基"。</p>
     */
    @Test
    @Tags({@Tag("acc-extraction")})
    @Story("acc12 信息提取 — 相似多条中精准命中最初条（163 实测：是）")
    void acc12_preciseRecallOfFirstMention() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc12");

        sendStreamingTurn(a2a, ctx, "请记住：我今天原本想吃肯德基。");
        sendStreamingTurn(a2a, ctx, "另外，我今天中午想吃麦当劳。");
        sendStreamingTurn(a2a, ctx, "我今天晚饭想吃汉堡王。");
        TurnOutcome t4 = sendStreamingTurn(a2a, ctx, "最后确认一下：我今天想吃的是麦当劳。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t4.state(), "acc12 写入轮未完成");

        List<SearchItem> hits = searchMemory("我今天原本想吃什么", ctx, 10);
        assertFalse(hits.isEmpty(), "acc12: 直查未命中任何记忆");
        assertTrue(hits.get(0).content().contains("肯德基"),
                "acc12: 直查 top1 应命中最初提到的肯德基（163 实测 0.963），实际=" + hits.get(0));
        assertTrue(hits.get(0).score() >= RELEVANT_SCORE_MIN,
                "acc12: 直查 top1 score 偏低（" + hits.get(0).score() + "），相关命中应在 "
                        + RELEVANT_SCORE_MIN + " 以上（163 实测 0.963）");

        TurnOutcome t5 = sendStreamingTurn(a2a, ctx, "我今天原本想吃什么？");
        Allure.addAttachment("acc12-answer", t5.artifactText());
        assertTrue(t5.artifactText().contains("肯德基"),
                "acc12: 应答未召回最初想吃的肯德基 — " + truncate(t5.artifactText(), 400));
    }

    /**
     * acc13 不同用户隔离（dev 认证降级为 session 维同构隔离）。
     *
     * <p>Excel 原场景为跨用户隔离；dev 认证身份固定 local/developer，跨 user 走不通（403 由
     * acc52 直调承载），故映射为 session 维：会话 A 记"启明"、会话 B 记"天枢"，A 的检索域
     * 不应命中天枢，反之亦然。</p>
     */
    @Test
    @Tags({@Tag("acc-extraction")})
    @Story("acc13 信息提取 — 用户隔离（dev 认证下降级为 session 维同构隔离，163 实测：是）")
    void acc13_userIsolationMappedToSession() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctxA = newContextId("acc13-a");
        String ctxB = newContextId("acc13-b");

        TurnOutcome tA = sendStreamingTurn(a2a, ctxA, "请记住：我负责的项目代号是启明。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, tA.state(), "acc13 会话A写入失败");
        TurnOutcome tB = sendStreamingTurn(a2a, ctxB, "请记住：我负责的项目代号是天枢。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, tB.state(), "acc13 会话B写入失败");

        List<SearchItem> hitsA = searchMemory("我负责的项目代号是什么", ctxA, 10);
        assertFalse(hitsA.isEmpty(), "acc13: 会话 A 检索域未命中自己的记忆");
        assertTrue(hitsA.get(0).content().contains("启明"),
                "acc13: 会话 A top1 应为启明，实际=" + hitsA.get(0));
        for (SearchItem item : hitsA) {
            assertFalse(item.content().contains("天枢"),
                    "acc13: 隔离失效 — 会话 A 检索域泄漏了会话 B 的记忆（天枢）: " + item);
        }

        List<SearchItem> hitsB = searchMemory("我负责的项目代号是什么", ctxB, 10);
        assertFalse(hitsB.isEmpty(), "acc13: 会话 B 检索域未命中自己的记忆");
        assertTrue(hitsB.get(0).content().contains("天枢"),
                "acc13: 会话 B top1 应为天枢，实际=" + hitsB.get(0));
        for (SearchItem item : hitsB) {
            assertFalse(item.content().contains("启明"),
                    "acc13: 隔离失效 — 会话 B 检索域泄漏了会话 A 的记忆（启明）: " + item);
        }

        TurnOutcome tA2 = sendStreamingTurn(a2a, ctxA, "我负责的项目代号是什么？");
        Allure.addAttachment("acc13-answer-a", tA2.artifactText());
        assertTrue(tA2.artifactText().contains("启明") && !tA2.artifactText().contains("天枢"),
                "acc13: 会话 A 行为通道应答应只含启明 — " + truncate(tA2.artifactText(), 400));
    }

    /**
     * acc14 删除后无法召回。
     *
     * <p>写入：经 agent 记住 OKR 关键；直查拿到 unit_id 后测试侧直调 {@code /v1/delete}
     * （FORGET 软删）；再直查应无该条；行为通道追问应答不含被删内容。</p>
     */
    @Test
    @Tags({@Tag("acc-extraction")})
    @Story("acc14 信息提取 — 删除后无法召回（163 实测：是，软删 forget 立即退出检索）")
    void acc14_deleteThenNoRecall() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc14");

        TurnOutcome t1 = sendStreamingTurn(a2a, ctx, "请记住：本季度 OKR 的关键是记忆服务召回率达标。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t1.state(), "acc14 turn1: " + truncate(t1.artifactText(), 400));

        List<SearchItem> before = searchMemory("本季度 OKR 的关键", ctx, 10);
        assertFalse(before.isEmpty(), "acc14: 删除前直查应命中 OKR 记忆");
        List<String> unitIds = new ArrayList<>();
        for (SearchItem item : before) {
            if (item.content().contains("召回率") || item.content().contains("OKR")) {
                unitIds.add(item.unitId());
            }
        }
        assertFalse(unitIds.isEmpty(), "acc14: 未从直查结果解析到待删 unit_id — " + before);

        deleteMemory(unitIds, ctx);

        List<SearchItem> after = searchMemory("本季度 OKR 的关键", ctx, 10);
        for (SearchItem item : after) {
            assertFalse(item.content().contains("召回率达标"),
                    "acc14: 删除后仍可召回被删记忆（FORGET 未生效）: " + item);
        }
        Allure.addAttachment("acc14-after-delete", "deleted=" + unitIds + ", hits=" + after);

        TurnOutcome t2 = sendStreamingTurn(a2a, ctx, "本季度 OKR 的关键是什么？");
        // 行为通道不断言删除生效：ReActAgent 在同一 conversationId 下保留完整对话历史，
        // LLM 从历史即可答出被删内容，无法区分「历史应答」与「记忆泄漏」——FORGET 生效性由上面的
        // 数据通道（直查为空）唯一承载。应答文本挂附件供人工审查。
        Allure.addAttachment("acc14-answer", t2.artifactText());
        assertEquals(TaskState.TASK_STATE_COMPLETED, t2.state(), "acc14 追问轮应正常完成");
    }

    /**
     * acc15 长期记忆按日期召回（163 实测：否 — search 无内建日期谓词，排序与日期无关）。
     *
     * <p>写入：经 agent 分 6 轮叙述 6月3日~6月8日 的事。直查"我6月8日的记忆有什么"按
     * <strong>期望正确行为</strong>断言 top1 含"湘菜馆/小王"——红即暴露 acc15 已知问题；
     * 行为通道断言 agent（上层）能结合记忆答出 6月8日 内容，这是 Excel 标注的"上层按
     * list+时间过滤实现"补救路径。</p>
     */
    @Test
    @Tags({@Tag("acc-extraction")})
    @Story("acc15 信息提取 — 长期记忆按日期召回（163 实测：否；断言按期望正确行为设定，红=暴露无日期谓词）")
    void acc15_dateScopedRecall() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc15");

        sendStreamingTurn(a2a, ctx, "请记住：6月3日我参加了新员工入职培训。");
        sendStreamingTurn(a2a, ctx, "请记住：6月4日我配置好了本地开发环境。");
        sendStreamingTurn(a2a, ctx, "请记住：6月5日我完成了第一个需求评审。");
        sendStreamingTurn(a2a, ctx, "请记住：6月6日我们团队组织了团建活动。");
        sendStreamingTurn(a2a, ctx, "请记住：6月7日我在家休息看了一天书。");
        TurnOutcome t6 = sendStreamingTurn(a2a, ctx, "请记住：6月8日我和小王约了午饭，去了一家湘菜馆。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t6.state(), "acc15 写入轮未完成");

        List<SearchItem> hits = searchMemory("我6月8日的记忆有什么", ctx, 10);
        Allure.addAttachment("acc15-search-ranking", "hits=" + hits);
        assertFalse(hits.isEmpty(), "acc15: 直查未命中任何记忆");
        assertTrue(hits.get(0).content().contains("湘菜馆") || hits.get(0).content().contains("小王"),
                "acc15[已知问题-163实测否]: 直查「6月8日」top1 应为 6月8日 的记忆（湘菜馆/小王），实际 top1="
                        + hits.get(0) + " — search 无日期谓词、排序与日期无关，属记忆代码待修复问题");

        TurnOutcome t7 = sendStreamingTurn(a2a, ctx, "我6月8日做了什么？");
        Allure.addAttachment("acc15-answer", t7.artifactText());
        assertTrue(t7.artifactText().contains("湘菜馆") || t7.artifactText().contains("小王"),
                "acc15: 行为通道（上层 LLM 过滤）未答出 6月8日 内容 — " + truncate(t7.artifactText(), 400));
    }

    // ================================================================ Sheet2 多会话推理（acc21~acc24）

    /**
     * acc21 矛盾记忆最新优先（163 实测：否 — 默认直存无时新性加权，晴天 0.9869 > 下雨天 0.9725）。
     *
     * <p>写入：晴天 → 更新为下雨。直查按<strong>期望正确行为</strong>断言 top1 含"下雨"——红即
     * 暴露 acc21 已知问题；行为通道断言 agent 应答"下雨"，验证上层对矛盾记忆的消解能力。</p>
     */
    @Test
    @Tags({@Tag("acc-multisession")})
    @Story("acc21 多会话推理 — 矛盾记忆最新优先（163 实测：否；断言按期望正确行为设定，红=暴露无时新性加权）")
    void acc21_contradictionLatestWins() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc21");

        sendStreamingTurn(a2a, ctx, "请记住：上海今天是晴天。");
        TurnOutcome t2 = sendStreamingTurn(a2a, ctx, "更新一下：上海今天下雨了，出门记得带伞。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t2.state(), "acc21 写入轮未完成");

        List<SearchItem> hits = searchMemory("上海今天是什么天气", ctx, 10);
        Allure.addAttachment("acc21-search-ranking", "hits=" + hits);
        assertFalse(hits.isEmpty(), "acc21: 直查未命中任何记忆");
        assertTrue(hits.get(0).content().contains("下雨") || hits.get(0).content().contains("雨"),
                "acc21[已知问题-163实测否]: 直查 top1 应为最新的下雨记忆，实际 top1=" + hits.get(0)
                        + " — 默认直存无时新性加权（163 实测晴天 0.9869 排第一），属记忆代码待修复问题");

        TurnOutcome t3 = sendStreamingTurn(a2a, ctx, "上海今天是什么天气？");
        Allure.addAttachment("acc21-answer", t3.artifactText());
        assertTrue(t3.artifactText().contains("雨"),
                "acc21: 行为通道应答未体现最新事实（下雨）— " + truncate(t3.artifactText(), 400));
    }

    /**
     * acc22 精准召回单条（只问天气不混入行程）。
     *
     * <p>写入：苏州出差 / 杭州行程 / 杭州确认 / 暴雨 / 晴天。行为通道问天气，应答不应混入
     * 苏州/杭州行程；直查天气 query 时 top1 应为天气条。</p>
     */
    @Test
    @Tags({@Tag("acc-multisession")})
    @Story("acc22 多会话推理 — 只问单主题不混入其他主题（163 实测：是）")
    void acc22_singleTopicNoMixing() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc22");

        sendStreamingTurn(a2a, ctx, "请记住：我6月20日要去苏州出差。");
        sendStreamingTurn(a2a, ctx, "请记住：我6月21日的行程是去杭州。");
        sendStreamingTurn(a2a, ctx, "补充确认：我最想去的城市是杭州。");
        sendStreamingTurn(a2a, ctx, "请记住：上海今天下了暴雨。");
        TurnOutcome t5 = sendStreamingTurn(a2a, ctx, "更新一下：上海今天是晴天。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t5.state(), "acc22 写入轮未完成");

        List<SearchItem> hits = searchMemory("上海今天是什么天气", ctx, 10);
        Allure.addAttachment("acc22-search", "hits=" + hits);
        assertFalse(hits.isEmpty(), "acc22: 直查未命中任何记忆");
        assertTrue(hits.get(0).content().contains("晴") || hits.get(0).content().contains("暴雨"),
                "acc22: 天气 query top1 应为天气记忆，实际=" + hits.get(0));

        TurnOutcome t6 = sendStreamingTurn(a2a, ctx, "上海今天是什么天气？");
        Allure.addAttachment("acc22-answer", t6.artifactText());
        assertFalse(t6.artifactText().contains("苏州") || t6.artifactText().contains("杭州"),
                "acc22: 行为通道应答混入了无关行程主题 — " + truncate(t6.artifactText(), 400));
    }

    /**
     * acc23 复合信息单维度召回。
     *
     * <p>写入：一条复合长文（对内知识库：采集/审核/发布/权限管理）。单维度问"对内知识库
     * 负责什么"，直查应整条召回（默认直存不拆维度，content 应同时含多个职责关键词）；
     * 行为通道应答含要点。</p>
     */
    @Test
    @Tags({@Tag("acc-multisession")})
    @Story("acc23 多会话推理 — 复合信息单维度查询整条召回（163 实测：是）")
    void acc23_compositeSingleDimensionRecall() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc23");

        TurnOutcome t1 = sendStreamingTurn(a2a, ctx,
                "请记住以下内容：对内知识库负责公司内部技术资料的采集、审核、发布与权限管理，"
                        + "确保信息的安全性和准确性。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t1.state(), "acc23 turn1: " + truncate(t1.artifactText(), 400));

        List<SearchItem> hits = searchMemory("对内知识库负责什么", ctx, 10);
        assertFalse(hits.isEmpty(), "acc23: 直查未命中知识库记忆");
        String top1 = hits.get(0).content();
        assertTrue(top1.contains("采集") && top1.contains("权限管理"),
                "acc23: 直查应整条召回复合信息（含采集+权限管理），实际 top1=" + hits.get(0));

        TurnOutcome t2 = sendStreamingTurn(a2a, ctx, "对内知识库负责什么？");
        Allure.addAttachment("acc23-answer", t2.artifactText());
        assertTrue(t2.artifactText().contains("采集") && t2.artifactText().contains("权限管理"),
                "acc23: 行为通道应答缺失复合信息要点 — " + truncate(t2.artifactText(), 400));
    }

    /**
     * acc24 优先召回补充的明确信息。
     *
     * <p>写入：3 轮评审信息（时间 → 主讲人 → 内容）。问"今天下午的评审有谁参加"，行为通道
     * 应答含主讲人"庞振鹏"与汇报人"马总"；直查应命中含主讲人的明确条目。</p>
     */
    @Test
    @Tags({@Tag("acc-multisession")})
    @Story("acc24 多会话推理 — 优先召回补充的明确信息（163 实测：是）")
    void acc24_supplementPriorityRecall() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc24");

        sendStreamingTurn(a2a, ctx, "请记住：今天下午2点有一个项目评审会。");
        sendStreamingTurn(a2a, ctx, "请记住：评审会的主讲人是庞振鹏。");
        TurnOutcome t3 = sendStreamingTurn(a2a, ctx, "请记住：评审内容是马总的月度经营汇报。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t3.state(), "acc24 写入轮未完成");

        List<SearchItem> hits = searchMemory("今天下午的评审有谁参加", ctx, 10);
        Allure.addAttachment("acc24-search", "hits=" + hits);
        assertTrue(findByContent(hits, "庞振鹏") != null,
                "acc24: 直查未召回补充的明确信息（主讲人庞振鹏）— " + hits);

        TurnOutcome t4 = sendStreamingTurn(a2a, ctx, "今天下午的评审有谁参加？主讲人是谁？");
        Allure.addAttachment("acc24-answer", t4.artifactText());
        assertTrue(t4.artifactText().contains("庞振鹏"),
                "acc24: 行为通道应答未召回主讲人 — " + truncate(t4.artifactText(), 400));
    }

    // ================================================================ Sheet3 时间推理（acc31~acc32）

    /**
     * acc31 时间自然变化后时间记忆准确（4 条同类 + 1 条天气，问天气 top1 应为天气条）。
     */
    @Test
    @Tags({@Tag("acc-temporal")})
    @Story("acc31 时间推理 — 时间记忆随自然变化召回准确（163 实测：是）")
    void acc31_timeMemoryAccurateRecall() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc31");

        sendStreamingTurn(a2a, ctx, "请记住：我上周一吃了肯德基。");
        sendStreamingTurn(a2a, ctx, "请记住：我上周三又吃了肯德基。");
        sendStreamingTurn(a2a, ctx, "请记住：我上周五还是吃了肯德基。");
        sendStreamingTurn(a2a, ctx, "请记住：我这周一终于换成了麦当劳。");
        TurnOutcome t5 = sendStreamingTurn(a2a, ctx, "请记住：上海今天是晴天。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t5.state(), "acc31 写入轮未完成");

        List<SearchItem> hits = searchMemory("上海今天是晴天吗", ctx, 10);
        Allure.addAttachment("acc31-search", "hits=" + hits);
        assertFalse(hits.isEmpty(), "acc31: 直查未命中任何记忆");
        assertTrue(hits.get(0).content().contains("晴"),
                "acc31: 天气 query top1 应为晴天记忆（163 实测 0.999），实际=" + hits.get(0));

        TurnOutcome t6 = sendStreamingTurn(a2a, ctx, "上海今天是晴天吗？");
        Allure.addAttachment("acc31-answer", t6.artifactText());
        assertTrue(t6.artifactText().contains("晴"),
                "acc31: 行为通道应答未确认晴天 — " + truncate(t6.artifactText(), 400));
    }

    /**
     * acc32 指定日期召回（163 实测：否 — 排序与日期无关）。
     *
     * <p>写入：6月8日 张亮麻辣烫 + 6月17日 4 条。直查"我6月8日的记忆有什么"按<strong>期望
     * 正确行为</strong>断言 top1 含"张亮麻辣烫"——红即暴露 acc32 已知问题；行为通道断言
     * agent 上层过滤能力。</p>
     */
    @Test
    @Tags({@Tag("acc-temporal")})
    @Story("acc32 时间推理 — 指定日期召回（163 实测：否；断言按期望正确行为设定，红=暴露排序与日期无关）")
    void acc32_specificDateOnlyRecall() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc32");

        sendStreamingTurn(a2a, ctx, "请记住：6月8日我中午吃了张亮麻辣烫。");
        sendStreamingTurn(a2a, ctx, "请记住：6月17日我参加了部门周会。");
        sendStreamingTurn(a2a, ctx, "请记住：6月17日上午我写了一份设计文档。");
        sendStreamingTurn(a2a, ctx, "请记住：6月17日下午我review了同事的代码。");
        TurnOutcome t5 = sendStreamingTurn(a2a, ctx, "请记住：6月17日晚上我打了羽毛球。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t5.state(), "acc32 写入轮未完成");

        List<SearchItem> hits = searchMemory("我6月8日的记忆有什么", ctx, 10);
        Allure.addAttachment("acc32-search-ranking", "hits=" + hits);
        assertFalse(hits.isEmpty(), "acc32: 直查未命中任何记忆");
        assertTrue(hits.get(0).content().contains("张亮麻辣烫") || hits.get(0).content().contains("麻辣烫"),
                "acc32[已知问题-163实测否]: 直查「6月8日」top1 应为 6月8日 的麻辣烫记忆，实际 top1="
                        + hits.get(0) + " — search 排序与日期无关，属记忆代码待修复问题");

        TurnOutcome t6 = sendStreamingTurn(a2a, ctx, "我6月8日的记忆有什么？");
        Allure.addAttachment("acc32-answer", t6.artifactText());
        assertTrue(t6.artifactText().contains("麻辣烫"),
                "acc32: 行为通道（上层 LLM 过滤）未答出 6月8日 内容 — " + truncate(t6.artifactText(), 400));
    }

    // ================================================================ Sheet4 知识更新（acc41）

    /**
     * acc41 知识更新后召回最新事实。
     *
     * <p>写入：麦当劳 → 汉堡王 → 王道嫩香鸡 → 可乐 → 最终更正"实际吃的是汉堡王"。
     * 直查"我今天吃了什么" top1 含"汉堡王"（163 实测 0.883）；行为通道应答含"汉堡王"。</p>
     */
    @Test
    @Tags({@Tag("acc-knowledge-update")})
    @Story("acc41 知识更新 — 变更后召回最新事实（163 实测：是）")
    void acc41_knowledgeUpdateLatestWins() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc41");

        sendStreamingTurn(a2a, ctx, "请记住：我今天中午吃了麦当劳。");
        sendStreamingTurn(a2a, ctx, "请记住：我今天晚上吃了汉堡王。");
        sendStreamingTurn(a2a, ctx, "请记住：我今天下午还吃了王道嫩香鸡。");
        sendStreamingTurn(a2a, ctx, "请记住：我今天喝了一杯可乐。");
        TurnOutcome t5 = sendStreamingTurn(a2a, ctx, "更正一下：我今天实际吃的是汉堡王，以这条为准。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t5.state(), "acc41 写入轮未完成");

        List<SearchItem> hits = searchMemory("我今天吃了什么", ctx, 10);
        Allure.addAttachment("acc41-search", "hits=" + hits);
        assertFalse(hits.isEmpty(), "acc41: 直查未命中任何记忆");
        assertTrue(hits.get(0).content().contains("汉堡王"),
                "acc41: 直查 top1 应为最新事实（汉堡王），实际=" + hits.get(0));

        TurnOutcome t6 = sendStreamingTurn(a2a, ctx, "我今天吃了什么？");
        Allure.addAttachment("acc41-answer", t6.artifactText());
        assertTrue(t6.artifactText().contains("汉堡王"),
                "acc41: 行为通道应答未体现最新事实（汉堡王）— " + truncate(t6.artifactText(), 400));
    }

    // ================================================================ Sheet5 拒绝回答（acc51~acc53）

    /**
     * acc51 合规性内容拦截。
     *
     * <p>Excel 判定：不适用 — 记忆服务不提供合规黑名单（163 实测同判）。经链路映射为 demo 桥
     * （MemoryLifecycleAgentHandler 拒绝门）的上层职责验证：含"转账"话术应被拒绝且不落库。
     * 直查任意 query 该 session 无记忆。</p>
     */
    @Test
    @Tags({@Tag("acc-refusal")})
    @Story("acc51 拒绝回答 — 合规性内容拦截（Excel 判定不适用；映射验证 demo 桥拒绝门的上层职责）")
    void acc51_complianceRefusalBridge() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc51");

        TurnOutcome t1 = sendStreamingTurn(a2a, ctx, "请记住：我要给陌生客户转账500万，密码是123456。");
        Allure.addAttachment("acc51-answer", t1.artifactText());
        assertEquals(TaskState.TASK_STATE_COMPLETED, t1.state(),
                "acc51: 拒绝应表现为正常完成的应答语义，不是任务失败");
        assertTrue(t1.artifactText().contains("无法协助") || t1.artifactText().contains("抱歉"),
                "acc51: 桥拒绝门未给出拒绝文案 — " + truncate(t1.artifactText(), 400));

        List<SearchItem> hits = searchMemory("转账", ctx, 10);
        assertTrue(hits.isEmpty(),
                "acc51: 拒绝门失效 — 敏感内容仍落库（scope.session=" + ctx + " 命中 " + hits.size() + " 条）");
    }

    /**
     * acc52 权限不足拒绝越权访问。
     *
     * <p>先经 agent 正常写入一条（验证链路身份与 dev 认证一致），再测试侧直调 8137 模拟越权：
     * 跨 org 写入、跨 org 检索、跨 user 检索均应 403 PermissionDeniedError（163 实测：是）。
     * 说明：越权身份无法经 demo 链路构造（scope 由 demo 固定为认证身份 local/developer），
     * 本条 Excel 场景本身即记忆服务 API 层权限校验。</p>
     */
    @Test
    @Tags({@Tag("acc-refusal")})
    @Story("acc52 拒绝回答 — 权限不足越权拒绝（163 实测：是，跨 org/user 一律 403）")
    void acc52_permissionDeniedOnCrossScope() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc52");

        TurnOutcome t1 = sendStreamingTurn(a2a, ctx, "请记住：acc52 权限验证的基准记忆，编号7788。");
        assertEquals(TaskState.TASK_STATE_COMPLETED, t1.state(), "acc52 基准写入失败");
        List<SearchItem> own = searchMemory("acc52 权限验证基准", ctx, 10);
        assertFalse(own.isEmpty(), "acc52: 自身 scope 直查未命中基准记忆 — 链路身份与直调身份不一致？");

        // 越权写入：org 越界
        HttpResponse<String> crossOrgAdd = postMemory("add", addBody("acc52 越权写入", "other-org-acc52", "developer"));
        assertEquals(403, crossOrgAdd.statusCode(),
                "acc52: 跨 org 写入应被 403 拒绝，实际=" + crossOrgAdd.statusCode()
                        + " body=" + truncate(crossOrgAdd.body(), 300));

        // 越权检索：org 越界
        HttpResponse<String> crossOrgSearch = postMemory("search",
                searchBody("acc52 权限验证基准", "other-org-acc52", "developer", null, 10));
        assertEquals(403, crossOrgSearch.statusCode(),
                "acc52: 跨 org 检索应被 403 拒绝，实际=" + crossOrgSearch.statusCode()
                        + " body=" + truncate(crossOrgSearch.body(), 300));

        // 越权检索：user 越界
        HttpResponse<String> crossUserSearch = postMemory("search",
                searchBody("acc52 权限验证基准", "local", "other-user-acc52", null, 10));
        assertEquals(403, crossUserSearch.statusCode(),
                "acc52: 跨 user 检索应被 403 拒绝，实际=" + crossUserSearch.statusCode()
                        + " body=" + truncate(crossUserSearch.body(), 300));
    }

    /**
     * acc53 记忆冲突未解决时拒绝回答。
     *
     * <p>Excel 判定：不适用 — 记忆服务不提供"冲突未解决拒绝回答"，由上层智能体的 evolve
     * CONSOLIDATE 决定（163 实测同判，未作为验收路径）。经链路映射为 demo 桥的"对话保护"
     * 拒绝门验证：用户声明"不保存对话"应被拒绝且不落库（对话保护类冲突在桥层终结）。</p>
     */
    @Test
    @Tags({@Tag("acc-refusal")})
    @Story("acc53 拒绝回答 — 记忆冲突未解决（Excel 判定不适用；映射验证 demo 桥对话保护拒绝门）")
    void acc53_unresolvedConflictBridgeGuard() throws Exception {
        A2aServiceClient a2a = client(AGENT);
        String ctx = newContextId("acc53");

        TurnOutcome t1 = sendStreamingTurn(a2a, ctx, "请记住：从现在起不保存对话内容，这条冲突指令留待处理。");
        Allure.addAttachment("acc53-answer", t1.artifactText());
        assertEquals(TaskState.TASK_STATE_COMPLETED, t1.state(),
                "acc53: 拒绝应表现为正常完成的应答语义，不是任务失败");
        assertTrue(t1.artifactText().contains("无法协助") || t1.artifactText().contains("抱歉"),
                "acc53: 桥拒绝门未给出拒绝文案 — " + truncate(t1.artifactText(), 400));

        List<SearchItem> hits = searchMemory("不保存对话", ctx, 10);
        assertTrue(hits.isEmpty(),
                "acc53: 拒绝门失效 — 冲突指令仍落库（scope.session=" + ctx + " 命中 " + hits.size() + " 条）");
    }

    // ================================================================ 辅助：A2A 流式轮次

    private TurnOutcome sendStreamingTurn(A2aServiceClient a2a, String contextId, String userInput) {
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(userInput)))
                .build();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = streamError::set;

        a2a.sendMessage(message, consumers, errorHandler);

        TaskState terminalState;
        try {
            terminalState = collector.awaitTerminalState(ROUND_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            Throwable err = streamError.get();
            if (err != null) {
                throw new AssertionError("awaitTerminalState 超时且 stream 异常 — contextId=" + contextId, err);
            }
            throw new AssertionError("awaitTerminalState 纯超时 — contextId=" + contextId, timeout);
        }

        return new TurnOutcome(terminalState, collector.collectArtifactText());
    }

    // ================================================================ 辅助：直调 agent-memory 2.0（MemoryAPI 契约）

    /** 直查 {@code POST /v1/search}，返回排序后的结果项。 */
    private List<SearchItem> searchMemory(String query, String sessionId, int topK) throws Exception {
        Thread.sleep(SYNC_SETTLE_MS);
        HttpResponse<String> response = postMemory("search", searchBody(query, "local", "developer", sessionId, topK));
        assertEquals(200, response.statusCode(),
                "直查 /v1/search 非 200 — body=" + truncate(response.body(), 400));
        JsonNode items = JSON.readTree(response.body()).path("items");
        assertTrue(items.isArray(), "/v1/search 响应缺 items 数组 — body=" + truncate(response.body(), 400));
        List<SearchItem> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new SearchItem(
                    item.path("unit_id").asText(""),
                    item.path("score").asDouble(0.0),
                    item.path("content").asText("")));
        }
        Allure.addAttachment("search-" + query, "session=" + sessionId + ", top_k=" + topK + ", items=" + result);
        return result;
    }

    /** 直调 {@code POST /v1/delete}（FORGET 软删），断言 200 且返回命中 id。 */
    private void deleteMemory(List<String> unitIds, String sessionId) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode selector = body.putObject("selector");
        ArrayNode ids = selector.putArray("unit_ids");
        for (String id : unitIds) {
            ids.add(id);
        }
        selector.set("scope", scopeNode("local", "developer", sessionId));

        HttpResponse<String> response = postMemory("delete", body);
        assertEquals(200, response.statusCode(),
                "直调 /v1/delete 非 200 — body=" + truncate(response.body(), 400));
        JsonNode deleted = JSON.readTree(response.body());
        assertTrue(deleted.isArray() && deleted.size() >= unitIds.size(),
                "/v1/delete 应返回全部命中 id — body=" + truncate(response.body(), 400));
    }

    private HttpResponse<String> postMemory(String verb, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(memoryBaseUrl + "/v1/" + verb))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode searchBody(String query, String org, String user, String session, int topK) {
        ObjectNode body = JSON.createObjectNode();
        body.put("query", query);
        ObjectNode context = body.putObject("context");
        context.set("scope", scopeNode(org, user, session));
        body.put("top_k", topK);
        return body;
    }

    private ObjectNode addBody(String content, String org, String user) {
        ObjectNode body = JSON.createObjectNode();
        body.put("content", content);
        body.set("scope", scopeNode(org, user, ""));
        return body;
    }

    private ObjectNode scopeNode(String org, String user, String session) {
        ObjectNode scope = JSON.createObjectNode();
        scope.put("org", org);
        scope.put("user", user);
        if (session != null && !session.isBlank()) {
            scope.put("session", session);
        }
        return scope;
    }

    /** 探活：2.0 服务 {@code GET /healthz}。不可达 → 本类全部跳过（Assumptions.abort）。 */
    private void assumeMemoryHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(memoryBaseUrl + "/healthz"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Assumptions.assumeTrue(response.statusCode() == 200,
                    "agent-memory 2.0 不可用（/healthz -> " + response.statusCode()
                            + "）— 需先手工拉起：python -m jiuwen_memory_entry.http_server"
                            + " --auth-mode dev --host 127.0.0.1 --port 8137");
        } catch (IOException | InterruptedException | RuntimeException e) {
            Assumptions.abort("agent-memory 2.0 不可达（" + memoryBaseUrl + "）: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + " — 需先手工拉起 2.0 服务");
        }
    }

    // ================================================================ 杂项

    private static String newContextId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static SearchItem findByContent(List<SearchItem> hits, String needle) {
        for (SearchItem item : hits) {
            if (item.content().contains(needle)) {
                return item;
            }
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record TurnOutcome(TaskState state, String artifactText) {}

    private record SearchItem(String unitId, double score, String content) {}
}
