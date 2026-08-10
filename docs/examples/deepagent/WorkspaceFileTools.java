package com.openjiuwen.examples.deepagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;

/**
 * 受限工作区文件工具：只允许读写工作区根目录下的 .md 文件，
 * 对标准化路径做边界检查——模型不能通过这些工具访问工作区外的文件。
 * 与 DeepAgentConfig.restrictToWorkDir 配合，构成「可写范围」双保险。
 */
public final class WorkspaceFileTools {

    private final Path root;

    private WorkspaceFileTools(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** 创建工作区目录并返回三个受限文件工具（读 / 写 / 列）。 */
    public static List<Object> create(Path workspaceRoot) {
        WorkspaceFileTools workspace = new WorkspaceFileTools(workspaceRoot);
        workspace.ensureRoot();
        return List.of(workspace.readFileTool(), workspace.writeFileTool(), workspace.listFilesTool());
    }

    private Tool readFileTool() {
        ToolCard card = ToolCard.builder()
                .id("workspace_read_file").name("read_file")
                .description("Read one Markdown file from the workspace before updating it.")
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of("file_path", Map.of(
                                "type", "string",
                                "description", "Workspace-relative .md file name")),
                        "required", List.of("file_path")))
                .build();
        return new LocalFunction(card, inputs -> readFile(String.valueOf(inputs.get("file_path"))));
    }

    private Tool writeFileTool() {
        ToolCard card = ToolCard.builder()
                .id("workspace_write_file").name("write_file")
                .description("Create or replace one Markdown file in the workspace.")
                .inputParams(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "file_path", Map.of(
                                        "type", "string",
                                        "description", "Workspace-relative .md file name"),
                                "content", Map.of(
                                        "type", "string",
                                        "description", "Complete UTF-8 Markdown content")),
                        "required", List.of("file_path", "content")))
                .build();
        return new LocalFunction(card, inputs -> writeFile(
                String.valueOf(inputs.get("file_path")),
                String.valueOf(inputs.get("content"))));
    }

    private Tool listFilesTool() {
        ToolCard card = ToolCard.builder()
                .id("workspace_list_files").name("list_files")
                .description("List the Markdown files that currently exist in the workspace.")
                .inputParams(Map.of("type", "object", "properties", Map.of()))
                .build();
        return new LocalFunction(card, inputs -> listFiles());
    }

    private Map<String, Object> readFile(String relativePath) {
        try {
            Path target = resolveAllowed(relativePath);
            if (!Files.isRegularFile(target)) {
                return failure(relativePath + " does not exist");
            }
            return Map.of(
                    "success", true,
                    "file_path", relativePath,
                    "content", Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException ex) {
            return failure(ex.getMessage());
        }
    }

    private Map<String, Object> writeFile(String relativePath, String content) {
        try {
            Path target = resolveAllowed(relativePath);
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return Map.of(
                    "success", true,
                    "file_path", relativePath,
                    "absolute_path", target.toString());
        } catch (IOException | IllegalArgumentException ex) {
            return failure(ex.getMessage());
        }
    }

    private Map<String, Object> listFiles() {
        List<String> existing = new ArrayList<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .sorted()
                    .forEach(existing::add);
        } catch (IOException ex) {
            return failure(ex.getMessage());
        }
        return Map.of("success", true, "files", existing);
    }

    /** 仅允许工作区根目录下的 .md 文件；拒绝越界路径与其他类型。 */
    private Path resolveAllowed(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || !relativePath.endsWith(".md")) {
            throw new IllegalArgumentException("Only .md files are allowed: " + relativePath);
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || !target.getParent().equals(root)) {
            throw new IllegalArgumentException("Path escapes the workspace: " + relativePath);
        }
        return target;
    }

    private void ensureRoot() {
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create workspace: " + root, ex);
        }
    }

    private static Map<String, Object> failure(String message) {
        return Map.of("success", false, "error", message == null ? "unknown file error" : message);
    }
}
