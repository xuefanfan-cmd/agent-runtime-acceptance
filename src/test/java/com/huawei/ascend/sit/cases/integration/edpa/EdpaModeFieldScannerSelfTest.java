package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-028 矩阵 <b>N1 金丝雀</b> —— {@link EdpaModeFieldScanner} 的看守自检。
 *
 * <p><b>为什么需要这条用例</b>：N1 的真机看守 {@link EdpaCoordinationModeLeakGuardTest} 在正常实现下
 * 恒绿。恒绿的用例有一个固有风险——你无法区分「确实没泄漏」和「扫描器坏了 / 匹配逻辑写错了 /
 * 根本没扫到东西」。本用例每轮构建都用<b>合成节点</b>验证扫描器确实能开火，
 * 把看守的绿灯从「扫不到」提升为「有能力扫到，且确实没扫到」。
 *
 * <p><b>不打 {@code manual} 标签</b>是刻意的：本用例不起 SUT、不需要 LLM、毫秒级完成，
 * 必须随常规构建一起跑。若它跟着真机看守一起被 {@code manual} 挡掉，看守就无从证明自己没坏。
 *
 * <p><b>同时回归一个真实缺陷</b>：2026-09-02 前的实现把白名单套在整条累积 JSON path 上，
 * 任何祖先节点名含 {@code model} 就会赦免其下所有后代字段，看守可被静默缴械。
 * {@link #forbiddenFieldNestedUnderModelAncestorIsStillCaught()} 就是为回归这个缺陷设的。
 * <p><b>标签说明</b>：本类<b>刻意不打</b> {@code @Tag("integration")}——它不起任何 SUT、
 * 不发网络请求，是纯逻辑自检，不属集成测试。同目录其余 edpa 用例均打 {@code integration}，
 * 此处是有意的例外，不是遗漏。保留 {@code edpa} / {@code feat-028} 两个标签是为了让它
 * 随 {@code -Dgroups='feat-028 & !manual'} 一起跑到（该集合当前 = A1 + 本金丝雀）。
 */
@Tag("edpa") @Tag("feat-028")
@Feature("FEAT-028: EDPA 规划工作流与智能体并行执行")
@Story("N1-canary.scanner-self-test: 协同模式字段扫描器自检（看守可开火性）")
class EdpaModeFieldScannerSelfTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("FEAT-028.N1-canary-1: 埋在 model 祖先节点下的 syncMode 仍必须被检出")
    void forbiddenFieldNestedUnderModelAncestorIsStillCaught() throws Exception {
        JsonNode canary = MAPPER.readTree("""
                {
                  "result": {
                    "model": { "modelName": "deepseek-v4", "syncMode": "BLOCKING" },
                    "items": [ { "toolCallId": "call_x", "edpa_mode": "ASYNC" } ]
                  }
                }
                """);

        List<String> hits = EdpaModeFieldScanner.scanJson(canary, "canary");

        assertThat(hits)
                .as("[n1-canary] 看守失效：合成违规字段未被检出，扫描/匹配逻辑已损坏，"
                        + "真机看守本轮的绿灯不可信")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(String.join("\n", hits))
                .as("[n1-canary] 回归 2026-09-02 修复的整条-path 白名单缺陷："
                        + "祖先节点名为 model 不得赦免其下的 syncMode")
                .contains("result.model.syncMode");
        assertThat(String.join("\n", hits))
                .as("[n1-canary] 数组元素内的 edpa_mode 必须被检出")
                .contains("edpa_mode");
    }

    @Test
    @DisplayName("FEAT-028.N1-canary-2: 合法字段不得误红（model/modelName/executionId 等）")
    void legitimateFieldsAreNotFlagged() throws Exception {
        // 这些是旧实现用 substring 匹配时会误红的字段：modelName 含 "mode"，
        // executionId 含 "execution"，asyncTimeout 含 "async"，syncedAt 含 "sync"。
        JsonNode benign = MAPPER.readTree("""
                {
                  "model": "deepseek-v4", "modelName": "deepseek-v4", "modelProvider": "aliyun",
                  "executionId": "exec-1", "asyncTimeout": 30, "syncedAt": "2026-09-02",
                  "coordinationId": "c-1", "createdAt": "2026-09-02", "taskId": "t-1"
                }
                """);

        assertThat(EdpaModeFieldScanner.scanJson(benign, "benign"))
                .as("[n1-canary] 合法字段被误判为协同模式字段——禁止词规则过宽，会造成误红")
                .isEmpty();
    }

    @Test
    @DisplayName("FEAT-028.N1-canary-3: 日志键名扫描同样可开火，且同名只报一次")
    void logKeyScannerCanFireAndDeduplicates() {
        String leaking = "Remote invocation state parentTaskId=t-1 batchId=b-1 "
                + "toolCallId=call_1 remoteAgentId=search-agent syncMode=BLOCKING latencyMs=12\n"
                + "Remote invocation state parentTaskId=t-1 batchId=b-2 syncMode=BLOCKING\n";
        assertThat(EdpaModeFieldScanner.scanLogKeys(leaking, "edp-agent.log"))
                .as("[n1-canary] 日志面泄漏未被检出，或未按键名去重")
                .containsExactly("edp-agent.log :: syncMode");

        String clean = "Remote invocation state parentTaskId=t-1 conversationId=ctx-1 batchId=b-1 "
                + "toolCallId=call_1 remoteAgentId=search-agent state=COMPLETED latencyMs=13179\n";
        assertThat(EdpaModeFieldScanner.scanLogKeys(clean, "edp-agent.log"))
                .as("[n1-canary] coordinator 正常状态行的 7 个字段均不得命中"
                        + "（parentTaskId/conversationId/batchId/toolCallId/remoteAgentId/state/latencyMs）")
                .isEmpty();
    }

    @Test
    @DisplayName("FEAT-028.N1-canary-4: 白名单保持为空（新增须附出处），叶子名判定大小写不敏感")
    void whitelistStaysEmptyAndLeafJudgementIsCaseInsensitive() {
        // 说明：本条只守「白名单条目纪律 + 叶子名判定的大小写规则」。
        // 「白名单/禁止词不得被祖先路径影响」这条语义由 canary-1 负责回归
        // （model 祖先下的 syncMode 仍必须命中），不要在这里重复宣称。
        assertThat(EdpaModeFieldScanner.WHITELIST_LEAF)
                .as("[n1-canary] 白名单新增条目必须在 EdpaModeFieldScanner 里注明合法出处（T-M21）；"
                        + "若本断言因新增条目而红，请确认该条目已附出处后再更新此处期望")
                .isEmpty();
        assertThat(EdpaModeFieldScanner.isForbiddenLeaf("syncMode")).isTrue();
        assertThat(EdpaModeFieldScanner.isForbiddenLeaf("SYNC_MODE")).isTrue();
        assertThat(EdpaModeFieldScanner.isForbiddenLeaf("modelName")).isFalse();
    }
}
