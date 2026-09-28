/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.sit.fixtures.studio_dsl;

import com.openjiuwen.core.graph.Executable;
import com.openjiuwen.core.graph.PregelGraph;
import com.openjiuwen.core.workflow.BaseWorkflow;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowSpec;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.nodes.FlowBranchNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads an assembled workflow exclusively through public core and Studio DSL APIs. */
public final class WorkflowAssemblyProbe {
    private WorkflowAssemblyProbe() {
    }

    public static Snapshot inspect(Workflow workflow) {
        if (!(workflow.getInternalDrawable() instanceof BaseWorkflow base)) {
            throw new IllegalArgumentException("workflow does not expose a BaseWorkflow drawable");
        }
        Map<String, Executable<?, ?>> nodes = base.getGraph().getNodes();
        Map<String, String> javaTypes = new LinkedHashMap<>();
        Map<String, String> studioTypes = new LinkedHashMap<>();
        Map<String, Set<String>> branchTargets = new LinkedHashMap<>();
        nodes.forEach((nodeId, executable) -> {
            javaTypes.put(nodeId, executable.getClass().getName());
            if (executable instanceof AbstractStudioNode studioNode) {
                studioTypes.put(nodeId, studioNode.nodeType());
            }
            if (executable instanceof FlowBranchNode branchNode) {
                var drawableRouter = branchNode.router().getDrawableBranchRouter();
                branchTargets.put(nodeId, drawableRouter == null
                        ? Set.of()
                        : Set.copyOf(drawableRouter.getTargets()));
            }
        });

        WorkflowSpec spec = base.getConfig().getSpec();
        Set<String> endNodes = new LinkedHashSet<>();
        if (base.getGraph() instanceof PregelGraph graph) {
            nodes.keySet().forEach(nodeId -> {
                if (graph.getVertex(nodeId) != null && graph.getVertex(nodeId).isEndNode()) {
                    endNodes.add(nodeId);
                }
            });
        }
        return new Snapshot(
                Map.copyOf(javaTypes),
                Map.copyOf(studioTypes),
                copyEdges(spec.getEdges()),
                copyEdges(spec.getStreamEdges()),
                List.copyOf(spec.getStartNodes()),
                Set.copyOf(endNodes),
                Map.copyOf(branchTargets),
                nodes);
    }

    private static Map<String, List<String>> copyEdges(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((nodeId, targets) -> copy.put(nodeId, List.copyOf(targets)));
        return Map.copyOf(copy);
    }

    public record Snapshot(
            Map<String, String> javaTypes,
            Map<String, String> studioTypes,
            Map<String, List<String>> edges,
            Map<String, List<String>> streamEdges,
            List<String> startNodes,
            Set<String> endNodes,
            Map<String, Set<String>> branchTargets,
            Map<String, Executable<?, ?>> nodes) {
    }
}
