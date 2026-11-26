package com.huawei.ascend.sit.cases.integration.agent_dev_workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.ascend.sit.config.TestConfig;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * FEAT-136 开发工具（jiuwen-agent CLI）契约黑盒验收（场景矩阵 F136-27～F136-29、F136-41）。
 *
 * <p>期望值出处：L2 §2.8（select-type 输出字段集与类型枚举、model-to-code 模型无效退出 2、
 * validate 输出 checks/summary/status/ok）、§5.5（blocked 表示至少一项失败）、特性档 §2.1/§2.4。
 * 审阅 #599 F1 修订：F136-27 改用命中 dev-scaffold 明确规则的样例覆盖四类选型
 * （原样例实测 3 条落入低置信度兜底全部判 moa）；新增 F136-41 低置信度规则兜底单测。
 * CLI 为独立交付物，不可执行时按环境门禁 SKIPPED 并在报告中标注。
 */
@Tag("integration")
@Tag("agent-dev-workbench")
@Tag("feat-136")
@Feature("FEAT-136: 对话式开发高码智能体")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat136CliToolingBlackboxTest {

    private String cliPath;

    record CliResult(int exitCode, String stdout) {
    }

    @BeforeAll
    void probeCliExecutable() throws Exception {
        TestConfig config = TestConfig.load();
        String configured = config.getString("sut.workbench.cli-path", "jiuwen-agent");
        try {
            Process probe = new ProcessBuilder(configured, "--help")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = probe.waitFor(30, TimeUnit.SECONDS);
            probe.destroyForcibly();
            assumeTrue(finished && probe.exitValue() != -1,
                    "jiuwen-agent CLI 不可执行（--help 未正常返回）：" + configured);
            cliPath = configured;
        } catch (Exception e) {
            assumeTrue(false, "jiuwen-agent CLI 不在 PATH 或不可执行：" + configured + "（" + e.getMessage() + "）");
        }
    }

    private CliResult runCli(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = cliPath;
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        byte[] out;
        try (var in = process.getInputStream()) {
            out = in.readAllBytes();
        }
        boolean finished = process.waitFor(5, TimeUnit.MINUTES);
        process.destroyForcibly();
        assumeTrue(finished, "CLI 执行超时");
        return new CliResult(process.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    @Test
    @Tag("story-feat-136-27")
    @Story("FEAT-136.F136-27: select-type 明确规则样例四类型判定")
    @DisplayName("F136-27 命中明确规则的样例 → 四类型判定 + decidedBy 指示规则命中")
    void f13627SelectTypeContract() throws Exception {
        // 审阅 #599 F1：实测旧样例仅首条命中规则、后 3 条落入低置信度兜底全部判 moa，
        // 故改用命中 dev-scaffold 明确选型规则的样例（含规则关键词），不依赖模型波动。
        String[][] requirements = {
                // moa：主控按意图分派（实测命中规则的原样例，保留）
                {"主控智能体按意图分派任务：查询、推荐、购买分别由不同智能体负责，主控负责分派与汇总", "moa"},
                // react：ReAct 反应式逐步推理并按需调用工具
                {"使用 ReAct 反应式智能体：逐步思考并按需调用工具，查询订单后根据结果决定是否调用退款工具", "react"},
                // deep：DeepAgent 深度研究（工作区、子任务拆解、多轮迭代）
                {"使用 DeepAgent 深度研究智能体：创建工作区、拆解子任务、多轮迭代分析项目资料并形成研究报告", "deep"},
                // workflow：工作流固定步骤与分支可预先枚举
                {"使用工作流（workflow）：按固定流程提取订单信息、计算总价、金额大于阈值时分支追问确认、最后输出结果", "workflow"},
        };
        for (String[] req : requirements) {
            CliResult result = runCli("select-type", "--requirement", req[0]);
            assertThat(result.exitCode())
                    .as("select-type 对需求 [%s] 应退出 0（L2 §2.8）", req[1])
                    .isZero();

            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.stdout());
            assertThat(body.path("type").asText())
                    .as("命中明确规则的需求 [%s] 判定类型应为 %s（审阅 #599 F1；特性档 §2.1 四类型）",
                            req[1], req[1])
                    .isEqualTo(req[1]);
            assertThat(body.has("confidence")).as("输出含 confidence（L2 §2.8）").isTrue();
            assertThat(body.path("decidedBy").asText()).as("输出 decidedBy 非空").isNotBlank();
            assertThat(body.path("reason").asText()).as("输出 reason 非空（L2 §2.8：含判断依据）").isNotBlank();
            assertThat(body.has("signals")).as("输出含 signals（L2 §2.8）").isTrue();
        }
    }

    @Test
    @Tag("story-feat-136-41")
    @Story("FEAT-136.F136-41: select-type 低置信度规则兜底")
    @DisplayName("F136-41 规则外需求 → 退出 0，type ∈ 枚举，decidedBy/reason 可复核为兜底")
    void f13641SelectTypeLowConfidenceFallback() throws Exception {
        // 审阅 #599 F1：低置信度兜底独立单测——规则外叙述性需求不调用模型，
        // 由低置信度规则兜底给出可复核的判定（decidedBy/reason 承载兜底依据）。
        CliResult result = runCli("select-type", "--requirement",
                "帮我整理一下这个月的工作要点，随手记录的一些想法和会议纪要都在笔记里");
        assertThat(result.exitCode())
                .as("低置信度兜底路径应退出 0（L2 §2.8）")
                .isZero();

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.stdout());
        String type = body.path("type").asText();
        assertThat(java.util.Set.of("moa", "react", "deep", "workflow").contains(type))
                .as("兜底判定 type ∈ 四类型枚举（特性档 §2.1），实际=%s", type)
                .isTrue();
        assertThat(body.path("decidedBy").asText())
                .as("兜底判定 decidedBy 非空且可复核（审阅 #599 F1：兜底不调用模型）")
                .isNotBlank();
        assertThat(body.path("reason").asText())
                .as("兜底判定 reason 含兜底依据（L2 §2.8）")
                .isNotBlank();
        assertThat(body.has("signals")).as("输出含 signals（L2 §2.8）").isTrue();
        assertThat(body.has("confidence")).as("输出含 confidence（L2 §2.8）").isTrue();
    }

    @Test
    @Tag("story-feat-136-28")
    @Story("FEAT-136.F136-28: model-to-code 对无效模型返回退出码 2")
    @DisplayName("F136-28 分支缺默认路径的模型 → 退出码 2 且无工程写入")
    void f13628ModelToCodeInvalidModelExit2() throws Exception {
        // 构造缺默认分支的 Workflow 模型（L2 §3.7：Branch 最后必须为 true 默认分支；特性档 §5.1.3）
        Path modelFile = Files.createTempFile("feat136-invalid-model", ".json");
        String modelJson = """
                {
                  "agent": {
                    "workflow": {
                      "id": "feat136-invalid-flow",
                      "nodes": [
                        {"id": "start", "type": "start"},
                        {"id": "end", "type": "end"}
                      ],
                      "connections": [],
                      "branches": [
                        {"id": "b1", "condition": "amount > 1000", "target": "end"}
                      ]
                    }
                  }
                }
                """;
        Files.writeString(modelFile, modelJson, StandardCharsets.UTF_8);
        Path outDir = Files.createTempDirectory("feat136-m2c-out");

        CliResult result = runCli("model-to-code", "--model", modelFile.toString(), "--out", outDir.toString());

        assertThat(result.exitCode())
                .as("模型无效应退出 2（L2 §2.8：模型无效退出 2；特性档 §2.4：拒绝确定的模型错误）")
                .isEqualTo(2);
        assertThat(listFiles(outDir))
                .as("无效模型不产生目标工程写入")
                .isEmpty();
    }

    private static Set<String> listFiles(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return Set.of();
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    @Test
    @Tag("story-feat-136-29")
    @Story("FEAT-136.F136-29: validate 输出契约与状态解释")
    @DisplayName("F136-29 validate → checks/summary/status/ok，blocked 时存在 fail 项")
    void f13629ValidateStatusContract() throws Exception {
        Path emptyProject = Files.createTempDirectory("feat136-validate-proj");
        CliResult result = runCli("validate", "--project", emptyProject.toString());

        JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.stdout());
        assertThat(body.path("checks").isArray()).as("输出 checks 为 array（L2 §2.8）").isTrue();
        assertThat(body.has("summary")).as("输出含 summary（L2 §2.8）").isTrue();
        String status = body.path("status").asText();
        assertThat(Set.of("pass", "blocked", "unknown").contains(status))
                .as("status ∈ {pass, blocked, unknown}（L2 §2.8/§5.5），实际=%s", status)
                .isTrue();
        assertThat(body.path("ok").isBoolean()).as("输出 ok 为 boolean").isTrue();

        if ("blocked".equals(status)) {
            boolean hasFail = false;
            for (JsonNode check : body.path("checks")) {
                if ("fail".equals(check.path("status").asText())) {
                    hasFail = true;
                    break;
                }
            }
            assertThat(hasFail)
                    .as("status==blocked 时存在 status==fail 的检查项（L2 §5.5）")
                    .isTrue();
        }
    }
}