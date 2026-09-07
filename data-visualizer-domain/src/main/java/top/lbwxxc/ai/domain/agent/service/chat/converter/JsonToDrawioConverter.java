package top.lbwxxc.ai.domain.agent.service.chat.converter;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * drawio_graph JSON DSL → Draw.io XML 转换器（TASK-004）。
 * <p>
 * 编排顺序：解析校验 → 布局 → 样式 → 生成 mxGraphModel XML。
 * 转换器为无状态纯函数组件；任何环节失败返回 null，由调用方（parseChatResponse）
 * 回退旧解析路径（drawio_done XML / 裸 XML / user 文本），不向用户请求抛出异常。
 */
@Slf4j
public final class JsonToDrawioConverter {

    private JsonToDrawioConverter() {
    }

    /**
     * 尝试把 drawio_graph JSON 转换为可直接渲染的 mxGraphModel XML。
     *
     * @param rawJson LLM 输出的原始 JSON 字符串
     * @return 合法 XML；解析失败、类型不匹配或无有效节点时返回 null
     */
    public static String tryConvert(String rawJson) {
        try {
            DrawioGraphDslVO graph = DiagramDslParser.parse(rawJson);
            if (graph == null || graph.getNodes().isEmpty()) {
                return null;
            }
            Map<String, SimpleGridLayout.Rect> positions = SimpleGridLayout.layout(graph);
            return buildXml(graph, positions);
        } catch (Exception e) {
            log.warn("drawio_graph 转换失败，回退旧解析路径: {}", e.getMessage());
            return null;
        }
    }

    private static String buildXml(DrawioGraphDslVO graph, Map<String, SimpleGridLayout.Rect> positions) {
        // DSL id → 生成 mxCell id：节点从 2 起唯一递增（0/1 为 drawio 根节点），连线为 e1、e2...
        Map<String, String> generatedIds = new HashMap<>();

        StringBuilder xml = new StringBuilder();
        xml.append("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>");

        int nextNodeId = 2;
        for (DrawioGraphDslVO.Node node : graph.getNodes()) {
            String cellId = String.valueOf(nextNodeId++);
            generatedIds.put(node.getId(), cellId);

            StyleTemplates.NodeTemplate template = StyleTemplates.nodeTemplate(node.getKind());
            SimpleGridLayout.Rect rect = positions.get(node.getId());
            int x = rect != null ? rect.x : SimpleGridLayout.START_X;
            int y = rect != null ? rect.y : SimpleGridLayout.START_Y;

            xml.append("<mxCell id=\"").append(cellId)
                    .append("\" value=\"").append(escapeXml(node.getLabel()))
                    .append("\" style=\"").append(template.style)
                    .append("\" vertex=\"1\" parent=\"1\"><mxGeometry x=\"").append(x)
                    .append("\" y=\"").append(y)
                    .append("\" width=\"").append(template.width)
                    .append("\" height=\"").append(template.height)
                    .append("\" as=\"geometry\"/></mxCell>");
        }

        int edgeIndex = 1;
        for (DrawioGraphDslVO.Edge edge : graph.getEdges()) {
            String sourceId = generatedIds.get(edge.getSource());
            String targetId = generatedIds.get(edge.getTarget());
            if (sourceId == null || targetId == null) {
                // 解析阶段已过滤悬空引用，此处为防御性兜底
                continue;
            }
            xml.append("<mxCell id=\"e").append(edgeIndex++)
                    .append("\" value=\"").append(escapeXml(edge.getLabel()))
                    .append("\" style=\"").append(StyleTemplates.edgeStyle(edge.getKind()))
                    .append("\" edge=\"1\" parent=\"1\" source=\"").append(sourceId)
                    .append("\" target=\"").append(targetId)
                    .append("\"><mxGeometry relative=\"1\" as=\"geometry\"/></mxCell>");
        }

        xml.append("</root></mxGraphModel>");
        return xml.toString();
    }

    /** XML 属性值转义：label 中的引号、尖括号、换行等必须转义，否则生成非法 XML。 */
    private static String escapeXml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                case '\n' -> escaped.append("&#xa;");
                case '\r' -> escaped.append("");
                default -> escaped.append(current);
            }
        }
        return escaped.toString();
    }
}
