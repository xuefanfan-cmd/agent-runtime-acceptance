/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Captures java.util.logging messages for one named logger and restores its original level. */
public final class JulLogCaptureFixture implements AutoCloseable {
    private final Logger logger;
    private final Level originalLevel;
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record != null && isLoggable(record)) {
                messages.add(record.getLevel() + ": " + record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    public JulLogCaptureFixture(Class<?> owner) {
        logger = Logger.getLogger(owner.getName());
        originalLevel = logger.getLevel();
        handler.setLevel(Level.ALL);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    public List<String> messages() {
        return List.copyOf(messages);
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(originalLevel);
    }
}

