package com.huawei.ascend.sit.fixtures.task_degradation_gate.deploy;

import com.huawei.ascend.sit.judge.AcceptanceExtension;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * FEAT-112 public-surface acceptance class: integration layer (owned DA process, model replaced by a stub), one
 * DA at a time on this machine, every test a recorded scenario execution.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Tag("integration")
@Tag("feat-112")
@ResourceLock("feat112-da")
@ExtendWith(AcceptanceExtension.class)
public @interface Feat112Integration {
}
