package com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail;

import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import com.huawei.ascend.sit.cases.e2e.deepanalyze_guardrail.DaCompatGuardrailSupport.SseFrame;
import io.qameta.allure.Allure;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-109 workdir 执行期 containment（F109-21，r3 判据——!1179 S2 分类二分 + #755 三点加固后形态）。
 *
 * <p><b>判据（被测制品 0.1.3-feat109111-r3 = agent-solution common@babb882b9，源码读出）：</b></p>
 * <ul>
 *   <li>workdir 校验双期：受理期词法+真实路径（DaHookDefinition.validatedWorkdir，逃逸抛 IAE）；
 *       执行期残余窗口（受理后目录被换链/删除）由 DaHookExecutor.runCommand 二分——
 *       <b>IAE（真实路径逃逸）→ BLOCKED_TARGET（安全拦截，进护栏证据轴）</b>；
 *       <b>IOException（目录删除/坏链）→ START_FAILED（环境面，不进护栏证据轴）</b>
 *       （!1179 S2 裁决 d5884e943；受理后换链逃逸探针 acceptedThenSwappedToEscapeSymlink_
 *       executionBlockedTarget / 删目录探针 acceptedThenDirectoryDeleted_executionStartFailed）。</li>
 *   <li>blocking PreToolUse hook 执行失败（含上述两类）→ fail-closed deny：工具不执行、
 *       hook_result status=failed（DaHookRunner.firePreToolUse——executionFailure 即拒）。</li>
 *   <li>可观测区分面 = hook_result 的 summary 文本：{@code execution blocked_target: …} vs
 *       {@code execution start_failed: …}（DaHookRunner.summaryOf：failure().name().toLowerCase()）。
 *       status 字段两类同为 failed——**分类判据必须落在 summary，不得只断 status**。</li>
 *   <li>#755 点6：段名内 {@code ..}（如 reports..2026）合法放行——本类负向构造全部使用
 *       真实路径逃逸/删除，不使用 {@code ..} 子串形态（F109-16 越界面同纪律）。</li>
 * </ul>
 *
 * <p><b>构造（完全确定性，无竞态）</b>：双 read_file 任务（read_file=allow 不挂起）；
 * PostToolUse hook M（合法 workdir W2）的脚本在第一次读完成后变更 PreToolUse hook H 的
 * workdir W——换链逃逸（A）或删除（B）；第二次 read 的 PreToolUse H 即在变更后的 W 上执行。
 * 变更时机由 hook 链自身定序，不依赖模型时序窗口。</p>
 */
@Tag("e2e")
@Tag("deepanalyze")
@Tag("feat-109")
@Feature("FEAT-109: 危险操作拦截与 hooks")
class Feat109WorkdirContainmentE2EIT {

    private static TestConfig config;
    private static SutStack stack;
    private static HttpClient http;
    private static String base;
    private static Path workspaceRoot;
    private static Path hookScriptDir;
    /** command-root：hooks workdir 校验根（S2 词法+真实路径双重 containment 的边界）。 */
    private static Path commandRoot;
    /** M（变更者）的 workdir——独立于受害者 W，避免脚本删除自身 CWD 的脆弱形态。 */
    private static Path workdirM;

    @BeforeAll
    static void setUp() throws Exception {
        config = TestConfig.load();
        workspaceRoot = Files.createTempDirectory("feat109-wdc-ws");
        hookScriptDir = Files.createTempDirectory("feat109-wdc-hooks");
        commandRoot = Files.createTempDirectory("feat109-wdc-root");
        workdirM = Files.createDirectories(commandRoot.resolve("wd-m"));
        stack = DaCompatGuardrailSupport.guardrailStack(config, agent -> agent
                        .property("deepanalyze.workspace.root", workspaceRoot.toAbsolutePath().toString())
                        .property("deepanalyze.compat.hooks.command-root",
                                commandRoot.toAbsolutePath().toString()))
                .start();
        http = DaCompatGuardrailSupport.newHttpClient();
        base = stack.client(DaCompatGuardrailSupport.AGENT).getBaseUrl();
    }

    @AfterAll
    static void tearDown() {
        if (stack != null) {
            stack.close();
        }
    }

    /* ==================== 构造 ==================== */

    /** PreToolUse hook H：canary 写入 workdir（执行证据），matcher=read_file。workdir 每测试独立。 */
    private static String preToolUseCanaryHook(String id, Path workdir) throws Exception {
        Path script = hookScriptDir.resolve(id + ".sh");
        Files.writeString(script, "#!/bin/sh\nprintf 'hook-ran-' >> canary.txt\nexit 0\n");
        return "{\"id\":\"" + id + "\",\"event\":\"PreToolUse\",\"type\":\"command\","
                + "\"matcher\":\"read_file\",\"enabled\":true,\"config\":{\"command\":\"sh "
                + script.toAbsolutePath() + "\",\"workdir\":\"" + workdir.toAbsolutePath() + "\"}}";
    }

    /** PostToolUse hook M：第一次读完成后变更 H 的 workdir（mutateShell 对 W 的绝对路径操作）。 */
    private static String postToolUseMutatorHook(String id, String mutateShell) throws Exception {
        Path script = hookScriptDir.resolve(id + ".sh");
        Files.writeString(script, "#!/bin/sh\n" + mutateShell + "\nexit 0\n");
        return "{\"id\":\"" + id + "\",\"event\":\"PostToolUse\",\"type\":\"command\","
                + "\"matcher\":\"read_file\",\"enabled\":true,\"config\":{\"command\":\"sh "
                + script.toAbsolutePath() + "\",\"workdir\":\"" + workdirM.toAbsolutePath() + "\"}}";
    }

    /** 双 read_file 任务（顺序指令：先 A 后 B，B 依赖 A 内容呈现——防并行工具块）。 */
    private static List<SseFrame> runTwoReadsTask(String hooksJson, String markerA, String markerB)
            throws Exception {
        String session = "sess-f109wdc-" + System.nanoTime();
        String fileA = "wdc-a-" + markerA + ".txt";
        String fileB = "wdc-b-" + markerB + ".txt";
        Files.createDirectories(workspaceRoot.resolve(session));
        Files.writeString(workspaceRoot.resolve(session).resolve(fileA), "content-A-" + markerA);
        Files.writeString(workspaceRoot.resolve(session).resolve(fileB), "content-B-" + markerB);
        String input = "请严格按顺序做两件事：第一步，使用 read_file 工具读取文件 " + fileA
                + " 并原样告诉我它的内容；第二步，在完成第一步之后，再使用 read_file 工具读取文件 "
                + fileB + " 并告诉我内容。必须先完成第一步才能开始第二步。不要执行其他任何工具。";
        String body = "{\"sessionId\":\"" + session + "\",\"input\":\"" + input + "\","
                + "\"runtimeConfig\":{\"version\":1,\"tenantId\":\"default-user\",\"sessionId\":\""
                + session + "\",\"hooks\":[" + hooksJson + "]}}";
        HttpResponse<InputStream> stream = DaCompatGuardrailSupport.openRunStream(http, base, body,
                Duration.ofSeconds(180));
        List<SseFrame> frames = DaCompatGuardrailSupport.readFrames(stream, f -> false,
                Duration.ofSeconds(180));
        Path dir = Path.of("target", "f109-dump");
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("session=").append(session).append("\n---frames---\n");
        for (int i = 0; i < frames.size(); i++) {
            sb.append("[").append(i).append("] event=").append(frames.get(i).event())
                    .append(" data=").append(frames.get(i).data()).append('\n');
        }
        Files.writeString(dir.resolve(session + ".txt"), sb.toString());
        return frames;
    }

    private static void assertModelCalledReadFile(List<SseFrame> frames) {
        boolean readSeen = frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("read_file"));
        org.junit.jupiter.api.Assumptions.assumeTrue(readSeen,
                "INCONCLUSIVE——模型未调 read_file（纯文本回复未触达 hooks 面）；属模型旁路非产品缺陷");
    }

    private static boolean contentSeen(List<SseFrame> frames, String marker) {
        return frames.stream().anyMatch(f -> f.data() != null && f.data().contains(marker));
    }

    /** H（PreToolUse）hook_result 里 status=failed 且 summary 含 expect 片段的帧是否存在。 */
    private static boolean hookResultFailedWith(List<SseFrame> frames, String hookId, String expect) {
        return frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("\"hookId\":\"" + hookId + "\"")
                && "failed".equals(f.field("status"))
                && f.data().contains(expect));
    }

    private static boolean hookResultCompleted(List<SseFrame> frames, String hookId) {
        return frames.stream().anyMatch(f -> f.data() != null
                && f.data().contains("\"hookId\":\"" + hookId + "\"")
                && "completed".equals(f.field("status")));
    }

    /* ==================== 用例 ==================== */

    @Test
    @Story("F109-21: 执行期换链逃逸 → BLOCKED_TARGET（安全拦截，进护栏证据轴），第二次工具被 deny")
    @DisplayName("F109-21a: 受理后换链逃逸 → 执行期 BLOCKED_TARGET deny")
    void swappedSymlinkEscapeBlockedAtExecution() throws Exception {
        // 每测试独立 workdir（run-02 教训：共享 W 被测试 B 删除后，测试 A 受理期 400）
        Path workdir = Files.createDirectories(commandRoot.resolve("wd-a-" + System.nanoTime()));
        // M 的变更动作：删真目录 → 建指向 /etc 的同名符号链接（真实路径逃逸——#755 点4 后
        // 校验返回真实路径，词法面无法区分，执行期 toRealPath 比对命中逃逸）
        String mutator = postToolUseMutatorHook("wdc-m",
                "rm -rf '" + workdir.toAbsolutePath() + "'"
                        + " && ln -s /etc '" + workdir.toAbsolutePath() + "'");
        List<SseFrame> frames = runTwoReadsTask(
                preToolUseCanaryHook("wdc-h", workdir) + "," + mutator, "esc" + System.nanoTime(),
                "escb" + System.nanoTime());
        assertModelCalledReadFile(frames);

        // 第一次 read 执行（变更前，H 在合法 workdir 上 completed）
        assertThat(hookResultCompleted(frames, "wdc-h"))
                .as("变更前 H 首次执行 completed（受理期合法）").isTrue();
        assertThat(contentSeen(frames, "content-A-")).as("第一次 read_file 执行").isTrue();

        // 变更后 H 第二次执行：status=failed 且 summary 含 execution blocked_target
        // （S2 分类：IAE=安全拦截；**不得**只断 status——START_FAILED 同为 failed）
        assertThat(hookResultFailedWith(frames, "wdc-h", "execution blocked_target"))
                .as("执行期换链逃逸 → BLOCKED_TARGET（summary 分类面）").isTrue();

        // containment：第二次 read 的内容不出现（工具被 deny，未执行）
        assertThat(contentSeen(frames, "content-B-"))
                .as("逃逸后第二次 read_file 被 deny，工具不执行").isFalse();
        Allure.addAttachment("F109-21a 证据", "text/plain",
                "受理后换链（W→/etc 符号链接）→ H 第二次执行 hook_result status=failed"
                        + " + summary execution blocked_target；第二次 read 内容未出现（deny 生效）");
    }

    @Test
    @Story("F109-21: 执行期目录删除 → START_FAILED（环境面，不进护栏证据轴），同样 fail-closed deny")
    @DisplayName("F109-21b: 受理后删目录 → 执行期 START_FAILED deny（环境面分类）")
    void deletedDirectoryStartFailedAtExecution() throws Exception {
        Path workdir = Files.createDirectories(commandRoot.resolve("wd-b-" + System.nanoTime()));
        // M 的变更动作：只删目录（环境失效——S2 裁决归 START_FAILED，退出护栏证据轴）
        String mutator = postToolUseMutatorHook("wdc-m2",
                "rm -rf '" + workdir.toAbsolutePath() + "'");
        List<SseFrame> frames = runTwoReadsTask(
                preToolUseCanaryHook("wdc-h2", workdir) + "," + mutator, "del" + System.nanoTime(),
                "delb" + System.nanoTime());
        assertModelCalledReadFile(frames);

        assertThat(hookResultCompleted(frames, "wdc-h2"))
                .as("变更前 H 首次执行 completed").isTrue();
        assertThat(contentSeen(frames, "content-A-")).as("第一次 read_file 执行").isTrue();

        // 环境面分类：summary 含 execution start_failed（与 blocked_target 互斥——
        // 两分类在 hook_result 上可区分，护栏证据轴差异由此承载）
        assertThat(hookResultFailedWith(frames, "wdc-h2", "execution start_failed"))
                .as("执行期目录删除 → START_FAILED（环境面 summary 分类）").isTrue();
        assertThat(hookResultFailedWith(frames, "wdc-h2", "execution blocked_target"))
                .as("分类互斥：环境面不得标 BLOCKED_TARGET").isFalse();

        // fail-closed：工具同样不执行（执行失败族 fail-closed 姿态）
        assertThat(contentSeen(frames, "content-B-"))
                .as("环境失效后第二次 read_file 同样被 deny（fail-closed）").isFalse();
        Allure.addAttachment("F109-21b 证据", "text/plain",
                "受理后删目录 → H 第二次执行 status=failed + summary execution start_failed"
                        + "（不与 blocked_target 混淆）；fail-closed deny 工具不执行");
    }
}
