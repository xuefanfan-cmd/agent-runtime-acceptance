/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.component.orchestration;

import com.openjiuwen.agents.orchestration.MemberSpec;
import com.openjiuwen.agents.orchestration.SubagentTranscriptTool;
import com.openjiuwen.agents.reactrails.loopforest.execution.SubAgentExecutor;
import com.openjiuwen.da.workflow.DaTaskBoard;
import com.openjiuwen.da.workflow.DaWorkflowRunner;
import com.openjiuwen.da.workflow.SendMessageTool;
import com.openjiuwen.da.workflow.WorkflowRunTool;
import com.openjiuwen.da.workflow.WorkflowStatusTool;
import com.openjiuwen.harness.tools.ToolOutput;
import com.huawei.ascend.sit.cases.component.orchestration.OrchestrationTestSupport.Recording;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-067 六件工具组件层（L1t）用例的共用装配设施（**非测试类，不含任何 {@code @Test}**）。
 *
 * <p>为什么工具契约能在组件层验证：六件工具的公开构造器依赖**全部可注入**，因此无需拉起宿主进程、无需模型凭据。
 * 入参一律用与主 Agent 实际传入**完全相同**的 {@code Map<String,Object>} 形态。
 *
 * <p><b>🔴 构造口径必须与生产装配点逐字一致（2026-10-19 重定基线到 {@code ce9019d86} 后强制）</b>：
 * 生产装配点是 {@code DaWorkflowTools.attach}（{@code DaEngineConfiguration:162-168} 调用它）——
 * <pre>{@code
 * DaWorkflowRunner runner = new DaWorkflowRunner(defaultMemberFactory(p), p.getWorkflow().getParallelism(),
 *                                                p.getWorkflow().getRunTimeoutSeconds());   // 三参
 * attach: new WorkflowRunTool(runner, baseRoot)                                             // 两参，含 baseRoot
 *         new DelegateTaskTool(board, delegate)
 *         new TaskOutputTool(board)
 *         runner.getSendMessage()                                                           // 取 runner 持有的实例
 *         new SubagentTranscriptTool(runner.getTranscripts(), DaOrchestrationScope::currentKey,
 *                                    board::activityOf)             // 【r6】三参：换包 + ownerKey + taskLookup
 *         new WorkflowStatusTool(board, runner.getTranscripts(),
 *                                runner.getSendMessage())           // 【r6】三参：含 transcripts 与 sendMessage
 * }</pre>
 * 旧基线（{@code f9da5b54f}）下我方用例用的是少参数写法，而**新基线里少参数构造器仍然存在**
 * （所以编译能过），但产品已不走它们——产品自己的 javadoc 把它们定性为降级形态：
 * {@code WorkflowRunTool(runner)}=「构造工具（**不落盘：长输出只截断**）」、
 * {@code WorkflowStatusTool(board)}=「构造工具（**只查委托任务**）」（{@code transcripts} 为
 * 「**null 表示不查运行**」）、{@code SubagentTranscriptTool(TraceForest)} 更是**整个构造器连同
 * {@code runner.getAuditForest()} 一起被删除**。若继续用少参数写法，组件层将"全绿"地验证
 * **产品不使用的构造路径**——尤其 {@code SubagentTranscriptTool} 旧形不含 {@code ownerKey}，
 * 而 ownerKey 正是 D1（跨会话留痕泄露）修复所在的参数。
 *
 * <p>⇒ **一律经本类的 {@code newRunner}/{@code newRunTool}/{@code sendMessageOf}/{@code newTranscriptTool}/
 * {@code newStatusTool} 取工具，禁止在用例里直接 {@code new}**；一致性由
 * {@code DaWorkflowToolsWiringParityTest}（067-S24）静态门禁看守。
 *
 * <p><b>这不是 Mock LLM</b>：成员执行体由 {@code memberFactory} 注入是 L2-067 §2.6 的**生产注入点**
 * （生产走 {@code DaWorkflowRunner.defaultMemberFactory(DaProperties)}，测试注入确定性执行体）。
 * "主 Agent 能否自主够到工具"与真实模型形态由 067-S11/S14/S16（端到端）承接。
 */
final class DaWorkflowTestSupport {

    private DaWorkflowTestSupport() {
    }

    /** 一个注入了确定性成员执行体的工作流执行器夹具。 */
    static final class Fixture implements AutoCloseable {

        final DaWorkflowRunner runner;
        /** 委托任务板（与生产同：单个共享实例，供 delegate_task / task_output / workflow_status 共用）。 */
        final DaTaskBoard board;
        /** 工作区基础根（生产由 {@code DaEmbeddedTools.resolveBaseRoot} 供给；测试用临时目录）。 */
        final Path baseRoot;
        /** 成员 id → 记录型执行体（按工厂被调用时惰性登记，故用并发 Map）。 */
        final Map<String, Recording> members = new ConcurrentHashMap<>();
        private final String caseId;
        private final String run;
        private final Function<MemberSpec, SubAgentExecutor> factory;

        Fixture(final String caseId, final String run, final int parallelism,
                final Function<MemberSpec, SubAgentExecutor> overrideFactory) {
            this.caseId = caseId;
            this.run = run;
            if (overrideFactory != null) {
                this.factory = overrideFactory;
            } else {
                // 同一夹具内**跨 run 复用同一个记录型执行体**：这样"同一 Task 内两次运行"的
                // 实收输入会按调用序累积在 received 里（下标 0 = 第一次运行、1 = 第二次），
                // 067-S05/S06 的"取走即清"与"广播可达"才可直接比对。
                this.factory = member -> members.computeIfAbsent(member.id(),
                        id -> new Recording(id, OrchestrationTestSupport.marker(caseId, id, run)));
            }
            this.runner = newRunner(this.factory, parallelism);
            this.board = new DaTaskBoard(parallelism);
            try {
                this.baseRoot = Files.createTempDirectory("sit-067-baseroot-");
            } catch (IOException e) {
                throw new UncheckedIOException("应能创建 baseRoot 临时目录（生产装配需要它）", e);
            }
        }

        /** 生产口径的 {@code workflow_run}（两参，含 baseRoot）。 */
        WorkflowRunTool runTool() {
            return newRunTool(runner, baseRoot);
        }

        /** 生产口径的 {@code send_message}（runner 持有的实例，与调度器消费面同源）。 */
        SendMessageTool sendMessage() {
            return sendMessageOf(runner);
        }

        /** 生产口径的 {@code subagent_transcript}（{@code (RunTranscripts, ownerKey)}）。 */
        SubagentTranscriptTool transcriptTool(final Supplier<String> ownerKey) {
            return newTranscriptTool(runner, ownerKey, board);
        }

        /** 生产口径的 {@code workflow_status}（两参，含 transcripts）。 */
        WorkflowStatusTool statusTool() {
            return newStatusTool(board, runner);
        }

        String marker(final String memberId) {
            return OrchestrationTestSupport.marker(caseId, memberId, run);
        }

        @Override
        public void close() {
            board.close();
            // baseRoot 是测试临时目录，递归清掉，避免每轮留垃圾
            try (Stream<Path> walk = Files.walk(baseRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                    try {
                        Files.deleteIfExists(x);
                    } catch (IOException ignored) {
                        // 清理失败不影响判据，忽略
                    }
                });
            } catch (IOException ignored) {
                // 目录已不存在等情况，忽略
            }
        }
    }

    /**
     * 生产装配的 {@code workflow_run} 工具超时默认值。
     * 依据：{@code DaProperties.Workflow.runTimeoutSeconds = 1800}（{@code DaProperties.java:672}），
     * 由 {@code DaEngineConfiguration:162-164} 经**三参**构造器传入 runner，
     * 再由 {@code WorkflowRunTool.java:109} 声明进 ToolCard 的 {@code resilience.timeout_s}；
     * core {@code AbilityManager:1199-1215} 只在卡片**未声明**该键时才回落到
     * {@code DEFAULT_TOOL_CALL_TIMEOUT}（300 s）。⇒ 组件层必须用三参构造器，否则验不到这条链。
     */
    static final int PRODUCTION_RUN_TIMEOUT_SECONDS = 1800;

    /** 生产口径的 runner：**三参**构造器（{@code DaEngineConfiguration:162-164}）。 */
    static DaWorkflowRunner newRunner(final Function<MemberSpec, SubAgentExecutor> factory, final int parallelism) {
        return new DaWorkflowRunner(factory, parallelism, PRODUCTION_RUN_TIMEOUT_SECONDS);
    }

    /** 生产口径的 {@code workflow_run}：**两参**含 baseRoot（{@code DaWorkflowTools.attach:40}）。 */
    static WorkflowRunTool newRunTool(final DaWorkflowRunner runner, final Path baseRoot) {
        return new WorkflowRunTool(runner, baseRoot);
    }

    /**
     * 生产口径的 {@code send_message}：**必须**取 runner 持有的实例
     * （{@code DaWorkflowTools.attach:44} = {@code runner.getSendMessage()}，
     * 其 javadoc 明写「与调度器消费面**同源**」）。测试自建 {@code new SendMessageTool()} 会得到
     * **另一个收件箱**，留言投影链路与产品不同。
     */
    static SendMessageTool sendMessageOf(final DaWorkflowRunner runner) {
        return runner.getSendMessage();
    }

    /**
     * 生产口径的 {@code subagent_transcript}：{@code (RunTranscripts, Supplier<String> ownerKey)}
     * （{@code DaWorkflowTools.attach:45-46}，ownerKey 生产取 {@code DaOrchestrationScope::currentKey}）。
     * {@code DaOrchestrationScope} 是 package-private，测试无法引用 ⇒ 由用例**显式传入 ownerKey**，
     * 这反而让 D1 的会话分区判据可直接构造（两个不同 owner 互相取不到根）。
     */
    /**
     * 生产口径的 {@code subagent_transcript}：**三参**（{@code DaWorkflowTools.attach:44-45}）——
     * {@code (RunTranscripts, ownerKey, taskLookup)}。
     *
     * <p>【2026-10-26 r6 适配】r6 起 **2 参重载已被删除**（`SubagentTranscriptTool.java:66-69` 只剩三参），
     * 第三参 {@code taskLookup} 是「委托任务 id → 执行体记录 {@code MemberActivity}」的查询函数
     * （只查当前会话、在工具线程上调用）。生产传 {@code board::activityOf}。
     * <b>fixture 也传 {@code board::activityOf}（同一个 {@code DaTaskBoard} 实例），不用
     * {@code id -> Optional.empty()} 之类的桩</b>——用桩会让"所查分支带 member 活动"这条产品能力
     * 在组件层永远测不到，等于悄悄削弱判据（RULES：不得为让用例转绿而降低取证强度）。
     */
    static SubagentTranscriptTool newTranscriptTool(final DaWorkflowRunner runner, final Supplier<String> ownerKey,
            final DaTaskBoard board) {
        return new SubagentTranscriptTool(runner.getTranscripts(), ownerKey, board::activityOf);
    }

    /**
     * 生产口径的 {@code workflow_status}：**三参**含 transcripts 与 sendMessage（{@code DaWorkflowTools.attach:46}）。
     *
     * <p>【2026-10-26 r6 适配】由两参升为三参（新增 {@code runner.getSendMessage()}——留言投递面并入任务板查询）。
     */
    static WorkflowStatusTool newStatusTool(final DaTaskBoard board, final DaWorkflowRunner runner) {
        return new WorkflowStatusTool(board, runner.getTranscripts(), runner.getSendMessage());
    }

    static Fixture fixture(final String caseId, final String run) {
        return new Fixture(caseId, run, 4, null);
    }

    static Fixture fixture(final String caseId, final String run, final int parallelism) {
        return new Fixture(caseId, run, parallelism, null);
    }

    /** 用自定义成员工厂建夹具（例如构造"面外异常逃逸"场景）。 */
    static Fixture fixtureWithFactory(final String caseId, final String run,
            final Function<MemberSpec, SubAgentExecutor> factory) {
        return new Fixture(caseId, run, 4, factory);
    }

    /** 调用工具并把返回信封拆成 {@link ToolOutput}（同时断言信封形态合法）。 */
    static ToolOutput output(final Object raw) {
        assertThat(raw).as("工具 invoke 应返回 ToolOutput 信封（返回其它类型即契约变形）")
                .isInstanceOf(ToolOutput.class);
        return (ToolOutput) raw;
    }

    /** 断言成功路径并取出 data 映射。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> dataOf(final ToolOutput out) {
        assertThat(out.isSuccess()).as("成功路径 isSuccess 应为 true；error=%s", out.getError()).isTrue();
        assertThat(out.getData()).as("成功路径应带 data").isInstanceOf(Map.class);
        return (Map<String, Object>) out.getData();
    }

    /** 断言失败路径并取出 error 文案（同时断言文案可读：非空）。 */
    static String errorOf(final ToolOutput out) {
        assertThat(out.isSuccess()).as("失败路径 isSuccess 应为 false").isFalse();
        assertThat(out.getError()).as("失败路径应带可读 error 文案").isNotNull().isNotBlank();
        return out.getError();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> stepsOf(final Map<String, Object> data) {
        assertThat(data.get("steps")).as("出参应含 steps").isInstanceOf(List.class);
        return (List<Map<String, Object>>) data.get("steps");
    }

    /** 单兵图纸（1 步），用于"图纸直给"与快速跑通场景。 */
    static String singleMemberBlueprint(final String id, final String memberId) {
        return """
                blueprint:
                  id: %s
                  type: single
                  members:
                    - {id: %s, instruction: "独立作答"}
                """.formatted(id, memberId);
    }

    /** 三成员依赖图图纸，用于广播可达性与留言投影场景。 */
    static String threeMemberDag(final String id) {
        return """
                blueprint:
                  id: %s
                  type: dag
                  members:
                    - {id: mA, instruction: "成员甲作答"}
                    - {id: mB, instruction: "成员乙作答"}
                    - {id: mC, instruction: "成员丙作答"}
                  steps:
                    - {id: sA, member: mA}
                    - {id: sB, member: mB}
                    - {id: sC, member: mC, depends_on: [sA, sB]}
                """.formatted(id);
    }

    /** 便于各用例复用的空绑定表（构造"工厂抛异常"等场景时用）。 */
    static Map<String, SubAgentExecutor> emptyBindings() {
        return new LinkedHashMap<>();
    }
}
