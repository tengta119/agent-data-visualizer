package top.lbwxxc.ai.domain.agent.service.chat.converter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

/**
 * drawio_graph JSON DSL 解析与校验器。
 * <p>
 * 校验规则：节点 id 必填且唯一（重复保留首个）；边 source/target 必须引用存在的节点 id（悬空跳过）；
 * label 为空时回退为节点 id；kind 为空或未知时不报错，由样式模板回退默认样式。
 * 解析失败（非法 JSON、type 不匹配、nodes 为空）返回 null，由调用方回退旧解析路径。
 */
@Slf4j
public final class DiagramDslParser {

    /** 内部协议类型，仅存在于 LLM 输出与后端解析之间，不透传到前端。 */
    public static final String TYPE_DRAWIO_GRAPH = "drawio_graph";

    private DiagramDslParser() {
    }

    /**
     * 解析并校验 drawio_graph JSON。
     *
     * @param rawJson LLM 输出的原始 JSON 字符串
     * @return 校验后的图形模型；不可解析或没有可用节点时返回 null
     */
    public static DrawioGraphDslVO parse(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return null;
        }

        JSONObject root;
        try {
            root = JSON.parseObject(rawJson);
        } catch (Exception e) {
            log.debug("drawio_graph JSON 解析失败: {}", e.getMessage());
            return null;
        }
        if (root == null) {
            return null;
        }
        if (!TYPE_DRAWIO_GRAPH.equalsIgnoreCase(trimToEmpty(root.getString("type")))) {
            return null;
        }

        // 兼容 nodes/edges 内嵌在 graph 字段下的形态
        JSONObject container = root;
        if (root.get("nodes") == null) {
            JSONObject nested = root.getJSONObject("graph");
            if (nested != null) {
                container = nested;
            }
        }

        JSONArray nodesArray = container.getJSONArray("nodes");
        if (nodesArray == null || nodesArray.isEmpty()) {
            log.warn("drawio_graph 缺少 nodes，放弃转换");
            return null;
        }

        DrawioGraphDslVO graph = new DrawioGraphDslVO();
        java.util.Set<String> nodeIds = new java.util.HashSet<>();
        for (int i = 0; i < nodesArray.size(); i++) {
            JSONObject nodeJson = asObject(nodesArray.get(i));
            if (nodeJson == null) {
                continue;
            }
            String id = trimToNull(nodeJson.getString("id"));
            if (id == null) {
                log.warn("drawio_graph 节点缺少 id，已跳过: index={}", i);
                continue;
            }
            if (!nodeIds.add(id)) {
                log.warn("drawio_graph 节点 id 重复，保留首个: id={}", id);
                continue;
            }
            String label = firstNonBlank(nodeJson.getString("label"), nodeJson.getString("name"));
            if (label == null) {
                label = id;
            }
            graph.getNodes().add(new DrawioGraphDslVO.Node(id, label, normalizeKind(nodeJson.getString("kind"))));
        }
        if (graph.getNodes().isEmpty()) {
            log.warn("drawio_graph 无有效节点，放弃转换");
            return null;
        }

        JSONArray edgesArray = container.getJSONArray("edges");
        if (edgesArray != null) {
            for (int i = 0; i < edgesArray.size(); i++) {
                JSONObject edgeJson = asObject(edgesArray.get(i));
                if (edgeJson == null) {
                    continue;
                }
                // 兼容 from/to 别名，降低模型输出格式漂移导致的失败率
                String source = firstNonBlank(edgeJson.getString("source"), edgeJson.getString("from"));
                String target = firstNonBlank(edgeJson.getString("target"), edgeJson.getString("to"));
                if (source == null || target == null) {
                    log.warn("drawio_graph 连线缺少 source/target，已跳过: index={}", i);
                    continue;
                }
                if (!nodeIds.contains(source) || !nodeIds.contains(target)) {
                    log.warn("drawio_graph 连线引用不存在的节点，已跳过: {} -> {}", source, target);
                    continue;
                }
                String label = trimToEmpty(edgeJson.getString("label"));
                graph.getEdges().add(new DrawioGraphDslVO.Edge(source, target, label,
                        normalizeKind(edgeJson.getString("kind"))));
            }
        }
        return graph;
    }

    private static JSONObject asObject(Object value) {
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        return null;
    }

    /** kind 统一小写并去除空白；空白视为未指定（null）。 */
    private static String normalizeKind(String kind) {
        String normalized = trimToEmpty(kind).toLowerCase();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return null;
    }
}
