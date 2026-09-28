/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.openjiuwen.studio.dsl.fetch.store.ObjectStore;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RecordingObjectStore implements ObjectStore {
    private final Map<String, byte[]> objects;
    private final Map<String, Integer> reads = new LinkedHashMap<>();

    public RecordingObjectStore(Map<String, byte[]> objects) {
        this.objects = new LinkedHashMap<>(objects);
    }

    @Override
    public byte[] getObject(String objectKey) {
        reads.merge(objectKey, 1, Integer::sum);
        byte[] value = objects.get(objectKey);
        if (value == null) {
            throw new com.openjiuwen.studio.dsl.fetch.error.FetchException(
                    "FETCH_OBS_KEY_MISSING", "missing object: " + objectKey);
        }
        return value.clone();
    }

    public Map<String, Integer> reads() {
        return Map.copyOf(reads);
    }
}
