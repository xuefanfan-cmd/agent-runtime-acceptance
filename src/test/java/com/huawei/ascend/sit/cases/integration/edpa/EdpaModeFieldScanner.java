package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FEAT-028 N1 共享扫描器：在可观察面上检出<b>协同模式字段</b>的命名形态。
 *
 * <p><b>判据性质（重要）</b>：这是一条<b>黑名单</b>。FEAT-019 §3 抬头明示该特性
 * 「不固定 Java 类名、包路径、内部 DTO 名称或具体序列化字段」，因此本扫描器
 * <b>永远无法证明「不存在协同模式字段」</b>，只能捕获已知命名形态的回归。
 * 它的绿灯含义是「未出现已知形态」，不是「§2.2 合规」。§2.2 的正面举证在 agent-core 白盒单测。
 *
 * <p>抽成独立 helper 的原因：让 {@link EdpaModeFieldScannerSelfTest} 能在<b>不起 SUT</b> 的前提下
 * 每轮构建都验证扫描逻辑本身可用（金丝雀）。真机看守 {@link EdpaCoordinationModeLeakGuardTest}
 * 打了 {@code manual} 标签、平时不跑；若自检也跟着不跑，看守就无从证明自己没坏。
 *
 * <p><b>2026-09-02 修复的缺陷</b>：旧实现把白名单套在整条累积 JSON path 上
 * （{@code pathLower.contains(whitelistEntry)}），导致任何祖先节点名含 {@code model}
 * 就赦免其下所有后代字段——看守可被静默缴械。现改为<b>只对命中的叶子字段名</b>做判定与白名单。
 */
final class EdpaModeFieldScanner {

    /**
     * 禁止的<b>叶子字段名</b>（小写精确匹配），与后缀规则取并集。
     *
     * <p>依据：FEAT-028 §2.2「agent-core 不在 batch interrupt envelope 中重复声明协同模式」；
     * 协同模式取值域为 FEAT-006 的 {@code BLOCKING} / {@code STREAMING} / {@code ASYNC}。
     * 本清单列举这些语义最可能的命名形态。
     */
    static final Set<String> FORBIDDEN_EXACT = Set.of(
            "mode", "blocking",
            "syncmode", "sync_mode", "asyncmode", "async_mode",
            "edpamode", "edpa_mode",
            "coordinationmode", "coordination_mode",
            "executionmode", "execution_mode",
            "invocationmode", "invocation_mode",
            "callmode", "call_mode");

    /**
     * 白名单：<b>叶子字段名</b>精确匹配（小写）。
     *
     * <p><b>初始为空是刻意的。</b>2026-09-02 前的版本放了几条自标「假想合规字段」的条目
     * （{@code syncedAt} / {@code syncTime} / {@code asyncoperation} / {@code responsemode} /
     * {@code protocolMode}）——无出处，违反 T-M21「断言须有可追溯依据」，已全部清除。
     *
     * <p><b>增长规则</b>：只有当某字段在真机上确实命中、且能指出它在 A2A 协议或特性档里的合法出处时，
     * 才允许加入，并在条目旁注明出处。红一次、定性一次、加一条；不允许预先推测。
     *
     * <p>判定规则已从「整条 path 子串匹配」改为「叶子名精确 + {@code *mode} 后缀」，
     * 因此 {@code model} / {@code modelName} / {@code modelProvider} 这类字段根本不会命中
     * （不以 mode 结尾、不在精确集内），旧白名单为它们准备的条目不再需要。
     */
    static final Set<String> WHITELIST_LEAF = Set.of();

    /** 日志文本里的 {@code key=value} 键名。 */
    private static final Pattern LOG_KEY = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)=");

    private EdpaModeFieldScanner() {}

    /**
     * 判定一个<b>叶子字段名</b>是否属协同模式字段。
     * 只看叶子名、不看整条 path；白名单同样只对叶子名生效，祖先节点名不会赦免后代字段。
     */
    static boolean isForbiddenLeaf(String leaf) {
        String l = leaf.toLowerCase(Locale.ROOT);
        if (WHITELIST_LEAF.contains(l)) {
            return false;
        }
        return FORBIDDEN_EXACT.contains(l) || l.endsWith("mode") || l.endsWith("_mode");
    }

    /**
     * 递归扫描 JSON 的所有字段名。
     *
     * @param origin 命中条目的来源标记（如 {@code sse[12]} / {@code snapshot}），便于定位
     * @return 命中列表，形如 {@code "sse[12] :: result.a.syncMode  (leaf='syncMode')"}
     */
    static List<String> scanJson(JsonNode root, String origin) {
        List<String> hits = new ArrayList<>();
        walk(root, "", origin, hits);
        return hits;
    }

    private static void walk(JsonNode node, String prefix, String origin, List<String> hits) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String leaf = names.next();
                String path = prefix.isEmpty() ? leaf : prefix + "." + leaf;
                if (isForbiddenLeaf(leaf)) {
                    hits.add(origin + " :: " + path + "  (leaf='" + leaf + "')");
                }
                walk(node.get(leaf), path, origin, hits);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), prefix + "[" + i + "]", origin, hits);
            }
        }
    }

    /**
     * 扫描日志文本里的 {@code key=value} 键名（同一键名只报一次）。
     *
     * <p>进程日志属灰盒面，且日志格式非契约——这里只做泄漏看守，不做结构断言。
     */
    static List<String> scanLogKeys(String log, String origin) {
        List<String> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = LOG_KEY.matcher(log == null ? "" : log);
        while (m.find()) {
            String key = m.group(1);
            if (isForbiddenLeaf(key) && seen.add(key.toLowerCase(Locale.ROOT))) {
                hits.add(origin + " :: " + key);
            }
        }
        return hits;
    }
}
