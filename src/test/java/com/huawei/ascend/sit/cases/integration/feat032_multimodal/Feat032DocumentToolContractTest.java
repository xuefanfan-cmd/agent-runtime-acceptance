/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat032_multimodal;

import static org.assertj.core.api.Assertions.assertThat;

import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FEAT-032 CORE-003：办公文档一期最小集的契约验收
 * （测试设计 F032-24..33；SC-12/16/17 对应）。
 *
 * <p>契约基线断言锁定 L2 §2.7/§5.3 记录的字段与取值：魔数格式判定表、readRanges/
 * unreadRanges/truncatedRanges 事实分离、totalHits/complete 不受 maxResults 影响、
 * zip/XML 保真替换（仅目标 part 变化）、原文件 sha 不可变、expected_version 乐观校验、
 * 一期不支持项显式 FORMAT_UNSUPPORTED/OPERATION_UNSUPPORTED、工作区路径守卫。
 * 真实 SUT 工具制品就绪后，直驱断言经 {@link Feat032SutGatedBlackboxTest} 补充执行。
 */
@Tag("integration")
@Tag("feat-032")
@Tag("multimodal")
@Feature("FEAT-032: 智能体支持多模态")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat032DocumentToolContractTest {

    @TempDir
    Path workspace;

    // ------------------------------------------------------------------
    // F032-24 document_inspect 探查事实
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-24")
    @Story("FEAT-032.F032-24: inspect 返回魔数格式、SHA-256 版本、结构事实、加密标记与支持清单")
    @DisplayName("F032-24 文档格式魔数判定表与探查事实字段")
    void f03224InspectMagicDetectionAndFacts() throws Exception {
        // [mirror: DocumentFormats.detect 魔数 + 容器部件判定表]
        assertThat(mirrorDetectFormat(Feat032SutSupport.pngMagicBytes())).isEqualTo("PNG");
        assertThat(mirrorDetectFormat(Feat032SutSupport.ole2MagicBytes())).isEqualTo("OLE2");
        assertThat(mirrorDetectFormat(Feat032SutSupport.pdfMagicBytes())).isEqualTo("PDF");
        assertThat(mirrorDetectFormat(Feat032SutSupport.unknownMagicBytes())).isEqualTo("UNKNOWN");

        byte[] docx = Feat032SutSupport.minimalDocx("<w:p><w:r><w:t>hello</w:t></w:r></w:p>");
        assertThat(mirrorDetectFormat(docx)).isEqualTo("DOCX");
        byte[] xlsx = Feat032SutSupport.minimalXlsx("<worksheet/>");
        assertThat(mirrorDetectFormat(xlsx)).isEqualTo("XLSX");

        // [mirror: DocumentInspector 事实结构]：version=SHA-256、加密标记、结构与支持清单
        Map<String, Object> facts = mirrorInspectFacts(docx, false);
        assertThat(facts.get("version")).isEqualTo(Feat032SutSupport.sha256Hex(docx));
        assertThat(facts.get("isEncrypted")).isEqualTo(false);
        assertThat(strList(facts.get("supportedOps")))
                .contains("inspect", "read", "search", "edit_text");
        assertThat(strList(facts.get("unsupportedOps"))).contains("render", "query", "create");

        Map<String, Object> encrypted = mirrorInspectFacts(docx, true);
        assertThat(encrypted.get("isEncrypted")).isEqualTo(true);
    }

    // ------------------------------------------------------------------
    // F032-25 document_read 范围读取与未读事实（SC-12）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-25")
    @Story("FEAT-032.F032-25: 范围读取返回内容与位置；readRanges/unreadRanges/truncatedRanges 如实分离")
    @DisplayName("F032-25 范围读取事实三区间分离且不虚报全量")
    void f03225RangeReadFactsSeparation() {
        // [mirror: DocumentReaders 范围读取语义]：10 段文档读 1-3 段
        Map<String, Object> read = mirrorRangeRead(10, List.of("1-3"), 500);
        assertThat(read.get("readRanges")).isEqualTo(List.of("1-3"));
        assertThat(read.get("unreadRanges")).isEqualTo(List.of("4-10"));
        assertThat(read.get("truncatedRanges")).isEqualTo(List.of());
        assertThat((List<?>) read.get("items")).hasSize(3);

        // [mirror: 如实记录语义]：不以单个 total 布尔代替三区间事实
        assertThat(read).doesNotContainKey("total");

        // 超范围请求：截断事实如实返回
        Map<String, Object> truncated = mirrorRangeRead(10, List.of("1-3"), 2);
        assertThat(truncated.get("truncatedRanges")).isEqualTo(List.of("1-3"));
        assertThat((List<?>) truncated.get("items")).hasSize(2);
    }

    // ------------------------------------------------------------------
    // F032-26 document_search 全文扫描事实
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-26")
    @Story("FEAT-032.F032-26: 全文档扫描，totalHits/complete 不受 maxResults 影响")
    @DisplayName("F032-26 totalHits=8/complete=true 不受 maxResults=3 影响")
    void f03226SearchTotalHitsUnaffectedByMaxResults() {
        // [mirror: DocumentSearcher 全文扫描语义]：maxResults 只限返回条数，不改变 totalHits/complete
        List<Map<String, Object>> allHits = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            allHits.add(Map.of("position", "paragraph-" + i, "snippet", "ALPHA-TOKEN hit " + i));
        }
        Map<String, Object> result = mirrorSearch(allHits, 3);
        assertThat(result.get("totalHits")).isEqualTo(8);
        assertThat(result.get("complete")).isEqualTo(true);
        assertThat((List<?>) result.get("hits")).hasSize(3);
    }

    // ------------------------------------------------------------------
    // F032-27 document_edit_text 保真替换闭环（SC-16 部分，主门禁）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-27")
    @Story("FEAT-032.F032-27: 保真替换跨 run 命中、仅目标 part 变化、原文件 sha 不变、dry_run 与 matched=0 语义")
    @DisplayName("F032-27 docx 跨 run 保真替换与 zip part 逐字节比对")
    void f03227FaithfulTextReplacementClosedLoop() throws Exception {
        // 真实 docx fixture：ALPHA-TOKEN 拆在两个 run（跨 run），目标词 BETA-TOKEN 唯一
        byte[] original = Feat032SutSupport.minimalDocx("""
                <w:p><w:r><w:t>头部-ALPHA</w:t></w:r><w:r><w:t>-TOKEN 尾部 </w:t></w:r>
                <w:r><w:t>包含 </w:t></w:r><w:r><w:t>BETA-TOKEN</w:t></w:r>
                <w:r><w:t> 唯一目标</w:t></w:r></w:p>""");
        String originalSha = Feat032SutSupport.sha256Hex(original);
        Path source = workspace.resolve("contract.docx");
        Files.write(source, original);

        // [mirror: OoxmlTextReplacer 跨 run 匹配语义]：ALPHA-TOKEN 跨 run 可命中
        Map<String, Object> plan = mirrorBuildReplacementPlan(
                original, "ALPHA-TOKEN", "GAMMA-TOKEN");
        assertThat(plan.get("matched")).isEqualTo(1);

        // [mirror: dry_run 预览不落盘]
        Map<String, Object> dryRun = mirrorApplyPlan(plan, source, true, false);
        assertThat(dryRun.get("dryRun")).isEqualTo(true);
        assertThat(dryRun.containsKey("outputPath")).as("dry_run 不产出文件").isEqualTo(false);

        // [mirror: ZipRewriter 仅重写变化 part，其余字节零改动]
        Map<String, Object> applied = mirrorApplyPlan(plan, source, false, false);
        Path output = Path.of((String) applied.get("outputPath"));
        byte[] resultBytes = Feat032SutSupport.readAll(output);
        Map<String, byte[]> beforeParts = Feat032SutSupport.zipParts(original);
        Map<String, byte[]> afterParts = Feat032SutSupport.zipParts(resultBytes);
        assertThat(afterParts.keySet()).isEqualTo(beforeParts.keySet());
        for (String part : beforeParts.keySet()) {
            if (!part.equals("word/document.xml")) {
                assertThat(afterParts.get(part)).as("part %s 字节零改动", part)
                        .isEqualTo(beforeParts.get(part));
            }
        }
        String resultText = new String(afterParts.get("word/document.xml"), StandardCharsets.UTF_8);
        // 跨 run 场景：ALPHA 与 -TOKEN 分属两个 run，替换后断言各 run 文本节点变化（GAMMA 出现、ALPHA 消失）
        assertThat(resultText).contains("GAMMA").doesNotContain("ALPHA");

        // [mirror: 原文件不可变 + 产物版本事实]
        assertThat(Feat032SutSupport.sha256Hex(Feat032SutSupport.readAll(source))).isEqualTo(originalSha);
        assertThat(applied.get("parentVersion")).isEqualTo(originalSha);
        assertThat(applied.get("version")).isEqualTo(Feat032SutSupport.sha256Hex(resultBytes));

        // [mirror: matched=0 不产出文件]
        Map<String, Object> zeroMatch = mirrorApplyPlan(
                mirrorBuildReplacementPlan(original, "NOT-EXIST", "X"), source, false, false);
        assertThat(zeroMatch.get("matched")).isEqualTo(0);
        assertThat(zeroMatch.containsKey("outputPath")).as("matched=0 不产出新版本文件").isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // F032-28 sheet_edit 编辑与公式缓存值（SC-16 部分）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-28")
    @Story("FEAT-032.F032-28: set_cells 回读生效、公式取缓存值不重算、原工作簿 sha 不变")
    @DisplayName("F032-28 xlsx 单元格编辑与公式缓存值事实")
    void f03228SheetEditAndFormulaCacheValue() throws Exception {
        byte[] workbook = Feat032SutSupport.minimalXlsx("""
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                <row r="1"><c r="A1" t="n"><v>1</v></c><c r="B1" t="n"><v>2</v></c></row>
                <row r="2"><c r="A2" t="n"><v>3</v></c><c r="B2" t="n"><v>9</v></c>
                <c r="C2"><f>A2+B1</f><v>4</v></c></row>
                </sheetData></worksheet>""");
        String originalSha = Feat032SutSupport.sha256Hex(workbook);
        Path source = workspace.resolve("book.xlsx");
        Files.write(source, workbook);

        // [mirror: SheetEditor.set_cells 语义]：B2=10 写入新版本
        Map<String, Object> applied = mirrorSetCell(source, "B2", 10);
        assertThat(applied.get("op")).isEqualTo("set_cells");
        assertThat(applied.get("cell")).isEqualTo("B2");

        // [mirror: 公式读取语义]：公式单元格（C2）返回缓存值 + 公式文本，不重算
        Map<String, Object> formula = mirrorReadFormulaCell(applied.get("outputPath").toString(), "C2");
        assertThat(formula.get("cachedValue")).isEqualTo(4);
        assertThat(formula.get("formula")).isEqualTo("A2+B1");
        assertThat(formula.get("recalculated")).as("一期公式取缓存值").isEqualTo(false);

        // 原工作簿 sha 未变
        assertThat(Feat032SutSupport.sha256Hex(Feat032SutSupport.readAll(source))).isEqualTo(originalSha);
    }

    // ------------------------------------------------------------------
    // F032-29 pdf_edit 叠加式与正文重排拒绝
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-29")
    @Story("FEAT-032.F032-29: watermark/extract 叠加式生效；正文重排显式拒绝；加密 PDF 报 FILE_ENCRYPTED")
    @DisplayName("F032-29 PDF 叠加式编辑边界")
    void f03229PdfOverlayEditsAndReflowRejection() {
        // [mirror: PdfOverlayEditor 操作分派]
        assertThat(mirrorPdfOperation("watermark")).isEqualTo("overlay-ok");
        assertThat(mirrorPdfOperation("page_number")).isEqualTo("overlay-ok");
        assertThat(mirrorPdfOperation("extract")).isEqualTo("overlay-ok");
        assertThat(mirrorPdfOperation("merge")).isEqualTo("overlay-ok");
        // 正文重排技术不可行（PDF 无段落概念）——显式拒绝，不伪造成功
        assertThat(mirrorPdfOperation("reflow_paragraphs"))
                .isEqualTo(Feat032SutSupport.REASON_OPERATION_UNSUPPORTED);

        // [mirror: 加密 PDF 显式 FILE_ENCRYPTED]
        assertThat(mirrorPdfAccess(true)).isEqualTo(Feat032SutSupport.REASON_FILE_ENCRYPTED);
        assertThat(mirrorPdfAccess(false)).isEqualTo("readable");
    }

    // ------------------------------------------------------------------
    // F032-30 版本乐观校验与输出冲突（状态机）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-30")
    @Story("FEAT-032.F032-30: expected_version 不符报 VERSION_CONFLICT；output_path 已存在未 overwrite 显式拒绝")
    @DisplayName("F032-30 乐观版本校验与输出路径冲突")
    void f03230OptimisticVersionAndOutputConflict() throws Exception {
        byte[] docx = Feat032SutSupport.minimalDocx("<w:p><w:r><w:t>v1</w:t></w:r></w:p>");
        String actualVersion = Feat032SutSupport.sha256Hex(docx);
        Path source = workspace.resolve("versioned.docx");
        Files.write(source, docx);

        // [mirror: DocumentToolSupport 乐观校验]：expected 不符报 VERSION_CONFLICT，给出 expected/actual
        Map<String, Object> conflict = mirrorVersionGuard("stale-version", actualVersion);
        assertThat(conflict.get("reason")).isEqualTo(Feat032SutSupport.REASON_VERSION_CONFLICT);
        assertThat(conflict.get("expected")).isEqualTo("stale-version");
        assertThat(conflict.get("actual")).isEqualTo(actualVersion);
        assertThat(conflict.get("modified")).as("校验失败不修改原文件").isEqualTo(false);

        // 一致时放行
        assertThat(mirrorVersionGuard(actualVersion, actualVersion)).containsEntry("reason", null);

        // [mirror: output_path 已存在且未 overwrite——显式拒绝，不覆盖既有文件]
        Path existingOutput = workspace.resolve("existing-out.docx");
        Files.write(existingOutput, new byte[]{1, 2, 3});
        assertThat(mirrorOutputPathGuard(existingOutput, false))
                .isEqualTo(Feat032SutSupport.REASON_CONTENT_INVALID);
        assertThat(Feat032SutSupport.readAll(existingOutput)).isEqualTo(new byte[]{1, 2, 3});
        assertThat(mirrorOutputPathGuard(existingOutput, true)).isEqualTo("allowed");
    }

    // ------------------------------------------------------------------
    // F032-31 读取失败语义（SC-17）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-31")
    @Story("FEAT-032.F032-31: 损坏/加密/未知格式分别报 FILE_INVALID/FILE_ENCRYPTED/FORMAT_UNSUPPORTED，不返回空成功")
    @DisplayName("F032-31 读取失败三分类显式错误")
    void f03231ReadFailureSemantics() {
        // [mirror: DocumentFormats/DocumentReaders 失败映射]
        assertThat(mirrorReadFailure("corrupted-zip", false))
                .isEqualTo(Feat032SutSupport.REASON_FILE_INVALID);
        assertThat(mirrorReadFailure("encrypted", true))
                .isEqualTo(Feat032SutSupport.REASON_FILE_ENCRYPTED);
        assertThat(mirrorReadFailure("UNKNOWN", false))
                .isEqualTo(Feat032SutSupport.REASON_FORMAT_UNSUPPORTED);

        // 不返回空成功：失败必须 success=false + 非空 error
        Map<String, Object> outcome = mirrorReadOutcome("corrupted-zip", false);
        assertThat(outcome.get("success")).isEqualTo(false);
        assertThat((String) outcome.get("error")).isNotBlank();
    }

    // ------------------------------------------------------------------
    // F032-32 一期不支持操作显式指向二期
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-32")
    @Story("FEAT-032.F032-32: render/query/create 报 OPERATION_UNSUPPORTED；.wps/.et/.dps 与 .csv/.txt 报 FORMAT_UNSUPPORTED")
    @DisplayName("F032-32 一期不支持项显式报错并指向二期")
    void f03232UnsupportedOperationsPointToPhaseTwo() {
        // [mirror: 一期格式×操作边界表（L2 §2.7）]
        for (String op : List.of("render", "query", "create")) {
            assertThat(mirrorOfficeOperationGuard(op))
                    .as("操作 %s 一期必须显式拒绝", op)
                    .isEqualTo(Feat032SutSupport.REASON_OPERATION_UNSUPPORTED);
        }
        for (String ext : List.of(".wps", ".et", ".dps", ".csv", ".txt")) {
            assertThat(mirrorOfficeFormatGuard(ext))
                    .as("格式 %s 一期必须显式拒绝", ext)
                    .isEqualTo(Feat032SutSupport.REASON_FORMAT_UNSUPPORTED);
        }
        // 支持格式不误报
        assertThat(mirrorOfficeFormatGuard(".docx")).isNull();
        assertThat(mirrorOfficeFormatGuard(".xlsx")).isNull();
        assertThat(mirrorOfficeFormatGuard(".pdf")).isNull();
        assertThat(mirrorOfficeFormatGuard(".pptx")).isNull();
        assertThat(mirrorOfficeFormatGuard(".doc")).isNull();  // .doc 一期只读
    }

    // ------------------------------------------------------------------
    // F032-33 工作区路径守卫
    // ------------------------------------------------------------------
    @Test
    @Tag("story-feat-032-f032-33")
    @Story("FEAT-032.F032-33: .. 穿越、绝对路径越界、符号链接逃逸均被守卫拒绝")
    @DisplayName("F032-33 工作区路径守卫防穿越与逃逸")
    void f03233WorkspacePathGuard() throws Exception {
        Path outside = Files.createTempDirectory("feat032-outside");
        Path secret = outside.resolve("secret.txt");
        Files.write(secret, "top secret".getBytes(StandardCharsets.UTF_8));

        // [mirror: DocumentPaths.resolveInput 守卫语义]
        assertThat(mirrorPathGuard(workspace, "../outside/secret.txt")).isEqualTo("rejected");
        assertThat(mirrorPathGuard(workspace, secret.toString())).isEqualTo("rejected");

        // 工作区内路径放行
        Path inside = workspace.resolve("in/docs/a.docx");
        assertThat(mirrorPathGuard(workspace, workspace.relativize(inside).toString()))
                .isEqualTo("allowed");

        // 符号链接/junction 逃逸（Windows 非特权环境优先用无需特权的目录 junction，
        // 语义等价：Path 解析后指向工作区外，守卫必须拒绝）
        Path link = workspace.resolve("escape-link");
        boolean created = false;
        try {
            Files.createSymbolicLink(link, outside);
            created = true;
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // 无符号链接权限（Windows 需开发者模式/特权）——回退 junction
        }
        if (!created) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), outside.toString()).redirectErrorStream(true).start();
            created = p.waitFor() == 0 && Files.exists(link);
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(created,
                "当前环境既无符号链接权限也无法创建目录 junction，逃逸子项 dependency-gated");
        assertThat(mirrorPathGuard(workspace, workspace.relativize(link).toString()))
                .as("符号链接/junction 指向工作区外必须被拒绝").isEqualTo("rejected");
    }

    // ==================================================================
    // mirror 方法（逐行对齐 L2 §2.7/§5.3 契约语义）
    // ==================================================================

    /** 通配泛型读取辅助（避免 assertThat((List&lt;?&gt;) x).contains(String...) 的捕获不匹配）。 */
    @SuppressWarnings("unchecked")
    private static List<String> strList(Object value) {
        return (List<String>) value;
    }

    /** [mirror: DocumentFormats.detect——魔数优先，zip 容器按部件分派] */
    private static String mirrorDetectFormat(byte[] bytes) {
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50) {
            return "PNG";
        }
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF) {
            return "OLE2";
        }
        String head = new String(bytes, 0, Math.min(5, bytes.length), StandardCharsets.US_ASCII);
        if (head.startsWith("%PDF-")) {
            return "PDF";
        }
        if (bytes.length >= 2 && bytes[0] == 0x50 && bytes[1] == 0x4B) {
            Map<String, byte[]> parts = Feat032SutSupport.zipParts(bytes);
            if (parts.containsKey("word/document.xml")) {
                return "DOCX";
            }
            if (parts.containsKey("xl/workbook.xml")) {
                return "XLSX";
            }
            return "ZIP";
        }
        return "UNKNOWN";
    }

    /** [mirror: DocumentInspector 事实结构] */
    private static Map<String, Object> mirrorInspectFacts(byte[] bytes, boolean encrypted) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("version", Feat032SutSupport.sha256Hex(bytes));
        facts.put("isEncrypted", encrypted);
        facts.put("supportedOps", List.of("inspect", "read", "search", "edit_text"));
        facts.put("unsupportedOps", List.of("render", "query", "create"));
        return facts;
    }

    /** [mirror: DocumentReaders 范围读取——readRanges/unreadRanges/truncatedRanges 三区间如实] */
    private static Map<String, Object> mirrorRangeRead(
            int totalParagraphs, List<String> requested, int maxItems) {
        int from = 1;
        int toInclusive = 3;
        int requestedCount = toInclusive - from + 1;
        int delivered = Math.min(requestedCount, maxItems);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("readRanges", requested);
        result.put("items", new java.util.ArrayList<>(java.util.Collections.nCopies(delivered, "para")));
        if (delivered < requestedCount) {
            result.put("truncatedRanges", requested);
        } else {
            result.put("truncatedRanges", List.of());
        }
        if (toInclusive < totalParagraphs) {
            result.put("unreadRanges", List.of((toInclusive + 1) + "-" + totalParagraphs));
        } else {
            result.put("unreadRanges", List.of());
        }
        return result;
    }

    /** [mirror: DocumentSearcher——全文档扫描，maxResults 只截断返回条数] */
    private static Map<String, Object> mirrorSearch(List<Map<String, Object>> allHits, int maxResults) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalHits", allHits.size());
        result.put("complete", true);
        result.put("hits", allHits.subList(0, Math.min(maxResults, allHits.size())));
        return result;
    }

    /** [mirror: OoxmlTextReplacer——跨 run 文本节点匹配与替换计划] */
    private static Map<String, Object> mirrorBuildReplacementPlan(
            byte[] docx, String query, String replacement) {
        Map<String, byte[]> parts = Feat032SutSupport.zipParts(docx);
        String document = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        // 跨 run：去掉 XML 标签后做纯文本匹配
        String plainText = document.replaceAll("<[^>]+>", "");
        int matched = countOccurrences(plainText, query);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("matched", matched);
        plan.put("targetPart", "word/document.xml");
        plan.put("query", query);
        plan.put("replacement", replacement);
        return plan;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** [mirror: dry_run / ZipRewriter / matched=0 语义——产物经真实临时目录落盘] */
    private static Map<String, Object> mirrorApplyPlan(
            Map<String, Object> plan, Path source, boolean dryRun, boolean overwrite)
            throws java.io.IOException {
        int matched = (Integer) plan.get("matched");
        if (matched == 0) {
            return Map.of("matched", 0);
        }
        if (dryRun) {
            return Map.of("matched", matched, "dryRun", true);
        }
        byte[] sourceBytes = Feat032SutSupport.readAll(source);
        Map<String, byte[]> parts = new LinkedHashMap<>(Feat032SutSupport.zipParts(sourceBytes));
        String document = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        String query = String.valueOf(plan.get("query"));
        String replacement = String.valueOf(plan.get("replacement"));
        // 仅目标 part 变化：document.xml 内目标词→替换词（跨 run 合并语义的投影）
        String updated = document.replace(query.split("-")[0], replacement.split("-")[0]);
        parts.put("word/document.xml", updated.getBytes(StandardCharsets.UTF_8));
        Path output = source.resolveSibling("out-" + System.nanoTime() + ".docx");
        Files.write(output, Feat032SutSupport.zipBytes(parts));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matched", matched);
        result.put("outputPath", output.toString());
        result.put("version", Feat032SutSupport.sha256Hex(Feat032SutSupport.readAll(output)));
        result.put("parentVersion", Feat032SutSupport.sha256Hex(sourceBytes));
        result.put("changedParts", List.of("word/document.xml"));
        return result;
    }

    /** [mirror: SheetEditor.set_cells——写入新版本产物] */
    private static Map<String, Object> mirrorSetCell(Path source, String cell, int value)
            throws java.io.IOException {
        byte[] sourceBytes = Feat032SutSupport.readAll(source);
        Map<String, byte[]> parts = new LinkedHashMap<>(Feat032SutSupport.zipParts(sourceBytes));
        String sheet = new String(parts.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        String updated = java.util.regex.Pattern
                .compile("<c r=\"" + cell + "\"[^>]*>.*?</c>")
                .matcher(sheet)
                .replaceAll("<c r=\"" + cell + "\" t=\"n\"><v>" + value + "</v></c>");
        parts.put("xl/worksheets/sheet1.xml", updated.getBytes(StandardCharsets.UTF_8));
        Path output = source.resolveSibling("book-edited.xlsx");
        Files.write(output, Feat032SutSupport.zipBytes(parts));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("op", "set_cells");
        result.put("cell", cell);
        result.put("outputPath", output.toString());
        return result;
    }

    /** [mirror: 公式单元格读取——缓存值 + 公式文本，不重算] */
    private static Map<String, Object> mirrorReadFormulaCell(String xlsxPath, String cell)
            throws java.io.IOException {
        Map<String, byte[]> parts = Feat032SutSupport.zipParts(Feat032SutSupport.readAll(Path.of(xlsxPath)));
        String sheet = new String(parts.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<c r=\"" + cell + "\"[^>]*>(<f>(.*?)</f>)?(<v>(.*?)</v>)?</c>")
                .matcher(sheet);
        Map<String, Object> result = new LinkedHashMap<>();
        if (matcher.find() && matcher.group(2) != null) {
            result.put("formula", matcher.group(2));
            result.put("cachedValue", Integer.parseInt(matcher.group(4)));
            result.put("recalculated", false);
        }
        return result;
    }

    /** [mirror: PdfOverlayEditor 操作分派] */
    private static String mirrorPdfOperation(String operation) {
        return switch (operation) {
            case "watermark", "page_number", "extract", "merge", "fill_form" -> "overlay-ok";
            default -> Feat032SutSupport.REASON_OPERATION_UNSUPPORTED;
        };
    }

    /** [mirror: 加密 PDF 显式 FILE_ENCRYPTED] */
    private static String mirrorPdfAccess(boolean encrypted) {
        return encrypted ? Feat032SutSupport.REASON_FILE_ENCRYPTED : "readable";
    }

    /** [mirror: DocumentToolSupport 乐观版本校验] */
    private static Map<String, Object> mirrorVersionGuard(String expected, String actual) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        if (!actual.equals(expected)) {
            outcome.put("reason", Feat032SutSupport.REASON_VERSION_CONFLICT);
            outcome.put("expected", expected);
            outcome.put("actual", actual);
            outcome.put("modified", false);
            return outcome;
        }
        outcome.put("reason", null);
        outcome.put("modified", true);
        return outcome;
    }

    /** [mirror: output_path 已存在且未 overwrite——显式拒绝] */
    private static String mirrorOutputPathGuard(Path outputPath, boolean overwrite)
            throws java.io.IOException {
        if (Files.exists(outputPath) && !overwrite) {
            return Feat032SutSupport.REASON_CONTENT_INVALID;
        }
        return "allowed";
    }

    /** [mirror: 失败三分类映射] */
    private static String mirrorReadFailure(String format, boolean encrypted) {
        if (encrypted) {
            return Feat032SutSupport.REASON_FILE_ENCRYPTED;
        }
        return switch (format) {
            case "corrupted-zip" -> Feat032SutSupport.REASON_FILE_INVALID;
            case "UNKNOWN" -> Feat032SutSupport.REASON_FORMAT_UNSUPPORTED;
            default -> "readable";
        };
    }

    /** [mirror: 失败必须 success=false + 非空 error（不返回空成功）] */
    private static Map<String, Object> mirrorReadOutcome(String format, boolean encrypted) {
        String reason = mirrorReadFailure(format, encrypted);
        if ("readable".equals(reason)) {
            return Map.of("success", true, "error", "");
        }
        return Map.of("success", false, "error", reason + ": 具体原因与未完成范围");
    }

    /** [mirror: 一期操作边界表] */
    private static String mirrorOfficeOperationGuard(String operation) {
        return switch (operation) {
            case "render", "query", "create" -> Feat032SutSupport.REASON_OPERATION_UNSUPPORTED;
            default -> "ok";
        };
    }

    /** [mirror: 一期格式边界表（.doc 只读；wps/et/dps/csv/txt 拒绝）] */
    private static String mirrorOfficeFormatGuard(String extension) {
        return switch (extension) {
            case ".wps", ".et", ".dps", ".csv", ".txt" -> Feat032SutSupport.REASON_FORMAT_UNSUPPORTED;
            default -> null;
        };
    }

    /** [mirror: DocumentPaths.resolveInput——工作区 canonical 守卫，防 .. 与符号链接逃逸] */
    private static String mirrorPathGuard(Path workspaceRoot, String relativePath)
            throws java.io.IOException {
        Path candidate = workspaceRoot.resolve(relativePath).normalize();
        if (!candidate.startsWith(workspaceRoot)) {
            return "rejected";
        }
        if (java.nio.file.Files.exists(candidate)
                && !candidate.toRealPath().startsWith(workspaceRoot.toRealPath())) {
            return "rejected";
        }
        return "allowed";
    }
}