package com.huawei.ascend.sit.base;

import com.huawei.ascend.sit.client.A2aServiceClient;
import com.huawei.ascend.sit.config.TestConfig;
import com.huawei.ascend.sit.lifecycle.SutStack;
import io.qameta.allure.Allure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Base class for tests that bring up their own managed SUT stack.
 *
 * <p>Here the test <em>owns</em> the SUT lifecycle at
 * {@code @BeforeAll}/{@code @AfterAll} granularity: a subclass declares which
 * agents it needs via {@link #buildStack(TestConfig)}, the base launches them
 * once per class and tears them down afterwards.
 *
 * <p>Uses {@link TestInstance.Lifecycle#PER_CLASS} so the lifecycle methods can
 * dispatch to the subclass-provided {@link #buildStack} — a single instance is
 * reused for all tests in the class, so the stack is brought up exactly once.
 * Subclasses obtain clients through {@link #client(String)} bound to the stack's
 * resolved ports. For per-case isolation instantiate a {@link SutStack} directly
 * with try-with-resources inside the test method.
 *
 * @see SutStack
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(SessionLabelExtension.class)   // 每次 invocation 派生 wire-log 会话标签（@Inherited，子类全覆盖）
public abstract class BaseManagedStackTest {

    protected TestConfig config;
    protected SutStack stack;

    @BeforeAll
    void initManagedStack() {
        config = TestConfig.load();
        stack = buildStack(config).start();
    }

    /**
     * Record each test invocation's start time as a human-readable {@link Allure#parameter} so the Allure
     * report shows when each case ran — useful for correlating a case with its per-run wire-log directory
     * ({@code target/sit-logs/wire/run-<yyyyMMdd-HHmmss>/}) and for ordering fast back-to-back cases.
     * Fires per invocation (including each parameter-set of a {@code @ParameterizedTest}), matching the
     * "each execution" granularity.
     */
    @BeforeEach
    void recordStartTimestamp() {
        String startTimestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
        Allure.parameter("startTimestamp", startTimestamp);
    }

    /**
     * Describe the SUT stack this class needs (which agents, which overrides).
     * The base calls {@link SutStack.Builder#start()} on the result.
     */
    protected abstract SutStack.Builder buildStack(TestConfig config);

    @AfterAll
    void tearDownStack() {
        if (stack != null) {
            stack.close();
        }
    }

    /** Convenience: an A2A client bound to a named agent's resolved base URL. */
    protected A2aServiceClient client(String name) {
        return stack.client(name);
    }

    protected TestConfig getConfig() {
        return config;
    }
}
