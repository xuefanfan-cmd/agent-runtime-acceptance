package com.huawei.ascend.sit.cases.integration.deepagent_deepresearch;

import com.huawei.ascend.sit.client.A2aEventCollector;
import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.utils.RedisProbe;
import io.qameta.allure.Feature;
import io.qameta.allure.Stories;
import io.qameta.allure.Story;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * FEAT-003.kv-todo-sessionid-isolation — KV Todolist 存储层必须按 sessionId 分片,
 * 两个不同 sessionId 的会话所产生的 todo 不应互相覆盖.
 *
 * <p><b>Spec 依据</b>: {@code version-scope/FEAT-003-agent-task-state-cache.md} §5.1.2 隔离维度 /
 * §5.1.6 MUST #3 task-level 隔离 —— sessionId 是 KV 存储分片的前置维度. 若 sessionId 恒为 fallback
 * 常量 {@code "default"}, 则多个并发/串行会话会撞进同一 KV 命名空间, 隔离维度直接破坏.
 *
 * <p><b>Bug 说明</b> (参考 openjiuwen-java 的
 * {@code multi-deep-research-demo/feat-003-evidence/ISSUE_DRAFT_kv-todo-sessionid-fallback.md} 场景 B/C):
 * {@code TaskPlanningRail.init()} 用只接受 {@code inputs} 的 {@code LocalFunction} 构造器装配 4 个 todo_*
 * tool, lambda 内部 {@code sessionId(inputs)} 从 LLM tool-call arguments 读 {@code "session_id"} 字段;
 * 但 tool schema / prompt 均未暴露 sessionId 给 LLM, LLM 从不填, 于是每次都走 fallback 常量 {@code "default"}.
 * 所有 todo 都落到 {@code default:todo} 单一 Redis key.
 *
 * <p><b>拓扑</b>:
 * <pre>
 *   [test] --SendMessage(round 1: sid=A, prompt=P1)--\
 *                                                     +--> [deep-research :redis-checkpointer] --> [search]
 *   [test] --SendMessage(round 2: sid=B, prompt=P2)--/                 |
 *                                                                       v
 *                                                                    [Redis]
 *                                                                       ^
 *   [test] --RedisProbe.keys("*todo*") + assertions ----------------------
 * </pre>
 *
 * <p><b>断言层次</b>:
 * <ol>
 *   <li><b>层 1 (spec 真相, 修复后应绿)</b>: SCAN {@code *todo*} 命中的 key 集合应<b>同时</b>包含两个
 *       sessionId 的前缀 (证明存储层按 sessionId 分片). 未修复时红.</li>
 *   <li><b>层 2 (bug 指纹, 未修复时命中)</b>: {@code default:todo} / {@code default:*todo*} 命中 → smoking gun.
 *       修复后应<b>不再命中</b>.</li>
 *   <li><b>层 3 (前置健康度)</b>: Redis DBSIZE > 0 且至少存在一个 todo 相关 key (证明 KV Todo storage
 *       provider 确实被激活, 触发前提成立).</li>
 * </ol>
 *
 * <p><b>为什么 red-first 是价值</b>: 该用例编写时上游 bug 尚未修复; 首次运行时层 1 红 + 层 2 命中,
 * 正是对 ISSUE_DRAFT 断言链的独立 SIT 侧复现证据. 上游修复后自动转绿.
 *
 * <p><b>Tag 说明</b>: {@code manual} —— 需真 LLM (deep-research 的 planner rail 必须真的走一遍生成
 * todo_* tool 调用) + 本地 deep-research/search jar. 不适合无 LLM 的 CI 扫描. 若干扰 UT/IT 快速回归,
 * 也可通过 tag 过滤掉.
 */
@Tag("integration")
@Tag("deepagent")
@Tag("feat-003")
@Tag("manual")
@Feature("FEAT-003: 智能体任务状态缓存")
@Stories({
        @Story("da.kv-todo-sessionid-isolation: KV Todo 存储须按 sessionId 分片, 不允许 fallback 常量撞库"),
        @Story("da.kv-todo-two-sessions-no-collide: 两个不同 sessionId 不应撞同一存储命名空间")
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KvTodoSessionIdIsolationTest {

    private static final String DEEP_RESEARCH = "deep-research";
    private static final String SEARCH = "search";
    private static final long ROUND_TIMEOUT_MS = 300_000;

    /** Redis 端口暴露的 service 名 (application-local.yml sut.services.redis). */
    private static final String REDIS = "redis";

    /**
     * 两轮不同 prompt —— 主题差异越大, 越能在 artifact / Redis 值里区分, 也让 planner 更倾向于生成 todo.
     * 与 ISSUE_DRAFT 场景 B 复用同款主题 (DeepSeek/qwen-max vs GLM/Kimi).
     */
    private static final String PROMPT_ROUND_1 = "对比 DeepSeek R1 和 qwen-max 定价";
    private static final String PROMPT_ROUND_2 = "对比 GLM 4.5 和 Kimi 定价";

    /** Bug 指纹: KV Todo 存储 fallback 常量 "default". */
    private static final String BUG_FALLBACK_TOKEN = "default";

    /** 全局 SCAN 玻璃 —— 覆盖 {@code <sessionId>:todo} / {@code <tenant>:<sessionId>:todo} 两种命名. */
    private static final String TODO_KEY_GLOB = "*todo*";

    private TestConfig config;
    private SutStack searchStack;
    private SutStack deepStack;

    @BeforeAll
    void startStack() {
        config = TestConfig.load();
        // 双 stack pattern (同 MultiTurnSearchFollowupTest):search 先起, 拿到 baseUrl 后
        // 用 SEARCH_AGENT_URL env 注入 deep-research. Redis 只挂在 deep-research 上——
        // search-agent 不使用 KV Todo storage, 也不需要 checkpointer.
        searchStack = SutStack.builder(config).agent(SEARCH).start();
        String searchBaseUrl = searchStack.baseUrl(SEARCH);

        deepStack = SutStack.builder(config)
                .agent(DEEP_RESEARCH, a -> a
                        .profile("redis-checkpointer")
                        .serviceBinding(REDIS, "REDIS_HOST", "{{host}}")
                        .serviceBinding(REDIS, "REDIS_PORT", "{{port}}")
                        .env("SEARCH_AGENT_URL", searchBaseUrl))
                .start();
    }

    @AfterAll
    void tearDown() {
        if (deepStack != null) {
            deepStack.close();
        }
        if (searchStack != null) {
            searchStack.close();
        }
    }

    @Test
    @DisplayName("FEAT-003.kv-todo-sessionid-isolation: 两个不同 sessionId 的会话 todo 不应撞进同一 KV 命名空间 "
            + "(未修复时 Redis 只见 default:todo — smoking gun)")
    void twoSessionsShouldNotCollideInDefaultNamespace() {
        A2aServiceClient a2a = deepStack.client(DEEP_RESEARCH);

        String sessionA = "kv-todo-iso-A-" + UUID.randomUUID().toString().substring(0, 8);
        String sessionB = "kv-todo-iso-B-" + UUID.randomUUID().toString().substring(0, 8);

        // Round 1: sessionA + prompt1
        TaskState stateA = sendOneRound(a2a, sessionA, PROMPT_ROUND_1);
        // Round 2: sessionB + prompt2 (完全不同的 sessionId + 完全不同的 topic)
        TaskState stateB = sendOneRound(a2a, sessionB, PROMPT_ROUND_2);

        RedisProbe probe = redisProbe();
        long dbsize = probe.dbsize();
        List<String> todoKeys = probe.keys(TODO_KEY_GLOB);

        // 层 3 (前置): KV Todo storage provider 应被激活, 至少有一个 todo 相关 key
        //   —— 若为 0 则说明本轮 planner 根本没走 todo_* 路径 (可能 LLM 走了其他策略), 层 1/2 判读无效
        assertThat(dbsize)
                .as("FEAT-003.kv-todo-sessionid-isolation [层3 前置]: Redis DBSIZE > 0 (KV backend 已激活)")
                .isGreaterThan(0);
        assertThat(todoKeys)
                .as("FEAT-003.kv-todo-sessionid-isolation [层3 前置]: 应至少存在一个 todo 相关 key\n"
                        + "  dbsize=%d\n  glob=%s\n  hit=%s", dbsize, TODO_KEY_GLOB, todoKeys)
                .isNotEmpty();

        // 层 2 (bug 指纹, 未修复时命中): 若 Redis 只见 `default:todo`, 那就是 sessionId fallback 到常量的 smoking gun
        //   修复后此断言应过 (todoKeys 里不应仍然出现 default 命名空间的 todo).
        List<String> defaultNsHits = todoKeys.stream()
                .filter(k -> k.toLowerCase().contains(BUG_FALLBACK_TOKEN))
                .toList();
        assertThat(defaultNsHits)
                .as("FEAT-003.kv-todo-sessionid-isolation [层2 bug 指纹]: 存在 KV Todo key 落到 fallback 常量 "
                        + "'%s' 命名空间 → 违反 FEAT-003 v2 spec §5.1.6 MUST #3 (sessionId 维度失效)\n"
                        + "  fallback-token=%s\n  all-todo-keys=%s\n  sessionA=%s\n  sessionB=%s\n"
                        + "  stateA=%s\n  stateB=%s\n"
                        + "  参考: openjiuwen-java multi-deep-research-demo/feat-003-evidence/"
                        + "ISSUE_DRAFT_kv-todo-sessionid-fallback.md 场景 C",
                        BUG_FALLBACK_TOKEN, BUG_FALLBACK_TOKEN, todoKeys,
                        sessionA, sessionB, stateA, stateB)
                .isEmpty();

        // 层 1 (spec 真相, 修复后应绿): 两个 sessionId 的 key 都应能在 Redis 中被 SCAN 到
        //   若 sessionA 的 key 存在但 sessionB 的 key 不存在 → 说明第二轮的 todo 覆盖了第一轮 (scenario B)
        //   若两个 sessionId 的 key 都不存在 → 说明 sessionId 维度完全失效 (scenario C)
        boolean seenA = todoKeys.stream().anyMatch(k -> k.contains(sessionA));
        boolean seenB = todoKeys.stream().anyMatch(k -> k.contains(sessionB));
        assertThat(seenA && seenB)
                .as("FEAT-003.kv-todo-sessionid-isolation [层1 spec 真相]: 两个 sessionId 的 KV Todo key "
                        + "应<b>同时</b>在 Redis 中出现, 证明存储层按 sessionId 分片\n"
                        + "  sessionA=%s → seen=%b\n  sessionB=%s → seen=%b\n"
                        + "  all-todo-keys=%s\n"
                        + "  修复方向 (Option A/B, 见 ISSUE_DRAFT §修复方案): rail 装 tool 时改用带 kwargs 的"
                        + " LocalFunction 构造器, 或从 SessionContextHolder ThreadLocal 取 sessionId",
                        sessionA, seenA, sessionB, seenB, todoKeys)
                .isTrue();
    }

    /**
     * 单轮 sendMessage + await 终态. 不硬断具体终态类型 —— 只要不是 timeout / stream 异常即可,
     * 因为对本用例来说 <b>Redis 是否按 sessionId 分片</b>比 <b>终态是否 COMPLETED</b> 更根本.
     * (即使 planner 中途 failed, 只要走过 todo_create, 覆盖点就存在.)
     */
    private TaskState sendOneRound(A2aServiceClient a2a, String contextId, String prompt) {
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(contextId)
                .parts(List.of(new TextPart(prompt)))
                .build();

        A2aEventCollector collector = new A2aEventCollector();
        AtomicReference<Throwable> sendError = new AtomicReference<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = List.of(collector.createConsumer());
        Consumer<Throwable> errorHandler = sendError::set;

        a2a.sendMessage(message, consumers, errorHandler);

        try {
            return collector.awaitTerminalState(ROUND_TIMEOUT_MS);
        } catch (ConditionTimeoutException timeout) {
            Throwable err = sendError.get();
            if (err != null) {
                fail("FEAT-003.kv-todo-sessionid-isolation: sendOneRound 超时且 stream 期间发生异常"
                        + " — contextId=" + contextId, err);
            }
            fail("FEAT-003.kv-todo-sessionid-isolation: sendOneRound 纯超时 — contextId=" + contextId, timeout);
            return null;
        }
    }

    private RedisProbe redisProbe() {
        URI u = URI.create(deepStack.serviceUrl(REDIS));
        return new RedisProbe(u.getHost(), u.getPort());
    }
}
