package com.openjiuwen.example.studiodsl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SUT Agent entry point for FEAT-031 Studio DSL acceptance testing.
 * The workflow beans are assembled in {@link StudioDslDemoConfiguration}.
 *
 * @since 2026-08-27
 */
@SpringBootApplication
public class StudioDslDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(StudioDslDemoApplication.class, args);
    }
}
