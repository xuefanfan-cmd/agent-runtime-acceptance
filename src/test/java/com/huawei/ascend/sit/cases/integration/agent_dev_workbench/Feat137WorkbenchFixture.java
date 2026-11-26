package com.huawei.ascend.sit.cases.integration.agent_dev_workbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Comparator;

/**
 * FEAT-137 黑盒用例共享 Fixture：进程内构造工程目录变体 + SHA-256 指纹计算。
 *
 * <p>工程文件是业务定义唯一来源（特性档 §5.1.1）；fixture 内容只写公开可观察的
 * 规范写法示例，不触碰 SUT 内部状态。期望值出处见
 * docs/cases/FEAT-137-agent-structure-visualization-source-linkage.md 场景矩阵。
 */
final class Feat137WorkbenchFixture {

    private Feat137WorkbenchFixture() {
    }

    /** 6 位随机 hex 运行后缀。 */
    static String runId() {
        SecureRandom random = new SecureRandom();
        return String.format("%06x", random.nextInt(0x1000000));
    }

    /** 原始字节 SHA-256（小写 hex，L2 §2.3：fingerprint 为原始文件字节的 SHA-256 指纹）。 */
    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 创建空的 fixture 工程目录（F137-10）。 */
    static Path createEmptyProject(String variant) throws IOException {
        return Files.createTempDirectory("feat137-" + variant + "-");
    }

    /**
     * MOA 候选配置工程（L2 §3.2 次序 1：候选配置目录存在 YAML 为确定证据）。
     *
     * <p>识别依据（实现仓 ProjectModel.hasCandidateYaml）：`src/main/resources/candidates/`
     * 下存在 YAML；EDP 主控与候选按规范写法取自 `application.yml`
     * （`openjiuwen.service.agent-id` 主控 + `openjiuwen.service.a2a.remote-agents` 候选）。
     */
    static Path createMoaProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-moa-");
        Path candidates = Files.createDirectories(
                root.resolve("src/main/resources/candidates"));
        Files.write(candidates.resolve("master-agent.yaml"), (
                "# FEAT-137 fixture: MOA 主控智能体候选声明（工程文件为业务定义来源）\n"
                + "agent-id: feat137-moa-master\n"
                + "role: master\n").getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.write(root.resolve("src/main/resources/application.yml"), (
                "# FEAT-137 fixture: MOA 主控 + A2A 候选（EDP 主控与候选核对来源）\n"
                + "openjiuwen:\n"
                + "  service:\n"
                + "    agent-id: feat137-moa-master\n"
                + "    a2a:\n"
                + "      remote-agents:\n"
                + "        - name: feat137-moa-worker-a\n"
                + "          url: http://127.0.0.1:19001/a2a/card\n"
                + "        - name: feat137-moa-worker-b\n"
                + "          url: http://127.0.0.1:19002/a2a/card\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /**
     * ReAct 特征工程（L2 §3.2 次序 4）。工具声明按实现仓 ToolCard.builder() 规范 DSL
     * （含 inputParams 输入声明），工具 source 引用工具类源文件（审阅整改项：工具及输入声明核对）。
     */
    static Path createReactProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-react-");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.write(src.resolve("DemoReactAgent.java"), (
                "package demo;\n\n"
                + "/** FEAT-137 fixture: ReAct 智能体声明。 */\n"
                + "public class DemoReactAgent {\n\n"
                + "    private static final String AGENT_ID = \"feat137-react-demo\";\n\n"
                + "    public ReActAgent build() {\n"
                + "        return ReActAgent.builder()\n"
                + "            .name(\"feat137-react-demo\")\n"
                + "            .description(\"feat137 ReAct demo agent\")\n"
                + "            .tool(new OrderQueryTool())\n"
                + "            .tool(new RefundTool())\n"
                + "            .build();\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(src.resolve("OrderQueryTool.java"), (
                "package demo;\n\n"
                + "import java.util.Map;\n\n"
                + "/** FEAT-137 fixture: 订单查询工具（输入声明：orderId）。 */\n"
                + "public class OrderQueryTool {\n\n"
                + "    public static final ToolCard CARD = ToolCard.builder()\n"
                + "        .id(\"order-query\")\n"
                + "        .name(\"OrderQueryTool\")\n"
                + "        .description(\"查询订单详情\")\n"
                + "        .inputParams(Map.of(\"orderId\", \"string\"))\n"
                + "        .build();\n\n"
                + "    public String apply(String orderId) {\n"
                + "        return orderId;\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(src.resolve("RefundTool.java"), (
                "package demo;\n\n"
                + "import java.util.Map;\n\n"
                + "/** FEAT-137 fixture: 退款工具（输入声明：orderId）。 */\n"
                + "public class RefundTool {\n\n"
                + "    public static final ToolCard CARD = ToolCard.builder()\n"
                + "        .id(\"refund\")\n"
                + "        .name(\"RefundTool\")\n"
                + "        .description(\"发起退款\")\n"
                + "        .inputParams(Map.of(\"orderId\", \"string\"))\n"
                + "        .build();\n\n"
                + "    public String apply(String orderId) {\n"
                + "        return \"refunded:\" + orderId;\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /**
     * DeepAgent 特征工程（L2 §3.2 次序 3）。识别依据含 "DeepAgent " 文本（实现仓
     * hasDeepAgent 规则），工具声明按 ToolCard.builder() 规范 DSL（审阅整改项）。
     */
    static Path createDeepProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-deep-");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.write(src.resolve("DemoDeepAgent.java"), (
                "package demo;\n\n"
                + "/** FEAT-137 fixture: DeepAgent 声明。 */\n"
                + "public class DemoDeepAgent {\n\n"
                + "    private static final String AGENT_ID = \"feat137-deep-demo\";\n\n"
                + "    public DeepAgent build() {\n"
                + "        DeepAgent agent = DeepAgent.builder()\n"
                + "            .name(\"feat137-deep-demo\")\n"
                + "            .description(\"feat137 DeepAgent demo\")\n"
                + "            .tool(new ResearchTool())\n"
                + "            .build();\n"
                + "        return agent;\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(src.resolve("ResearchTool.java"), (
                "package demo;\n\n"
                + "import java.util.Map;\n\n"
                + "/** FEAT-137 fixture: 研究工具（输入声明：query）。 */\n"
                + "public class ResearchTool {\n\n"
                + "    public static final ToolCard CARD = ToolCard.builder()\n"
                + "        .id(\"research\")\n"
                + "        .name(\"ResearchTool\")\n"
                + "        .description(\"执行研究检索\")\n"
                + "        .inputParams(Map.of(\"query\", \"string\"))\n"
                + "        .build();\n\n"
                + "    public String apply(String query) {\n"
                + "        return \"result:\" + query;\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /**
     * Workflow 规范工程（规范写法 v1）：`new WorkflowAgent(config)` + `addWorkflows(List.of(<流程类>.create()))`，
     * 流程含普通连接、流式连接、分支（条件文本 + 顺序 + 默认分支）与多来源分组连接（List 形式 addConnection）。
     */
    static Path createWorkflowProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-workflow-");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.write(src.resolve("DemoWorkflowAgent.java"), (
                "package demo;\n\n"
                + "import com.openjiuwen.core.workflow.Workflow;\n"
                + "import com.openjiuwen.core.workflow.WorkflowCard;\n"
                + "import com.openjiuwen.core.workflow.component.BranchComponent;\n"
                + "import com.openjiuwen.core.workflow.component.End;\n"
                + "import com.openjiuwen.core.workflow.component.Start;\n"
                + "import com.openjiuwen.core.workflow.component.tool.ToolComponent;\n"
                + "import com.openjiuwen.core.workflow.component.tool.ToolComponentConfig;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n\n"
                + "public class DemoWorkflowAgent {\n\n"
                + "    private static final String AGENT_ID = \"feat137-workflow-demo\";\n\n"
                + "    public WorkflowAgent build() {\n"
                + "        WorkflowAgentConfig config = WorkflowAgentConfig.builder()\n"
                + "            .id(AGENT_ID)\n"
                + "            .build();\n"
                + "        WorkflowAgent agent = new WorkflowAgent(config);\n"
                + "        agent.addWorkflows(List.of(DemoWorkflowAgent.create()));\n"
                + "        return agent;\n"
                + "    }\n\n"
                + "    public static Workflow create() {\n"
                + "        WorkflowCard card = WorkflowCard.builder()\n"
                + "            .id(\"feat137_order_flow\")\n"
                + "            .name(\"订单处理流程\")\n"
                + "            .version(\"1.0\")\n"
                + "            .build();\n"
                + "        Workflow workflow = new Workflow(card);\n"
                + "        workflow.setStartComp(\"start\", new Start(), Map.of(\"query\", \"${query}\"), null);\n"
                + "        workflow.addWorkflowComp(\"classify\", new LLMComponent(new LLMCompConfig()), Map.of(\"query\", \"${start.query}\"), null);\n"
                + "        workflow.addWorkflowComp(\"query\", new ToolComponent(new ToolComponentConfig()), Map.of(\"q\", \"${classify.out}\"), null);\n"
                + "        BranchComponent branch = new BranchComponent();\n"
                + "        branch.addBranch(\"amount > 1000\", \"reply\", \"large\");\n"
                + "        branch.addBranch(\"true\", \"reply\", \"default\");\n"
                + "        workflow.addWorkflowComp(\"branch\", branch, Map.of(), null);\n"
                + "        workflow.setEndComp(\"reply\", new End(), Map.of(\"total\", \"${query.data.total}\"), null);\n"
                + "        workflow.addConnection(\"start\", \"classify\");\n"
                + "        workflow.addStreamConnection(\"classify\", \"query\");\n"
                + "        workflow.addConnection(\"query\", \"branch\");\n"
                + "        workflow.addConnection(List.of(\"start\", \"query\"), \"merge\");\n"
                + "        workflow.addWorkflowComp(\"merge\", new ToolComponent(new ToolComponentConfig()), Map.of(), null);\n"
                + "        return workflow;\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /**
     * 非规范 Workflow 工程（F137-08）：规范流程体内含一条引用流程变量但不符合任何
     * 规范模式的语句 → WF_NONCANONICAL_FORM 未解析项 + 部分解析。
     */
    static Path createWorkflowPartialProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-wf-partial-");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.write(src.resolve("DynamicWorkflowAgent.java"), (
                "package demo;\n\n"
                + "import com.openjiuwen.core.workflow.Workflow;\n"
                + "import com.openjiuwen.core.workflow.WorkflowCard;\n"
                + "import com.openjiuwen.core.workflow.component.End;\n"
                + "import com.openjiuwen.core.workflow.component.Start;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n\n"
                + "public class DynamicWorkflowAgent {\n\n"
                + "    private static final String AGENT_ID = \"feat137-wf-partial\";\n\n"
                + "    public WorkflowAgent build() {\n"
                + "        WorkflowAgentConfig config = WorkflowAgentConfig.builder().id(AGENT_ID).build();\n"
                + "        WorkflowAgent agent = new WorkflowAgent(config);\n"
                + "        agent.addWorkflows(List.of(DynamicWorkflowAgent.create()));\n"
                + "        return agent;\n"
                + "    }\n\n"
                + "    public static Workflow create() {\n"
                + "        WorkflowCard card = WorkflowCard.builder().id(\"feat137_partial_flow\").name(\"部分解析流程\").build();\n"
                + "        Workflow workflow = new Workflow(card);\n"
                + "        workflow.setStartComp(\"start\", new Start(), Map.of(), null);\n"
                + "        workflow.setEndComp(\"end\", new End(), Map.of(), null);\n"
                + "        workflow.addConnection(\"start\", \"end\");\n"
                + "        // 非规范写法：引用流程变量的方法不在规范模式内，静态分析无法确定\n"
                + "        workflow.applyDynamicNodes(loadConfig());\n"
                + "        return workflow;\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /** 混合证据工程（F137-09）：Workflow 证据 + ReActAgent 特征并存。 */
    static Path createMixedProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-mixed-");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.write(src.resolve("MixedAgent.java"), (
                "package demo;\n\n"
                + "import com.openjiuwen.core.workflow.Workflow;\n"
                + "import com.openjiuwen.core.workflow.WorkflowCard;\n"
                + "import com.openjiuwen.core.workflow.component.End;\n"
                + "import com.openjiuwen.core.workflow.component.Start;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n\n"
                + "public class MixedAgent {\n\n"
                + "    private static final String AGENT_ID = \"feat137-mixed\";\n\n"
                + "    public WorkflowAgent buildWorkflow() {\n"
                + "        WorkflowAgentConfig config = WorkflowAgentConfig.builder().id(AGENT_ID).build();\n"
                + "        WorkflowAgent agent = new WorkflowAgent(config);\n"
                + "        agent.addWorkflows(List.of(MixedAgent.create()));\n"
                + "        return agent;\n"
                + "    }\n\n"
                + "    public static Workflow create() {\n"
                + "        WorkflowCard card = WorkflowCard.builder().id(\"feat137_mixed_flow\").name(\"混合证据流程\").build();\n"
                + "        Workflow workflow = new Workflow(card);\n"
                + "        workflow.setStartComp(\"start\", new Start(), Map.of(), null);\n"
                + "        workflow.setEndComp(\"end\", new End(), Map.of(), null);\n"
                + "        workflow.addConnection(\"start\", \"end\");\n"
                + "        return workflow;\n"
                + "    }\n\n"
                + "    public ReActAgent buildReact() {\n"
                + "        return ReActAgent.builder().name(\"feat137-mixed-react\").build();\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /**
     * Workflow 注册未解析工程（F137-24，审阅整改项）：addWorkflows 参数不是
     * `List.of(<流程类>.create(...))` 形式 → 注册来源无法静态确认
     * （实现仓 WorkflowRegistrationResolver：无法确定注册的流程 → WF_REGISTRATION_UNRESOLVED，
     * 流程 registration.status == unresolved）。
     */
    static Path createWorkflowUnresolvedProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-wf-unresolved-");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.write(src.resolve("RuntimeWorkflowAgent.java"), (
                "package demo;\n\n"
                + "import com.openjiuwen.core.workflow.Workflow;\n"
                + "import com.openjiuwen.core.workflow.WorkflowCard;\n"
                + "import com.openjiuwen.core.workflow.component.End;\n"
                + "import com.openjiuwen.core.workflow.component.Start;\n"
                + "import java.util.List;\n"
                + "import java.util.Map;\n\n"
                + "public class RuntimeWorkflowAgent {\n\n"
                + "    private static final String AGENT_ID = \"feat137-wf-unresolved\";\n\n"
                + "    public WorkflowAgent build() {\n"
                + "        // 注册来源运行期决定，静态分析无法确认注册\n"
                + "        WorkflowAgentConfig config = WorkflowAgentConfig.builder().id(AGENT_ID).build();\n"
                + "        WorkflowAgent agent = new WorkflowAgent(config);\n"
                + "        agent.addWorkflows(loadWorkflows());\n"
                + "        return agent;\n"
                + "    }\n\n"
                + "    private List<Workflow> loadWorkflows() {\n"
                + "        return List.of(RuntimeWorkflowAgent.create());\n"
                + "    }\n\n"
                + "    public static Workflow create() {\n"
                + "        WorkflowCard card = WorkflowCard.builder().id(\"feat137_unresolved_flow\").name(\"未解析注册流程\").build();\n"
                + "        Workflow workflow = new Workflow(card);\n"
                + "        workflow.setStartComp(\"start\", new Start(), Map.of(), null);\n"
                + "        workflow.setEndComp(\"end\", new End(), Map.of(), null);\n"
                + "        workflow.addConnection(\"start\", \"end\");\n"
                + "        return workflow;\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /**
     * Workflow 未注册工程（F137-25，审阅整改项）：仅流程定义、无任何 WorkflowAgent
     * 注册（registrationUnknown == false → L2 §3.3：明确没有注册证据时使用 unregistered）。
     */
    static Path createWorkflowUnregisteredProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-wf-unregistered-");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.write(src.resolve("OrphanFlowDefinition.java"), (
                "package demo;\n\n"
                + "import com.openjiuwen.core.workflow.Workflow;\n"
                + "import com.openjiuwen.core.workflow.WorkflowCard;\n"
                + "import com.openjiuwen.core.workflow.component.End;\n"
                + "import com.openjiuwen.core.workflow.component.Start;\n"
                + "import java.util.Map;\n\n"
                + "public class OrphanFlowDefinition {\n\n"
                + "    public static Workflow create() {\n"
                + "        // 仅定义流程，未由任何 WorkflowAgent 注册\n"
                + "        WorkflowCard card = WorkflowCard.builder().id(\"feat137_orphan_flow\").name(\"孤立流程\").build();\n"
                + "        Workflow workflow = new Workflow(card);\n"
                + "        workflow.setStartComp(\"start\", new Start(), Map.of(), null);\n"
                + "        workflow.setEndComp(\"end\", new End(), Map.of(), null);\n"
                + "        workflow.addConnection(\"start\", \"end\");\n"
                + "        return workflow;\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /**
     * Workflow 整体破损工程（F137-26，审阅整改项）：定义文件词法/语法级破损
     * → 文件诊断 + 疑似 Workflow 证据 → uncertain 展示，不伪装完整识别（特性档 §2.2、L2 §5.3）。
     */
    static Path createWorkflowBrokenProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-wf-broken-");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.write(src.resolve("BrokenWorkflowAgent.java"), (
                "package demo;\n\n"
                + "public class BrokenWorkflowAgent {\n\n"
                + "    public Workflow build() {\n"
                + "        Workflow workflow = new Workflow(card));\n"
                + "        workflow.setStartComp(\"unterminated-start-node, workflow body continues\n"
                + "        return workflow;\n"
                + "    }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /** 文件契约工程：分页、指纹、二进制、路径越界场景的文件集。Java 文件放 src/main/java（分析器输入清单范围，L2 §5.5）。 */
    static Path createFilesProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-files-");
        Path src = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.write(root.resolve("README.md"), "feat137 fixture readme\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("notes.txt"), "line-1\nline-2\nline-3\n".getBytes(StandardCharsets.UTF_8));
        Files.write(src.resolve("Main.java"), "public class Main {\n}\n".getBytes(StandardCharsets.UTF_8));
        // 非 UTF-8 二进制（F137-19）
        byte[] binary = new byte[64];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) (0xC0 + i % 32);
        }
        Files.write(root.resolve("data.bin"), binary);
        return root;
    }

    /** 超大文件工程：大于 P1_WORKBENCH_MAX_BYTES(524288) 的文本文件（F137-18）。 */
    static Path createBigFileProject() throws IOException {
        Path root = Files.createTempDirectory("feat137-big-");
        byte[] chunk = "feat137 big file filler line 0123456789\n".getBytes(StandardCharsets.UTF_8);
        byte[] big = new byte[524288 + 4096];
        for (int i = 0; i < big.length; i++) {
            big[i] = chunk[i % chunk.length];
        }
        Files.write(root.resolve("big.txt"), big);
        return root;
    }

    /** 凭据工程：明文口令行 YAML（F137-21）+ 私钥文件（F137-29，审阅整改项：私密文件 403 且无正文）。 */
    static Path createSecretProject(String runId) throws IOException {
        Path root = Files.createTempDirectory("feat137-secret-");
        String secretValue = "s3cret-" + runId;
        Files.write(root.resolve("secret.yaml"), (
                "db:\n"
                + "  user: feat137\n"
                + "  password: " + secretValue + "\n"
                + "  host: 127.0.0.1\n").getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("id_rsa"), (
                "-----BEGIN PRIVATE KEY-----\n"
                + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7feat137fixture\n"
                + "b3BxdWVzdGtleW1hdGVyaWFsZm9yZjEzNzByaXZhdGVrZXlyZWplY3Rpb250ZXN0\n"
                + "-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    /**
     * 创建工程内符号链接/联接指向工程外文件（F137-28，审阅整改项：访问越界 403）。
     *
     * @return 符号链接创建成功返回 true；环境不支持符号链接（Windows 非管理员等）返回 false，
     *         调用方据此 assumeTrue SKIPPED。
     */
    static boolean createSymlinkEscape(Path projectDir, Path outsideTarget, String linkName) {
        try {
            Path link = projectDir.resolve(linkName);
            if (Files.exists(link)) {
                return true;
            }
            Files.createSymbolicLink(link, outsideTarget.toAbsolutePath());
            return Files.exists(link);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            return false;
        }
    }

    /**
     * 将后端返回的工作区路径映射为宿主可写路径（VM/远端部署形态）。
     *
     * <p>后端运行于 Linux VM 时返回 "/mnt/d/xxx" 形式的工作区路径；该路径经 WSL drvfs 与
     * 宿主 "D:/xxx" 指向同一物理目录。映射规则经 `sut.workbench.host-path-map` 注入，
     * 多规则以 ";" 分隔，单规则形如 "from=to"（前缀替换）；未配置时原样返回
     * （本机形态路径即宿主路径，行为不变）。
     */
    static Path toHostPath(String backendPath, String mapSpec) {
        if (backendPath == null || backendPath.isBlank()) {
            return Path.of("");
        }
        if (mapSpec != null && !mapSpec.isBlank()) {
            for (String rule : mapSpec.split(";")) {
                int eq = rule.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String from = rule.substring(0, eq).trim();
                String to = rule.substring(eq + 1).trim();
                if (backendPath.startsWith(from)) {
                    return Path.of(to + backendPath.substring(from.length()));
                }
            }
        }
        return Path.of(backendPath);
    }

    /**
     * 宿主路径反向映射回 VM/Linux 路径（hostPathMap 的逆操作）。
     * 规则形如 "from=to"（如 "/mnt/d/=D:/"）：宿主 "D:/x" → VM "/mnt/d/x"。
     */
    static String toVmPath(Path hostPath, String mapSpec) {
        String p = hostPath.toString().replace('\\', '/');
        if (mapSpec != null && !mapSpec.isBlank()) {
            for (String rule : mapSpec.split(";")) {
                int eq = rule.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String from = rule.substring(0, eq).trim();
                String to = rule.substring(eq + 1).trim();
                if (!to.isBlank() && p.startsWith(to)) {
                    return from + p.substring(to.length());
                }
            }
        }
        return p;
    }

    /**
     * 经 VM/Linux 侧创建符号链接（宿主无符号链接权限时的环境辅助，config 门禁：
     * sut.workbench.wsl-distro 注入发行版名）。fixture 环境搭建手段，非被测行为。
     *
     * @return 创建成功返回 true
     */
    static boolean createSymlinkViaWsl(String distro, Path hostLink, Path hostTarget, String mapSpec) {
        if (distro == null || distro.isBlank()) {
            return false;
        }
        String vmLink = toVmPath(hostLink, mapSpec);
        String vmTarget = toVmPath(hostTarget, mapSpec);
        if (vmLink.startsWith("D:") || vmTarget.startsWith("D:")) {
            return false; // 无法反向映射时放弃
        }
        try {
            Process proc = new ProcessBuilder(
                    "wsl", "-d", distro, "--", "ln", "-sfn", vmTarget, vmLink)
                    .redirectErrorStream(true)
                    .start();
            boolean done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            return done && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 递归拷贝 fixture 目录内容到目标目录（合并，不删除目标已有文件）。
     *
     * <p>用途：工作台后端按命名登记工作区时在用户根目录下自动建目录（显式 path 登记
     * 会被约束在用户根内），fixture 内容需拷入该目录后再做投影/文件断言。
     */
    static void copyInto(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (var stream = Files.walk(src)) {
            for (Path p : stream.sorted().toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 删除 fixture 目录（尽力而为）。 */
    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 清理尽力而为
        }
    }
}
