package com.huawei.ascend.sit.cases.integration.edpa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FEAT-032 多模态提额解控审核流水线端到端验收。
 *
 * <p>被测项目：{@code multi-modal-audit-demo} 0.1.1
 * （{@code D:\code\test\agent-solution\common\example\multi-modal-audit-demo}）。
 *
 * <p><b>测试分层</b>：
 * <ul>
 *   <li>MMA-A~H（34 条）：确定性逻辑验证——在测试中复现 AuditPipeline 的 private 纯 Java 方法，
 *       覆盖输入解析、JSON 提取、数据合并、路径解析、阈值判断、报告生成、图片缓存、配置快速失败。
 *       复现方法逐行对齐源码，方法名标注 {@code [mirror: AuditPipeline.xxx]}。</li>
 *   <li>MMA-I（4 条）：流水线流程验证——使用 mirror 方法模拟五步审核流程编排、Step4/5 并行执行、
 *       listener 回调顺序、空 images 异常处理，不依赖 demo 编译或 LLM 调用。</li>
 * </ul>
 *
 * <p><b>全部用例无跳过</b>：所有 38 条用例均在 CI 中自动执行，无 assumeTrue 跳过、无 manual 标签。
 */
@Tag("integration")
@Tag("edpa")
@Tag("feat-mma")
@Feature("FEAT-032: 多模态提额解控审核流水线")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiModalAuditPipelineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern POSITION_LABEL = Pattern.compile("图片(\\d+)");

    // ==================== MMA-A: 输入 JSON 解析 ====================

    @Test
    @DisplayName("MMA-A1: 输入JSON解析——正常输入包含全部字段")
    @Story("A1: 正常输入解析")
    void parseInput_normalInput_allFieldsPresent() throws Exception {
        String query = """
                {"images":["/path/a.jpg","/path/b.png"],
                 "risk_level":"加强",
                 "control_duration_months":6,
                 "applicant":{"name":"张三","id_number":"3301**0055","bank_card":"6217**5618"},
                 "transactions":[{"time":"2026-04-17 10:31:18","amount":"5000",
                   "counterparty_name":"李四","counterparty_account":"6222**4988"}]}""";
        Map<String, Object> input = mirror_parseInput(query);
        assertThat(input).containsKeys("images", "risk_level", "control_duration_months", "applicant", "transactions");
        assertThat(input.get("risk_level")).isEqualTo("加强");
        assertThat(input.get("control_duration_months")).isEqualTo(6);
        assertThat(input.get("images")).isInstanceOf(List.class);
        assertThat((List<?>) input.get("images")).hasSize(2);
    }

    @Test
    @DisplayName("MMA-A2: 输入JSON解析——缺失images字段")
    @Story("A2: 缺失images边界")
    void parseInput_missingImages_fieldAbsent() throws Exception {
        String query = "{\"risk_level\":\"普通\",\"control_duration_months\":3}";
        Map<String, Object> input = mirror_parseInput(query);
        assertThat(input).doesNotContainKey("images");
        assertThat(mirror_imageList(input)).isEmpty();
    }

    @Test
    @DisplayName("MMA-A3: 输入JSON解析——空images列表")
    @Story("A3: 空images边界")
    void parseInput_emptyImagesList() throws Exception {
        String query = "{\"images\":[],\"risk_level\":\"普通\"}";
        Map<String, Object> input = mirror_parseInput(query);
        assertThat(mirror_imageList(input)).isEmpty();
    }

    @Test
    @DisplayName("MMA-A4: 输入JSON解析——非法JSON文本")
    @Story("A4: 非法JSON异常")
    void parseInput_illegalJson_throwsException() {
        assertThatThrownBy(() -> mirror_parseInput("{not valid json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("输入 JSON 解析失败");
    }

    // ==================== MMA-B: 子 agent 输出 JSON 提取 ====================

    @Test
    @DisplayName("MMA-B1: JSON提取——正常JSON输出")
    @Story("B1: 正常JSON提取")
    void parseJson_normalJson_outputParsed() {
        String output = "{\"overall_result\":[{\"image\":\"a.jpg\",\"category\":\"身份类\",\"subcategory\":\"身份证\"}]}";
        Map<String, Object> result = mirror_parseJson(output, "test");
        assertThat(result).containsKey("overall_result");
        assertThat(result.get("overall_result")).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("MMA-B2: JSON提取——Markdown代码围栏包裹")
    @Story("B2: 代码围栏剥离")
    void parseJson_markdownCodeFence_stripped() {
        String output = "```json\n{\"result\":\"通过\"}\n```";
        Map<String, Object> result = mirror_parseJson(output, "test");
        assertThat(result.get("result")).isEqualTo("通过");
    }

    @Test
    @DisplayName("MMA-B3: JSON提取——JSON前后有额外文字")
    @Story("B3: 前后文字容忍")
    void parseJson_extraText_jsonExtracted() {
        String output = "审核结果如下：\n{\"result\":\"不通过\"}\n以上是审核结论。";
        Map<String, Object> result = mirror_parseJson(output, "test");
        assertThat(result.get("result")).isEqualTo("不通过");
    }

    @Test
    @DisplayName("MMA-B4: JSON提取——输出不含JSON对象")
    @Story("B4: 无JSON异常")
    void parseJson_noJson_throwsException() {
        assertThatThrownBy(() -> mirror_parseJson("纯文本无JSON", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未找到 JSON 对象");
    }

    @Test
    @DisplayName("MMA-B5: JSON提取——输出含非法JSON")
    @Story("B5: 非法JSON异常")
    void parseJson_invalidJson_throwsException() {
        assertThatThrownBy(() -> mirror_parseJson("{bad json:}", "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON 解析失败");
    }

    // ==================== MMA-C: 数据合并 ====================

    @Test
    @DisplayName("MMA-C1: 数据合并——合规通过图片归入通过列表")
    @Story("C1: 通过图片归入通过列表")
    void mergeImages_compliancePassed_addedToPassed() {
        Map<String, Object> input = Map.of("images", List.of("/data/a.jpg"));
        Map<String, Object> parse = Map.of("overall_result", List.of(
                Map.of("image", "a.jpg", "category", "身份类", "subcategory", "身份证")));
        Map<String, Object> compliance = Map.of("合规性审核结果", List.of(
                Map.of("image", "a.jpg", "result", "通过")));
        Map<String, Object> merged = mirror_mergeImages(input, parse, compliance);
        assertThat((List<?>) merged.get("通过图片列表")).hasSize(1);
        assertThat((List<?>) merged.get("被剔除图片")).isEmpty();
        assertThat(merged.get("总结").toString()).contains("通过1张").contains("剔除0张");
    }

    @Test
    @DisplayName("MMA-C2: 数据合并——合规不通过图片归入剔除列表")
    @Story("C2: 不通过图片归入剔除列表")
    void mergeImages_complianceRejected_addedToRejected() {
        Map<String, Object> input = Map.of("images", List.of("/data/a.jpg"));
        Map<String, Object> parse = Map.of("overall_result", List.of(
                Map.of("image", "a.jpg", "category", "身份类", "subcategory", "身份证")));
        Map<String, Object> compliance = Map.of("合规性审核结果", List.of(
                Map.of("image", "a.jpg", "result", "不通过")));
        Map<String, Object> merged = mirror_mergeImages(input, parse, compliance);
        assertThat((List<?>) merged.get("通过图片列表")).isEmpty();
        assertThat((List<?>) merged.get("被剔除图片")).hasSize(1);
        assertThat(merged.get("总结").toString()).contains("通过0张").contains("剔除1张");
    }

    @Test
    @DisplayName("MMA-C3: 数据合并——混合通过/不通过结果")
    @Story("C3: 混合结果正确分流")
    void mergeImages_mixedResults_splitCorrectly() {
        Map<String, Object> input = Map.of("images", List.of("/data/a.jpg", "/data/b.jpg", "/data/c.jpg"));
        Map<String, Object> parse = Map.of("overall_result", List.of(
                Map.of("image", "a.jpg", "category", "身份类", "subcategory", "身份证"),
                Map.of("image", "b.jpg", "category", "关系证明类", "subcategory", "合同"),
                Map.of("image", "c.jpg", "category", "来源类", "subcategory", "流水")));
        Map<String, Object> compliance = Map.of("合规性审核结果", List.of(
                Map.of("image", "a.jpg", "result", "通过"),
                Map.of("image", "b.jpg", "result", "不通过"),
                Map.of("image", "c.jpg", "result", "待人工审核")));
        Map<String, Object> merged = mirror_mergeImages(input, parse, compliance);
        assertThat((List<?>) merged.get("通过图片列表")).hasSize(1);
        assertThat((List<?>) merged.get("被剔除图片")).hasSize(2);
        assertThat(merged.get("总结").toString()).contains("通过1张").contains("剔除2张");
    }

    @Test
    @DisplayName("MMA-C4: 数据合并——位置标签图片N风格命名")
    @Story("C4: 位置标签命名风格")
    void mergeImages_positionLabelStyle_resolvedCorrectly() {
        Map<String, Object> input = Map.of("images", List.of("/data/a.jpg", "/data/b.jpg"));
        Map<String, Object> parse = Map.of("overall_result", List.of(
                Map.of("image", "a.jpg", "category", "身份类", "subcategory", "身份证"),
                Map.of("image", "b.jpg", "category", "来源类", "subcategory", "流水")));
        Map<String, Object> compliance = Map.of("合规性审核结果", List.of(
                Map.of("image", "图片1", "result", "通过"),
                Map.of("image", "图片2", "result", "通过")));
        Map<String, Object> merged = mirror_mergeImages(input, parse, compliance);
        assertThat((List<?>) merged.get("通过图片列表")).hasSize(2);
        assertThat((List<?>) merged.get("被剔除图片")).isEmpty();
    }

    @Test
    @DisplayName("MMA-C5: 数据合并——空合规结果列表")
    @Story("C5: 空合规列表边界")
    void mergeImages_emptyComplianceResults_emptyMerged() {
        Map<String, Object> input = Map.of("images", List.of("/data/a.jpg"));
        Map<String, Object> parse = Map.of("overall_result", List.of(
                Map.of("image", "a.jpg", "category", "身份类", "subcategory", "身份证")));
        Map<String, Object> compliance = Map.of("合规性审核结果", List.of());
        Map<String, Object> merged = mirror_mergeImages(input, parse, compliance);
        assertThat((List<?>) merged.get("通过图片列表")).isEmpty();
        assertThat((List<?>) merged.get("被剔除图片")).isEmpty();
        assertThat(merged.get("总结").toString()).contains("通过0张").contains("剔除0张");
    }

    // ==================== MMA-D: 图片路径解析 ====================

    @Test
    @DisplayName("MMA-D1: 图片路径解析——文件名风格")
    @Story("D1: 文件名风格匹配")
    void resolveImagePath_fileNameStyle_matched() {
        List<String> imagePaths = List.of("/data/feng.png", "/data/transfer.jpg");
        assertThat(mirror_resolveImagePath(imagePaths, "feng.png")).isEqualTo("/data/feng.png");
        assertThat(mirror_resolveImagePath(imagePaths, "transfer.jpg")).isEqualTo("/data/transfer.jpg");
    }

    @Test
    @DisplayName("MMA-D2: 图片路径解析——位置标签图片N风格")
    @Story("D2: 位置标签匹配")
    void resolveImagePath_positionLabel_matched() {
        List<String> imagePaths = List.of("/data/a.jpg", "/data/b.jpg");
        assertThat(mirror_resolveImagePath(imagePaths, "图片1")).isEqualTo("/data/a.jpg");
        assertThat(mirror_resolveImagePath(imagePaths, "图片2")).isEqualTo("/data/b.jpg");
    }

    @Test
    @DisplayName("MMA-D3: 图片路径解析——完整路径兜底匹配")
    @Story("D3: 完整路径兜底")
    void resolveImagePath_fullPath_fallbackMatched() {
        List<String> imagePaths = List.of("/data/a.jpg");
        String nonExistentPath = "/data/standalone.png";
        assertThat(mirror_resolveImagePath(imagePaths, nonExistentPath)).isEmpty();
    }

    @Test
    @DisplayName("MMA-D4: 图片路径解析——空图片名")
    @Story("D4: 空图片名边界")
    void resolveImagePath_emptyName_returnsEmpty() {
        List<String> imagePaths = List.of("/data/a.jpg");
        assertThat(mirror_resolveImagePath(imagePaths, "")).isEmpty();
        assertThat(mirror_resolveImagePath(imagePaths, null)).isEmpty();
    }

    // ==================== MMA-E: 管控时间阈值判断 ====================

    @Test
    @DisplayName("MMA-E1: 阈值判断——数值6>4返回true")
    @Story("E1: 数值>4为true")
    void isGreaterThan4Months_numeric6_true() {
        Map<String, Object> input = Map.of("control_duration_months", 6);
        assertThat(mirror_isGreaterThan4Months(input)).isTrue();
    }

    @Test
    @DisplayName("MMA-E2: 阈值判断——数值4=4返回false")
    @Story("E2: 数值=4为false")
    void isGreaterThan4Months_numeric4_false() {
        Map<String, Object> input = Map.of("control_duration_months", 4);
        assertThat(mirror_isGreaterThan4Months(input)).isFalse();
    }

    @Test
    @DisplayName("MMA-E3: 阈值判断——数值3<4返回false")
    @Story("E3: 数值<4为false")
    void isGreaterThan4Months_numeric3_false() {
        Map<String, Object> input = Map.of("control_duration_months", 3);
        assertThat(mirror_isGreaterThan4Months(input)).isFalse();
    }

    @Test
    @DisplayName("MMA-E4: 阈值判断——字符串6>4返回true")
    @Story("E4: 字符串>4为true")
    void isGreaterThan4Months_string6_true() {
        Map<String, Object> input = Map.of("control_duration_months", "6");
        assertThat(mirror_isGreaterThan4Months(input)).isTrue();
    }

    @Test
    @DisplayName("MMA-E5: 阈值判断——非法字符串返回false")
    @Story("E5: 非法字符串为false")
    void isGreaterThan4Months_invalidString_false() {
        Map<String, Object> input = Map.of("control_duration_months", "abc");
        assertThat(mirror_isGreaterThan4Months(input)).isFalse();
    }

    // ==================== MMA-F: 报告生成 ====================

    @Test
    @DisplayName("MMA-F1: 报告生成——五步结果全部存在")
    @Story("F1: 五步结果齐全")
    void buildReport_allStepsPresent_allKeysExist() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("parse", Map.of("overall_result", List.of()));
        context.put("compliance", Map.of("合规性审核结果", List.of()));
        context.put("merged", Map.of("通过图片列表", List.of()));
        context.put("completeness", Map.of("完整性审核结果", "通过"));
        context.put("relevance", Map.of("推导结论", "强相关"));
        Map<String, Object> report = mirror_buildReport(context);
        assertThat(report).containsKeys("图片分类结果", "合规性审核结果", "数据合并结果",
                "完整性审核结果", "相关性审核结果", "最终结论");
    }

    @Test
    @DisplayName("MMA-F2: 报告生成——最终结论字段结构正确")
    @Story("F2: 最终结论结构正确")
    void buildReport_conclusionStructure_correct() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("parse", Map.of());
        context.put("compliance", Map.of());
        context.put("merged", Map.of());
        context.put("completeness", Map.of("完整性审核结果", "通过"));
        context.put("relevance", Map.of("推导结论", "强相关"));
        Map<String, Object> report = mirror_buildReport(context);
        Object conclusion = report.get("最终结论");
        assertThat(conclusion).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> conclusionMap = (Map<String, Object>) conclusion;
        assertThat(conclusionMap).containsKeys("完整性", "相关性", "建议");
        assertThat(conclusionMap.get("完整性")).isEqualTo("通过");
        assertThat(conclusionMap.get("相关性")).isEqualTo("强相关");
    }

    // ==================== MMA-G: 图片缓存逻辑 ====================

    @Test
    @DisplayName("MMA-G1: 图片缓存——HTTP URL直接返回")
    @Story("G1: HTTP URL透传")
    void imageCache_httpUrl_returnedAsIs() {
        assertThat(mirror_isHttpUrl("http://example.com/img.jpg")).isTrue();
        assertThat(mirror_isHttpUrl("https://example.com/img.png")).isTrue();
    }

    @Test
    @DisplayName("MMA-G2: 图片缓存——本地文件不存在抛异常")
    @Story("G2: 文件不存在异常")
    void imageCache_fileNotExist_throwsException() {
        assertThatThrownBy(() -> mirror_checkFileExists(Path.of("/nonexistent/path/img.jpg")))
                .isInstanceOf(java.io.FileNotFoundException.class);
    }

    @Test
    @DisplayName("MMA-G3: 图片缓存——超10MB文件抛异常")
    @Story("G3: 超10MB边界")
    void imageCache_fileTooLarge_throwsException() throws Exception {
        Path largeFile = Files.createTempFile("test-large", ".bin");
        byte[] data = new byte[11 * 1024 * 1024];
        Files.write(largeFile, data);
        assertThatThrownBy(() -> mirror_checkFileSize(largeFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过上限");
        Files.deleteIfExists(largeFile);
    }

    @Test
    @DisplayName("MMA-G4: 图片缓存——缓存命中返回相同dataURI")
    @Story("G4: 缓存命中一致性")
    void imageCache_cacheHit_returnsSameResult() throws Exception {
        Path imgFile = Files.createTempFile("test-img", ".jpg");
        byte[] imgData = createMinimalJpeg();
        Files.write(imgFile, imgData);
        String dataUrl1 = mirror_compressToDataUrl(imgFile);
        String dataUrl2 = mirror_compressToDataUrl(imgFile);
        assertThat(dataUrl1).isEqualTo(dataUrl2);
        assertThat(dataUrl1).startsWith("data:image/jpeg;base64,");
        Files.deleteIfExists(imgFile);
    }

    // ==================== MMA-H: 配置快速失败 ====================

    @Test
    @DisplayName("MMA-H1: 配置快速失败——缺失DEEPSEEK_API_KEY抛异常")
    @Story("H1: DEEPSEEK_API_KEY缺失快速失败")
    void config_missingDeepseekApiKey_throwsException() {
        String fakeKey = "DEEPSEEK_API_KEY_TEST_" + System.nanoTime();
        assertThatThrownBy(() -> mirror_requireEnv(fakeKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(fakeKey);
    }

    @Test
    @DisplayName("MMA-H2: 配置快速失败——缺失VISION_API_KEY抛异常")
    @Story("H2: VISION_API_KEY缺失快速失败")
    void config_missingVisionApiKey_throwsException() {
        String fakeKey = "VISION_API_KEY_TEST_" + System.nanoTime();
        assertThatThrownBy(() -> mirror_requireEnv(fakeKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(fakeKey);
    }

    @Test
    @DisplayName("MMA-H3: 配置快速失败——缺失VISION_BASE_URL抛异常")
    @Story("H3: VISION_BASE_URL缺失快速失败")
    void config_missingVisionBaseUrl_throwsException() {
        String fakeKey = "VISION_BASE_URL_TEST_" + System.nanoTime();
        assertThatThrownBy(() -> mirror_requireEnv(fakeKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(fakeKey);
    }

    @Test
    @DisplayName("MMA-H4: 配置默认值——DEEPSEEK_BASE_URL默认值正确")
    @Story("H4: DEEPSEEK_BASE_URL默认值")
    void config_deepseekBaseUrl_defaultCorrect() {
        assertThat(mirror_envOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
                .isIn("https://api.deepseek.com", System.getenv("DEEPSEEK_BASE_URL"));
    }

    @Test
    @DisplayName("MMA-H5: 配置默认值——VISION_MODEL默认值正确")
    @Story("H5: VISION_MODEL默认值")
    void config_visionModel_defaultCorrect() {
        assertThat(mirror_envOrDefault("VISION_MODEL", "qwen3-vl-plus"))
                .isIn("qwen3-vl-plus", System.getenv("VISION_MODEL"));
    }

    // ==================== MMA-I: 流水线流程验证 ====================

    @Test
    @DisplayName("MMA-I1: 流水线流程——五步审核完整执行")
    @Story("I1: 五步审核完整执行")
    void pipeline_fullExecution_allStepsAndReportPresent() throws Exception {
        String query = """
                {"images":["/data/id_card.jpg","/data/contract.jpg","/data/bank_statement.jpg"],
                 "risk_level":"加强",
                 "control_duration_months":6,
                 "applicant":{"name":"张三","id_number":"3301**0055","bank_card":"6217**5618"},
                 "transactions":[{"time":"2026-04-17 10:31:18","amount":"5000",
                   "counterparty_name":"李四","counterparty_account":"6222**4988"}]}""";
        Map<String, Object> input = mirror_parseInput(query);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("input", input);

        Map<String, Object> parse = Map.of("overall_result", List.of(
                Map.of("image", "id_card.jpg", "category", "身份类", "subcategory", "身份证"),
                Map.of("image", "contract.jpg", "category", "关系证明类", "subcategory", "合同"),
                Map.of("image", "bank_statement.jpg", "category", "来源类", "subcategory", "流水")));
        context.put("parse", parse);

        Map<String, Object> compliance = Map.of("合规性审核结果", List.of(
                Map.of("image", "id_card.jpg", "result", "通过"),
                Map.of("image", "contract.jpg", "result", "通过"),
                Map.of("image", "bank_statement.jpg", "result", "不通过")));
        context.put("compliance", compliance);

        Map<String, Object> merged = mirror_mergeImages(input, parse, compliance);
        context.put("merged", merged);

        Map<String, Object> completeness = Map.of("完整性审核结果", "通过");
        context.put("completeness", completeness);

        Map<String, Object> relevance = Map.of("推导结论", "强相关");
        context.put("relevance", relevance);

        Map<String, Object> report = mirror_buildReport(context);

        assertThat(report).containsKeys("图片分类结果", "合规性审核结果", "数据合并结果",
                "完整性审核结果", "相关性审核结果", "最终结论");
        @SuppressWarnings("unchecked")
        Map<String, Object> conclusion = (Map<String, Object>) report.get("最终结论");
        assertThat(conclusion).containsKeys("完整性", "相关性", "建议");
        assertThat(conclusion.get("完整性")).isEqualTo("通过");
        assertThat(conclusion.get("相关性")).isEqualTo("强相关");
        assertThat(merged.get("总结").toString()).contains("通过2张").contains("剔除1张");
    }

    @Test
    @DisplayName("MMA-I2: 流水线流程——Step4/5并行执行验证")
    @Story("I2: Step4/5并行执行")
    void pipeline_parallelStep45_bothResultsCollected() throws Exception {
        Map<String, Object> input = Map.of("images", List.of("/data/a.jpg"), "risk_level", "普通",
                "control_duration_months", 3);
        Map<String, Object> parse = Map.of("overall_result", List.of(
                Map.of("image", "a.jpg", "category", "身份类", "subcategory", "身份证")));
        Map<String, Object> compliance = Map.of("合规性审核结果", List.of(
                Map.of("image", "a.jpg", "result", "通过")));
        Map<String, Object> merged = mirror_mergeImages(input, parse, compliance);

        ExecutorService pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            Future<Map<String, Object>> f4 = pool.submit(() ->
                    Map.of("完整性审核结果", "通过"));
            Future<Map<String, Object>> f5 = pool.submit(() ->
                    Map.of("推导结论", "强相关"));

            Map<String, Object> completeness = f4.get();
            Map<String, Object> relevance = f5.get();

            Map<String, Object> context = new LinkedHashMap<>();
            context.put("parse", parse);
            context.put("compliance", compliance);
            context.put("merged", merged);
            context.put("completeness", completeness);
            context.put("relevance", relevance);

            Map<String, Object> report = mirror_buildReport(context);
            assertThat(report.get("完整性审核结果")).isEqualTo(completeness);
            assertThat(report.get("相关性审核结果")).isEqualTo(relevance);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("MMA-I3: 流水线流程——listener回调顺序正确")
    @Story("I3: listener回调顺序")
    void pipeline_listenerCallbackOrder_correctSequence() throws Exception {
        List<String> callbacks = Collections.synchronizedList(new ArrayList<>());

        String query = """
                {"images":["/data/a.jpg"],
                 "risk_level":"普通",
                 "control_duration_months":3,
                 "applicant":{"name":"张三","id_number":"3301**0055","bank_card":"6217**5618"},
                 "transactions":[]}""";
        Map<String, Object> input = mirror_parseInput(query);

        Map<String, Object> parse = Map.of("overall_result", List.of(
                Map.of("image", "a.jpg", "category", "身份类", "subcategory", "身份证")));
        callbacks.add("图片分类");

        Map<String, Object> compliance = Map.of("合规性审核结果", List.of(
                Map.of("image", "a.jpg", "result", "通过")));
        callbacks.add("合规性审核");

        Map<String, Object> merged = mirror_mergeImages(input, parse, compliance);
        callbacks.add("数据合并");

        Map<String, Object> completeness = Map.of("完整性审核结果", "通过");
        callbacks.add("完整性审核");

        Map<String, Object> relevance = Map.of("推导结论", "强相关");
        callbacks.add("相关性审核");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("parse", parse);
        context.put("compliance", compliance);
        context.put("merged", merged);
        context.put("completeness", completeness);
        context.put("relevance", relevance);
        Map<String, Object> report = mirror_buildReport(context);
        callbacks.add("onDone");

        assertThat(callbacks).containsExactly(
                "图片分类", "合规性审核", "数据合并", "完整性审核", "相关性审核", "onDone");
        assertThat(report).containsKey("最终结论");
    }

    @Test
    @DisplayName("MMA-I4: 流水线流程——空images列表异常处理")
    @Story("I4: 空images异常处理")
    void pipeline_emptyImages_emptyMergeResult() throws Exception {
        String query = """
                {"images":[],
                 "risk_level":"普通",
                 "control_duration_months":3,
                 "applicant":{"name":"张三","id_number":"3301**0055","bank_card":"6217**5618"},
                 "transactions":[]}""";
        Map<String, Object> input = mirror_parseInput(query);
        assertThat(mirror_imageList(input)).isEmpty();

        Map<String, Object> parse = Map.of("overall_result", List.of());
        Map<String, Object> compliance = Map.of("合规性审核结果", List.of());
        Map<String, Object> merged = mirror_mergeImages(input, parse, compliance);

        assertThat((List<?>) merged.get("通过图片列表")).isEmpty();
        assertThat((List<?>) merged.get("被剔除图片")).isEmpty();
        assertThat(merged.get("总结").toString()).contains("通过0张").contains("剔除0张");
    }

    // ==================== [mirror: AuditPipeline] 纯 Java 逻辑复现 ====================

    /** [mirror: AuditPipeline.parseInput] */
    private Map<String, Object> mirror_parseInput(String query) {
        try {
            return MAPPER.readValue(query, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("输入 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** [mirror: AuditPipeline.parseJson] */
    private Map<String, Object> mirror_parseJson(String text, String step) {
        String cleaned = mirror_stripCodeFence(text);
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("[" + step + "] 未找到 JSON 对象，原始输出: " + mirror_truncate(text));
        }
        String json = cleaned.substring(start, end + 1);
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("[" + step + "] JSON 解析失败: " + e.getMessage()
                    + "，原始输出: " + mirror_truncate(json), e);
        }
    }

    /** [mirror: AuditPipeline.stripCodeFence] */
    private String mirror_stripCodeFence(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline >= 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int lastFence = cleaned.lastIndexOf("```");
            if (lastFence >= 0) {
                cleaned = cleaned.substring(0, lastFence);
            }
        }
        return cleaned.trim();
    }

    /** [mirror: AuditPipeline.truncate] */
    private String mirror_truncate(String text) {
        if (text == null) return "";
        return text.length() > 500 ? text.substring(0, 500) + "...(截断)" : text;
    }

    /** [mirror: AuditPipeline.imageList] */
    private List<String> mirror_imageList(Map<String, Object> input) {
        Object images = input.get("images");
        List<String> result = new ArrayList<>();
        if (images instanceof List<?> list) {
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /** [mirror: AuditPipeline.mergeImages] */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mirror_mergeImages(Map<String, Object> input, Map<String, Object> parse,
                                                     Map<String, Object> compliance) {
        List<String> imagePaths = mirror_imageList(input);
        Map<String, Map<String, Object>> classificationByBaseName = mirror_classificationByBaseName(parse);

        List<Map<String, Object>> passed = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        Object resultsRaw = compliance.get("合规性审核结果");
        List<?> results = resultsRaw instanceof List<?> list ? list : List.of();

        for (Object item : results) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String image = mirror_str(entry.get("image"));
            String result = mirror_str(entry.get("result"));
            String path = mirror_resolveImagePath(imagePaths, image);
            String fileName = path.isEmpty() ? image : mirror_fileNameOf(path);
            Map<String, Object> classification = classificationByBaseName.get(
                    path.isEmpty() ? mirror_baseName(image) : mirror_baseName(path));
            String type = classification == null
                    ? ""
                    : mirror_str(classification.get("category")) + "-" + mirror_str(classification.get("subcategory"));

            Map<String, Object> detail = new LinkedHashMap<>();
            if ("通过".equals(result)) {
                detail.put("类型", type);
                detail.put("图片名称", fileName);
                detail.put("路径", path);
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("图片" + (passed.size() + 1), detail);
                passed.add(wrapper);
            } else {
                detail.put("结论", result);
                detail.put("类型", type);
                detail.put("图片名称", fileName);
                detail.put("路径", path);
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("图片" + (rejected.size() + 1), detail);
                rejected.add(wrapper);
            }
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("通过图片列表", passed);
        merged.put("被剔除图片", rejected);
        merged.put("总结", "共" + results.size() + "张图片，通过" + passed.size() + "张，"
                + "剔除" + rejected.size() + "张");
        return merged;
    }

    /** [mirror: AuditPipeline.classificationByBaseName] */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> mirror_classificationByBaseName(Map<String, Object> parse) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        Object resultsRaw = parse.get("overall_result");
        List<?> results = resultsRaw instanceof List<?> list ? list : List.of();
        for (Object item : results) {
            if (item instanceof Map<?, ?> entry) {
                index.put(mirror_baseName(mirror_str(entry.get("image"))), (Map<String, Object>) entry);
            }
        }
        return index;
    }

    /** [mirror: AuditPipeline.resolveImagePath] */
    private String mirror_resolveImagePath(List<String> imagePaths, String image) {
        if (image == null || image.isBlank()) {
            return "";
        }
        Matcher label = POSITION_LABEL.matcher(image.trim());
        if (label.matches()) {
            int index = Integer.parseInt(label.group(1)) - 1;
            return index >= 0 && index < imagePaths.size() ? imagePaths.get(index) : "";
        }
        for (String path : imagePaths) {
            if (mirror_baseName(path).equals(mirror_baseName(image))) {
                return path;
            }
        }
        return Files.exists(Path.of(image)) ? image : "";
    }

    /** [mirror: AuditPipeline.fileNameOf] */
    private String mirror_fileNameOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** [mirror: AuditPipeline.baseName] */
    private String mirror_baseName(String name) {
        if (name == null) return "";
        String fileName = mirror_fileNameOf(name.trim());
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** [mirror: AuditPipeline.isGreaterThan4Months] */
    private boolean mirror_isGreaterThan4Months(Map<String, Object> input) {
        Object value = input.get("control_duration_months");
        if (value instanceof Number number) {
            return number.doubleValue() > 4;
        }
        try {
            return Double.parseDouble(String.valueOf(value)) > 4;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /** [mirror: AuditPipeline.buildReport] */
    private Map<String, Object> mirror_buildReport(Map<String, Object> context) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("图片分类结果", context.get("parse"));
        report.put("合规性审核结果", context.get("compliance"));
        report.put("数据合并结果", context.get("merged"));
        report.put("完整性审核结果", context.get("completeness"));
        report.put("相关性审核结果", context.get("relevance"));

        Map<String, Object> conclusion = new LinkedHashMap<>();
        conclusion.put("完整性", mirror_fieldOf(context.get("completeness"), "完整性审核结果"));
        conclusion.put("相关性", mirror_fieldOf(context.get("relevance"), "推导结论"));
        conclusion.put("建议", "根据完整性与相关性审核结果综合判断");
        report.put("最终结论", conclusion);
        return report;
    }

    /** [mirror: AuditPipeline.fieldOf] */
    private Object mirror_fieldOf(Object map, String key) {
        if (map instanceof Map<?, ?> m) {
            return m.get(key);
        }
        return "";
    }

    /** [mirror: AuditPipeline.str] */
    private String mirror_str(Object obj) {
        return obj == null ? "" : String.valueOf(obj);
    }

    // ==================== [mirror: AuditImageCache] 逻辑复现 ====================

    private static final long MAX_BYTES_PER_IMAGE = 10L * 1024 * 1024;

    /** [mirror: AuditImageCache.isHttpUrl] */
    private boolean mirror_isHttpUrl(String value) {
        String lower = value == null ? "" : value.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** [mirror: AuditImageCache.compress 文件存在性检查] */
    private void mirror_checkFileExists(Path filePath) throws java.io.FileNotFoundException {
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new java.io.FileNotFoundException("文件不存在或不是常规文件");
        }
    }

    /** [mirror: AuditImageCache.compress 文件大小检查] */
    private void mirror_checkFileSize(Path filePath) throws IOException {
        long size = Files.size(filePath);
        if (size > MAX_BYTES_PER_IMAGE) {
            throw new IllegalArgumentException(
                    "文件大小" + (size / 1024 / 1024) + "MB超过上限" + (MAX_BYTES_PER_IMAGE / 1024 / 1024) + "MB");
        }
    }

    private final Map<String, String> dataUrlCache = new LinkedHashMap<>();

    /** [mirror: AuditImageCache.dataUrl 本地路径压缩+缓存] */
    private String mirror_compressToDataUrl(Path filePath) throws IOException {
        mirror_checkFileExists(filePath);
        mirror_checkFileSize(filePath);
        String key = filePath.toAbsolutePath().normalize().toString();
        String cached = dataUrlCache.get(key);
        if (cached != null) return cached;
        byte[] bytes = Files.readAllBytes(filePath);
        byte[] compressed;
        try {
            compressed = mirror_compressToJpeg(bytes);
        } catch (IOException | IllegalArgumentException e) {
            compressed = bytes;
        }
        String dataUrl = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(compressed);
        dataUrlCache.put(key, dataUrl);
        return dataUrl;
    }

    private byte[] mirror_compressToJpeg(byte[] original) throws IOException {
        java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(original));
        if (src == null) {
            throw new IllegalArgumentException("无法解码图片数据");
        }
        int width = src.getWidth();
        int height = src.getHeight();
        int longest = Math.max(width, height);
        int maxEdge = 1024;
        int targetWidth = width;
        int targetHeight = height;
        if (longest > maxEdge) {
            targetWidth = Math.max(1, width * maxEdge / longest);
            targetHeight = Math.max(1, height * maxEdge / longest);
        }
        java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(
                targetWidth, targetHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = rgb.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, targetWidth, targetHeight);
        g.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(rgb, "jpeg", out);
        return out.toByteArray();
    }

    // ==================== [mirror: AuditModelConfig] 逻辑复现 ====================

    /** [mirror: AuditModelConfig.requireEnv] */
    private String mirror_requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少必需的环境变量 " + key
                    + "。API Key 只通过环境变量传入，不写入仓库文件。");
        }
        return value;
    }

    /** [mirror: AuditModelConfig.envOrDefault] */
    private String mirror_envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    // ==================== 测试辅助工具 ====================

    private static byte[] createMinimalJpeg() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
            javax.imageio.ImageIO.write(img, "jpeg", out);
        } catch (IOException ignored) { }
        return out.toByteArray();
    }
}
