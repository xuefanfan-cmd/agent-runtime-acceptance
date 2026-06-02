package com.huawei.ascend.sit.utils;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Loads externalized test data from {@code src/test/resources/testdata/}.
 *
 * <p>Supports JSON test data files for data-driven testing with
 * {@code @ParameterizedTest}. Data files are organized to mirror the
 * {@code cases/} directory structure.</p>
 *
 * <h3>Usage example:</h3>
 * <pre>{@code
 * List<Map<String, Object>> cases = TestDataLoader.loadList("component/service/create-run-invalid-inputs.json");
 * }</pre>
 */
public final class TestDataLoader {

    private TestDataLoader() {}

    private static final String TESTDATA_ROOT = "testdata/";
    private static final ObjectMapper MAPPER = JsonUtils.mapper();

    /**
     * Load a JSON file as a typed object.
     *
     * @param relativePath path relative to testdata/ (e.g. "component/service/create-run-invalid-inputs.json")
     * @param type         target type
     * @param <T>          the return type
     * @return deserialized object
     */
    public static <T> T load(String relativePath, Class<T> type) {
        String resourcePath = TESTDATA_ROOT + relativePath;
        try (InputStream is = TestDataLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Test data file not found: " + resourcePath);
            }
            return MAPPER.readValue(is, type);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load test data: " + resourcePath, e);
        }
    }

    /**
     * Load a JSON file as a List of Maps (for parameterized test cases).
     *
     * @param relativePath path relative to testdata/
     * @return list of test case maps
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> loadList(String relativePath) {
        String resourcePath = TESTDATA_ROOT + relativePath;
        try (InputStream is = TestDataLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Test data file not found: " + resourcePath);
            }
            return MAPPER.readValue(is, List.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load test data: " + resourcePath, e);
        }
    }

    /**
     * Load a JSON file as a string (raw content for direct payload use).
     *
     * @param relativePath path relative to testdata/
     * @return raw JSON string
     */
    public static String loadRaw(String relativePath) {
        String resourcePath = TESTDATA_ROOT + relativePath;
        try (InputStream is = TestDataLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Test data file not found: " + resourcePath);
            }
            return new String(is.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load test data: " + resourcePath, e);
        }
    }
}
