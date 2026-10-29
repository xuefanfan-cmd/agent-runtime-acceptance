/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat032_multimodal;

import com.openjiuwen.core.context.context.SessionModelContext;
import com.openjiuwen.core.context.schema.ContextEngineConfig;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.ModelInvokeOptions;
import com.openjiuwen.core.foundation.llm.modelclients.OpenAiModelClientFactory;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.example.multimodal.tools.DemoModelConfig;
import com.openjiuwen.example.multimodal.tools.MultimodalToolsAgent;
import com.openjiuwen.example.multimodal.tools.SampleImages;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * FEAT-032 全链路直驱后端：基于 agent-solution
 * {@code common/example/multi-modal-tools-demo} 的 {@link MultimodalToolsAgent}
 * （ObservationToolSuite 六工具 + vote_answer 装配的真实 ReActAgent）执行验收场景
 * （测试设计 F032-01/02/12/13/14/15/22）。
 *
 * <p><b>请求捕获</b>（L2 §7 验收方法学「记录型 ModelInvoker」）：以
 * {@link Model#registerClientFactory} 为本实例专属 provider 名注册包装工厂——每次进入
 * 模型调用的消息先入捕获列表，再原样委托真实 OpenAI 兼容客户端（真实网关流量不变）；
 * 离线模式（无网关环境变量）委托固定应答客户端，供 F032-14 等离线场景使用。密钥仅经
 * 进程环境变量注入，不写入捕获内容、不落盘。
 *
 * <p>实现仅调用 demo 模块与 core 的公开 API（MultimodalToolsAgent.create/agent/ask/close、
 * ReActAgent.invoke），不反射产品内部状态。
 *
 * @since 0.1.2
 */
final class Feat032DemoAgentInvoker implements Feat032SutAgentInvoker {

    /** 演示样张（含可判定印刷码：小字号销售表 + 竖排批注），取自 demo 模块 SampleImages。 */
    static final Path SAMPLE_PAGE = createSamplePage();

    private static final String OFFLINE_FAKE_KEY = "offline-acceptance-not-a-real-key";

    private final DemoModelConfig config;
    private final boolean offline;
    private final OpenAiModelClientFactory delegateFactory = new OpenAiModelClientFactory();
    private final List<MultimodalToolsAgent> agents = new ArrayList<>();
    private final List<CapturedModelCall> currentRun = new ArrayList<>();

    private Feat032DemoAgentInvoker(DemoModelConfig config, boolean offline) {
        this.config = config;
        this.offline = offline;
        // 记录型客户端工厂：专属 provider 名（即 config.provider()），demo agent 主循环与
        // vote_answer 的模型客户端都经此工厂创建——消息先入捕获列表再委托真实客户端
        Model.registerClientFactory(config.provider(), (requestConfig, clientConfig) -> {
            Model.ModelClient delegate = offline
                    ? new FixedAnswerClient()
                    : delegateFactory.create(requestConfig, clientConfig);
            return new RecordingClient(delegate, currentRun);
        });
    }

    /**
     * 创建 invoker；验证基座 agent 制品缺失时返回 null（调用方按 dependency-gated 处理）。
     *
     * @return invoker 或 null
     */
    static Feat032DemoAgentInvoker create() {
        if (!Feat032SutSupport.demoAgentPresent() || !Feat032SutSupport.solutionToolsPresent()) {
            return null;
        }
        String key = Feat032SutSupport.visionApiKey();
        String base = Feat032SutSupport.visionApiBase();
        boolean offline = key == null || base == null;
        // 离线模式以占位 key 通过 requireApiKey 门禁；模型客户端经记录型工厂返回固定应答
        // SSL 校验默认关闭（验收网关为私有 CA 证书），仅 VISION_SSL_VERIFY=true 时开启
        DemoModelConfig demoConfig = new DemoModelConfig(
                "Feat032Recording-" + UUID.randomUUID(),
                offline ? OFFLINE_FAKE_KEY : key,
                offline ? "http://127.0.0.1:9/v1" : base,
                Feat032SutSupport.visionModel(),
                "true".equalsIgnoreCase(System.getenv("VISION_SSL_VERIFY")),
                0.0d, 0.7d, 4096);
        return new Feat032DemoAgentInvoker(demoConfig, offline);
    }

    /** 是否离线模式（无真实网关环境变量）。 */
    boolean isOffline() {
        return offline;
    }

    // ------------------------------------------------------------------
    // Feat032SutAgentInvoker 场景实现
    // ------------------------------------------------------------------

    @Override
    public CapturedRun invokeWithStructuredEntry(List<Map<String, Object>> contentBlocks) {
        MultimodalToolsAgent demoAgent = newDemoAgent();
        try {
            currentRun.clear();
            demoAgent.agent().invoke(new ArrayList<>(contentBlocks),
                    new Feat032Pr404EntryDirectTest.TestSession());
            return finishRun();
        } finally {
            release(demoAgent);
        }
    }

    @Override
    public CapturedRun invokeWithToolLoop(String toolId, Map<String, Object> toolArgs) {
        MultimodalToolsAgent demoAgent = newDemoAgent();
        try {
            currentRun.clear();
            String conversationId = "feat032-loop-" + UUID.randomUUID();
            String query = toolLoopQuery(toolId, toolArgs);
            Object result = demoAgent.ask(query, conversationId);
            CapturedRun run = finishRun();
            return new CapturedRun(run.modelCalls(), answerOf(result), null);
        } finally {
            release(demoAgent);
        }
    }

    @Override
    public CapturedRun invokeAfterPersistReload(List<Map<String, Object>> contentBlocks) {
        MultimodalToolsAgent demoAgent = newDemoAgent();
        try {
            currentRun.clear();
            // 第 1 次：结构化输入进入模型调用（多模态 user 消息进入会话上下文）
            demoAgent.agent().invoke(new ArrayList<>(contentBlocks),
                    new Feat032Pr404EntryDirectTest.TestSession());

            // 持久化往返：SessionModelContext saveState → 新上下文 loadState（离线可执行）
            UserMessage multimodalUser = multimodalUserMessage(contentBlocks);
            SessionModelContext persisted = new SessionModelContext("feat032-ctx", "feat032-session",
                    new ContextEngineConfig(), List.of(multimodalUser), List.of(), null);
            Map<String, Object> state = persisted.saveState();
            SessionModelContext reloaded = new SessionModelContext("feat032-ctx", "feat032-session",
                    new ContextEngineConfig(), List.of(), List.of(), null);
            // loadState 按 contextId 嵌套取数（多上下文持久化格式）：{contextId: {messages, offload_messages}}
            reloaded.loadState(Map.of("feat032-ctx", state));

            // 第 2 次（回载后）：同结构化输入再次发起模型调用，仍按 wire parts 编码出图片块
            demoAgent.agent().invoke(new ArrayList<>(contentBlocks),
                    new Feat032Pr404EntryDirectTest.TestSession());
            CapturedRun run = finishRun();

            // 回载后 user content 仍为 List 结构（不退化为 toString 字符串）
            List<BaseMessage> messages = reloaded.getMessages();
            Object reloadedContent = null;
            for (BaseMessage message : messages) {
                if (message instanceof UserMessage user) {
                    reloadedContent = user.getContent();
                }
            }
            CapturedModelCall reloadEvidence = new CapturedModelCall(
                    List.of(Map.of("type", "reloadedUserContent", "value", String.valueOf(reloadedContent),
                            "isList", Boolean.toString(reloadedContent instanceof List))),
                    List.of(), "persist-roundtrip");
            List<CapturedModelCall> calls = new ArrayList<>(run.modelCalls());
            calls.add(reloadEvidence);
            return new CapturedRun(calls, run.finalAnswer(), null);
        } finally {
            release(demoAgent);
        }
    }

    @Override
    public CapturedRun invokeAgainstModel(String modelId, List<Map<String, Object>> contentBlocks) {
        // 非视觉模型目标：仅改模型 ID，其余配置不变（真实网关）
        DemoModelConfig targetConfig = new DemoModelConfig(config.provider(), config.apiKey(),
                config.apiBase(), modelId, config.isSslVerify(), config.agentTemperature(),
                config.voteTemperature(), config.maxTokens());
        MultimodalToolsAgent demoAgent = MultimodalToolsAgent.create(targetConfig);
        agents.add(demoAgent);
        try {
            currentRun.clear();
            try {
                Object result = demoAgent.agent().invoke(new ArrayList<>(contentBlocks),
                        new Feat032Pr404EntryDirectTest.TestSession());
                CapturedRun run = finishRun();
                return new CapturedRun(run.modelCalls(), answerOf(result), null);
            } catch (RuntimeException e) {
                // F032-15：模型/框架显式拒绝（如网关 81001「unsupported content type: image_url」）
                // 记入 modelCallError，供断言区分「显式不兼容错误」与「静默丢图继续作答」。
                List<CapturedModelCall> calls = new ArrayList<>(currentRun);
                currentRun.clear();
                return new CapturedRun(calls, null, String.valueOf(e.getMessage()));
            }
        } finally {
            release(demoAgent);
        }
    }

    @Override
    public void close() {
        for (MultimodalToolsAgent agent : agents) {
            try {
                agent.close();
            } catch (RuntimeException ignore) {
                // 卸载失败不影响其余清理
            }
        }
        agents.clear();
    }

    // ------------------------------------------------------------------
    // 内部装配与捕获
    // ------------------------------------------------------------------

    private MultimodalToolsAgent newDemoAgent() {
        MultimodalToolsAgent demoAgent = MultimodalToolsAgent.create(config);
        // demo agent 未配置 materialAccess：入口 path 块需 allowedRoots 覆盖样张目录，
        // 否则 access 阶段 SOURCE_UNRESOLVED。在既有 config 上追加，不改动 demo 其他配置。
        try {
            Object rawConfig = demoAgent.agent().getConfig();
            if (rawConfig instanceof com.openjiuwen.core.singleagent.agents.ReActAgentConfig agentConfig) {
                com.openjiuwen.core.foundation.llm.multimodal.MaterialAccessConfig access =
                        agentConfig.getMaterialAccess() != null
                                ? agentConfig.getMaterialAccess()
                                : new com.openjiuwen.core.foundation.llm.multimodal.MaterialAccessConfig();
                java.util.List<String> roots = new java.util.ArrayList<>(
                        access.getAllowedRoots() != null ? access.getAllowedRoots() : java.util.List.of());
                roots.add(SAMPLE_PAGE.getParent().toString());
                access.setAllowedRoots(roots);
                agentConfig.configureMaterialAccess(access);
                demoAgent.agent().configure(agentConfig);
            }
        } catch (RuntimeException ex) {
            // 配置追加失败不阻断（path 块场景将显式失败）
        }
        agents.add(demoAgent);
        return demoAgent;
    }

    private void release(MultimodalToolsAgent demoAgent) {
        try {
            demoAgent.close();
        } finally {
            agents.remove(demoAgent);
        }
    }

    private CapturedRun finishRun() {
        List<CapturedModelCall> calls = new ArrayList<>(currentRun);
        currentRun.clear();
        String lastResponse = calls.isEmpty() ? "" : calls.get(calls.size() - 1).responseText();
        return new CapturedRun(calls, lastResponse, null);
    }

    /** 工具环查询串：与 demo 的 buildQuery 同构（图片路径 + 问题），由模型自主决定工具调用。 */
    private static String toolLoopQuery(String toolId, Map<String, Object> toolArgs) {
        if ("desktop_screenshot".equals(toolId)) {
            return "请调用 desktop_screenshot 截取本机桌面（screenId=" + toolArgs.getOrDefault("screenId", 0)
                    + "），然后用一句话说明屏幕上最显眼的元素。";
        }
        Object imagePath = toolArgs.get("imagePath");
        String question = String.valueOf(toolArgs.getOrDefault("question",
                "图片里右下角表格中 East 区域 Q3 的销售额是多少？请先用 image_observe 查看图片再回答。"));
        return "图片：\n1. " + imagePath + "\n\n问题：" + question;
    }

    /** Agent 结果 → 最终回复文本（demo outputOf 同构投影，兼容嵌套 Map 形态）。 */
    private static String answerOf(Object result) {
        if (result instanceof Map<?, ?> map && map.containsKey("output")) {
            Object output = map.get("output");
            if (output instanceof Map<?, ?> nested && nested.containsKey("output")) {
                return String.valueOf(nested.get("output"));
            }
            return String.valueOf(output);
        }
        return String.valueOf(result);
    }

    /** 由场景输入重建多模态 UserMessage（持久化往返 fixture 用，经 core 契约 wire 形态）。 */
    private static UserMessage multimodalUserMessage(List<Map<String, Object>> contentBlocks) {
        UserMessage message = new UserMessage("");
        List<Object> parts = new ArrayList<>(contentBlocks);
        message.setContent(parts);
        return message;
    }

    private static Path createSamplePage() {
        try {
            return SampleImages.writeSamplePage(Files.createTempDirectory("feat032-sample"));
        } catch (Exception e) {
            throw new IllegalStateException("demo SampleImages 样张生成失败", e);
        }
    }

    /** 记录型客户端：消息先入捕获列表，再委托真实/固定客户端（密钥不入捕获内容）。 */
    private static final class RecordingClient implements Model.ModelClient {
        private final Model.ModelClient delegate;
        private final List<CapturedModelCall> sink;

        RecordingClient(Model.ModelClient delegate, List<CapturedModelCall> sink) {
            this.delegate = delegate;
            this.sink = sink;
        }

        @Override
        public CompletionStage<AssistantMessage> invoke(List<BaseMessage> messages,
                                                        ModelInvokeOptions options) {
            CompletionStage<AssistantMessage> future = delegate.invoke(messages, options);
            future.thenAccept(response -> sink.add(project(messages, response)));
            return future;
        }

        @Override
        public Iterator<AssistantMessageChunk> stream(List<BaseMessage> messages,
                                                      ModelInvokeOptions options) {
            sink.add(project(messages, null));
            return delegate.stream(messages, options);
        }

        private static CapturedModelCall project(List<BaseMessage> messages,
                                                 AssistantMessage response) {
            List<Map<String, Object>> userParts = new ArrayList<>();
            List<String> toolTexts = new ArrayList<>();
            for (BaseMessage message : messages) {
                if (message instanceof UserMessage user) {
                    userParts.addAll(userPartsOf(user));
                } else if (message instanceof ToolMessage tool) {
                    toolTexts.add(String.valueOf(tool.getContent()));
                }
            }
            return new CapturedModelCall(userParts, toolTexts,
                    response == null ? "" : String.valueOf(response.getContent()));
        }

        private static List<Map<String, Object>> userPartsOf(UserMessage user) {
            List<Map<String, Object>> parts = new ArrayList<>();
            Object content = user.getContent();
            if (content instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof Map<?, ?> map) {
                        Map<String, Object> part = new LinkedHashMap<>();
                        map.forEach((key, value) -> part.put(String.valueOf(key), value));
                        parts.add(part);
                    }
                }
            } else if (content != null) {
                parts.add(Map.of("type", "text", "text", String.valueOf(content)));
            }
            return parts;
        }
    }

    /** 离线固定应答客户端（F032-14 等离线场景；不访问网络、不消耗网关额度）。 */
    private static final class FixedAnswerClient implements Model.ModelClient {
        @Override
        public CompletionStage<AssistantMessage> invoke(List<BaseMessage> messages,
                                                        ModelInvokeOptions options) {
            return CompletableFuture.completedFuture(new AssistantMessage("ok"));
        }
    }
}
