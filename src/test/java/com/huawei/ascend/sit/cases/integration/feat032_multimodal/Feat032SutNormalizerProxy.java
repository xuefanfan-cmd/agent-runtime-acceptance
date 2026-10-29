/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.cases.integration.feat032_multimodal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SUT 直驱代理：反射调用 agent-core-java multimodal 契约的 public API
 * （仅调用 L2 §4.1/§4.2 逐字记录的签名，制品缺失时 {@link #available()} 为 false）。
 *
 * <p>覆盖的 public 面（均取自 L2 §4 实码签名）：
 * <ul>
 *   <li>{@code MultimodalContentNormalizer.isStructuredContent(Object)} — static 判别</li>
 *   <li>{@code new DefaultMultimodalContentNormalizer().normalize(Object)} — 结构化输入规范化</li>
 *   <li>{@code ContentBlockType.wireValue() / fromWire(String)}</li>
 *   <li>{@code ContentBlock.id() / type() / source()}（经 {@link Feat032SutSupport#describeBlock}）</li>
 * </ul>
 *
 * <p>失败面约定（L2 §2.2）：结构非法时抛 BaseError（details.reason=CONTENT_INVALID，
 * 携带 blockId/field/index），不回退 String.valueOf——本代理将异常转译为
 * {@link SutContractViolation}，reason 经 {@link Feat032SutSupport#errorDetails} 提取。
 *
 * @since 0.1.2
 */
final class Feat032SutNormalizerProxy {

    /** SUT 抛出的结构化错误（reason 取自 BaseError.details.reason 或异常 message 兜底）。 */
    static final class SutContractViolation extends RuntimeException {
        private final String reason;
        private final transient Map<String, Object> details;

        SutContractViolation(String reason, Map<String, Object> details) {
            super(reason);
            this.reason = reason;
            this.details = details;
        }

        String reason() {
            return reason;
        }

        Map<String, Object> details() {
            return details;
        }
    }

    private final Object normalizer;

    private Feat032SutNormalizerProxy(Object normalizer) {
        this.normalizer = normalizer;
    }

    /** core 契约制品是否就绪。 */
    static boolean available() {
        return Feat032SutSupport.coreContractPresent();
    }

    /** 创建代理；制品未就绪返回 null（调用方负责门禁）。 */
    static Feat032SutNormalizerProxy create() {
        if (!available()) {
            return null;
        }
        Class<?> type = Feat032SutSupport.tryLoad(Feat032SutSupport.CLS_DEFAULT_NORMALIZER);
        Object instance = Feat032SutSupport.newInstance(type);
        return instance != null ? new Feat032SutNormalizerProxy(instance) : null;
    }

    /**
     * static 判别：MultimodalContentNormalizer 结构化输入判别。core 不同版本方法名存在
     * {@code isStructuredContent} / {@code isMultimodalContent} 两种口径，此处按序兼容，
     * 两者都不可用时返回 null（由调用方以「实际不得为 null」暴露契约破裂）。
     */
    Boolean isStructuredContent(Object input) {
        Class<?> type = Feat032SutSupport.tryLoad(Feat032SutSupport.CLS_NORMALIZER);
        if (type == null) {
            return null;
        }
        Object result = Feat032SutSupport.invokeStatic(type, "isStructuredContent",
                new Class<?>[]{Object.class}, new Object[]{input});
        if (!(result instanceof Boolean)) {
            result = Feat032SutSupport.invokeStatic(type, "isMultimodalContent",
                    new Class<?>[]{Object.class}, new Object[]{input});
        }
        return result instanceof Boolean b ? b : null;
    }

    /**
     * 规范化输入为有序内容块描述列表；结构非法时抛 {@link SutContractViolation}。
     */
    List<Map<String, Object>> normalize(Object input) {
        try {
            Method normalize = normalizer.getClass().getMethod("normalize", Object.class);
            Object blocks = normalize.invoke(normalizer, input);
            List<Map<String, Object>> described = new ArrayList<>();
            if (blocks instanceof List<?> list) {
                for (Object block : list) {
                    described.add(Feat032SutSupport.describeBlock(block));
                }
            }
            return described;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Map<String, Object> details = Feat032SutSupport.errorDetails(cause);
            Object reason = details.get("reason");
            throw new SutContractViolation(
                    reason != null ? reason.toString() : cause.getClass().getSimpleName(),
                    details);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SUT normalize 反射调用失败（API 形态与 L2 §4.1 不符）", e);
        }
    }

    /** ContentBlockType.fromWire(String) 的 wire 值往返验证；制品未就绪返回 null。 */
    String fromWire(String wireValue) {
        Class<?> type = Feat032SutSupport.tryLoad(Feat032SutSupport.CLS_CONTENT_BLOCK_TYPE);
        if (type == null) {
            return null;
        }
        Object optional = Feat032SutSupport.invokeStatic(type, "fromWire",
                new Class<?>[]{String.class}, new Object[]{wireValue});
        Object resolved = Feat032SutSupport.invokeNoArg(optional, "orElse");
        if (resolved == null) {
            // Optional.orElse 需要参数；改走 isPresent/get 反射
            try {
                Method get = optional.getClass().getMethod("get");
                resolved = get.invoke(optional);
            } catch (ReflectiveOperationException ignore) {
                return null;
            }
        }
        Object wire = Feat032SutSupport.invokeNoArg(resolved, "wireValue");
        return wire != null ? wire.toString() : null;
    }

    /** 兼容 Map 构造便捷方法（镜像外部结构化输入形态）。 */
    static Map<String, Object> textBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    static Map<String, Object> imageBlock(Map<String, Object> source) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image");
        block.put("source", source);
        return block;
    }

    static Map<String, Object> source(String kind, Map<String, Object> fields) {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("kind", kind);
        src.putAll(fields);
        return src;
    }

    /** 旧 OpenAI 风格 image_url 块。 */
    static Map<String, Object> legacyImageUrlBlock(String url) {
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", url);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image_url");
        block.put("image_url", imageUrl);
        return block;
    }
}