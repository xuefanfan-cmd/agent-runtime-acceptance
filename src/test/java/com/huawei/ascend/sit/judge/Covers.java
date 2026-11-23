package com.huawei.ascend.sit.judge;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The testplan scenarios one test execution carries and the acceptance layer it carries them on. Required on
 * every test run by {@link AcceptanceExtension}; the IDs are the connection key between testplan, code and
 * result records.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Covers {
    /** Full scenario IDs, for example {@code FEAT-112-D1-run}. */
    String[] value();

    AcceptanceLayer layer();
}
