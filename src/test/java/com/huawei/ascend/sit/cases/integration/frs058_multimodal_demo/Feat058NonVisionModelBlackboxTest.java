/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo;

import com.huawei.ascend.sit.base.BaseManagedStackTest;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.SseTurn;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.multipart;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.parseSse;
import static com.huawei.ascend.sit.cases.integration.frs058_multimodal_demo.Feat058WireSupport.postUrl;

/**
 * FRS058-11 专属栈：SUT 注入纯文本模型（非识图）驱动识图链路。
 *
 * <p>2026-11-12 归属整改：FRS058-11 原经 multi-modal-tools-demo 的
 * {@code MultimodalToolsAgent} 进程内驱动断言——但 FRS-058 装配面 SUT
 * （multi-modal-FAQ-agent）的识图为自有实现（MultimodalInputRail + FaqPocLlmProperties），
 * 不依赖 demo 链。特性档 7.3 要测的对象是"Demo 装配/切换到无视觉能力的模型"后的
 * SUT 行为，故本类以独立 managed stack 拉起 faq-poc-agent，VISION_MODEL 覆盖为
 * 纯文本型号（默认 glm-5.2——ark coding 网关实测对 image_url 显式拒绝），黑盒断言
 * SUT 的不兼容失败表面。原 demo 面断言归还 FEAT-032 测试域。</p>
 */
@Tag("integration")
@Tag("frs-058")
@Feature("FRS-058: 多模态能力展示智能体Demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Feat058NonVisionModelBlackboxTest extends BaseManagedStackTest {

    private static final String SUT = "faq-poc-agent";

    /** 与 Demo 主类一致的 C-04 深链补全基准（本类断言面不涉及深链，仅装配一致）。 */
    private static final String APP_BASE_URL = "http://127.0.0.1:39001";

    private Feat058WireSupport.FaqStub faq;

    @Override
    protected SutStack.Builder buildStack(TestConfig config) {
        try {
            faq = new Feat058WireSupport.FaqStub();
        } catch (IOException e) {
            throw new IllegalStateException("FRS-058 FAQ stub 启动失败", e);
        }
        String visionKey = firstNonBlank(System.getenv("VISION_API_KEY"),
                System.getenv("LLM_API_KEY"));
        // 依赖门禁与 Demo 主类同口径：凭据缺失按 dependency-gated SKIPPED 收敛
        if (visionKey == null || visionKey.isBlank()) {
            throw new org.opentest4j.TestAbortedException(
                    "FRS-058 dependency-gated: VISION_API_KEY/LLM_API_KEY 未注入（C-01 视觉模型凭据）");
        }
        // 纯文本型号可经环境变量覆盖（ark coding 网关默认取 glm-5.2——对 image_url 显式拒绝）
        String nonVisionModel = firstNonBlank(System.getenv("FRS058_NON_VISION_MODEL"), "glm-5.2");
        return SutStack.builder(config)
                .streaming(false)
                .agent(SUT, a -> a
                        .env("FAQ_BASE_URL", faq.a2aUrl())
                        .env("APP_BASE_URL", APP_BASE_URL)
                        .env("OPENJIUWEN_SERVICE_A2A_REMOTEAGENTS_0_NAME", "faq-workflow")
                        .env("OPENJIUWEN_SERVICE_A2A_REMOTEAGENTS_0_URL", faq.a2aUrl())
                        .env("OPENJIUWEN_SERVICE_A2A_REMOTEAGENTS_0_STREAMING", "false")
                        // C-01 覆盖：注入纯文本模型（非识图），其余三件套与 Demo 主类一致
                        .env("VISION_API_KEY", visionKey)
                        .env("VISION_BASE_URL", firstNonBlank(System.getenv("VISION_BASE_URL"),
                                "https://ark.cn-beijing.volces.com/api/coding/v3"))
                        .env("VISION_MODEL", nonVisionModel)
                        .env("VISION_SSL_VERIFY", "false"));
    }

    @AfterAll
    void tearDownStub() {
        if (faq != null) {
            faq.close();
        }
    }

    // ------------------------------------------------------------------
    // FRS058-11 模型不具备视觉能力（FR-08/AC-06，用例 7.3）
    // ------------------------------------------------------------------
    @Test
    @Tag("story-frs058-11")
    @Story("FRS-058.FRS058-11: 无视觉模型收到图文输入必须显式报错，静默丢图/降级即缺陷")
    @DisplayName("FRS058-11 SUT 配纯文本模型时图文输入返回显式不兼容失败")
    void frs05811NonVisionModelExplicitIncompatibility() throws Exception {
        Map.Entry<String, byte[]> body = multipart(
                List.of(new Feat058WireSupport.FileField("screenshot",
                        "error-page.png", "image/png", anomalySampleBytes())),
                List.<String[]>of(new String[]{"message", "转账时报错怎么办"}));
        HttpResponse<String> response = postUrl(multipartUrl(), body.getKey(), body.getValue());
        assertThat(response.statusCode())
                .as("图文提交应被入口受理（HTTP 200，失败发生在识图链路而非入口）").isEqualTo(200);
        SseTurn turn = parseSse(response.body());
        boolean explicitFailure = !turn.completed() || turn.errorCode() != null
                || (turn.errorMessage() != null && !turn.errorMessage().isBlank());
        assertThat(explicitFailure)
                .as("纯文本模型收到图文输入必须显式报错（FR-08/AC-06：不静默丢图、不静默降级；"
                        + "实际 final=%s error=%s/%s answer=%s）",
                        turn.finalState(), turn.errorCode(), turn.errorMessage(),
                        abbreviate(turn.fullText()))
                .isTrue();
        assertThat(faq.totalRequests())
                .as("识图链路失败不得进入 FAQ 委托（零出站）").isZero();
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private String multipartUrl() {
        return client(SUT).getBaseUrl() + "/v1/projects/p-frs058/conversations/conv-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }

    private static String firstNonBlank(String left, String right) {
        return left != null && !left.isBlank() ? left : right;
    }

    /** 异常页面样张（含唯一错误码 TX-9527，与 Demo 主类同款生成逻辑）。 */
    private static byte[] anomalySampleBytes() {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                480, 240, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 480, 240);
        g.setColor(new java.awt.Color(0xB4, 0x1B, 0x1B));
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 22));
        g.drawString("手机银行-转账页面异常", 24, 56);
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 18));
        g.drawString("错误码: TX-9527", 24, 116);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
