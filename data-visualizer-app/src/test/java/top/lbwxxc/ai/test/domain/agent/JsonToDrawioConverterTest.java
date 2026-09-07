package top.lbwxxc.ai.test.domain.agent;

import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.domain.agent.service.chat.converter.DiagramDslParser;
import top.lbwxxc.ai.domain.agent.service.chat.converter.JsonToDrawioConverter;
import top.lbwxxc.ai.domain.agent.service.chat.converter.SimpleGridLayout;
import top.lbwxxc.ai.domain.agent.service.chat.converter.StyleTemplates;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-004：drawio_graph JSON DSL → Draw.io XML 转换器测试。
 * 覆盖解析校验、布局间距、样式映射、XML 转义与失败回退。
 */
class JsonToDrawioConverterTest {

    private static final Pattern DRAWIO_XML_PATTERN =
            Pattern.compile("(?s)(<mxfile[\\s\\S]*?</mxfile>|<mxGraphModel[\\s\\S]*?</mxGraphModel>)");

    private String dsl(String nodes, String edges) {
        return "{\"type\":\"drawio_graph\",\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}";
    }

    @Test
    void validGraphConvertsToWellFormedXml() {
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"前端\",\"kind\":\"client\"},{\"id\":\"n2\",\"label\":\"订单服务\",\"kind\":\"service\"},{\"id\":\"n3\",\"label\":\"MySQL\",\"kind\":\"database\"}",
                "{\"source\":\"n1\",\"target\":\"n2\",\"label\":\"HTTPS\"},{\"source\":\"n2\",\"target\":\"n3\",\"kind\":\"data\"}"));

        assertNotNull(xml);
        assertTrue(xml.startsWith("<mxGraphModel>"));
        assertTrue(xml.endsWith("</mxGraphModel>"));
        // 结构完整：root、0/1 根节点、3 个 vertex、2 个 edge
        assertTrue(xml.contains("<mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"));
        assertEquals(4, countOccurrences(xml, "vertex=\"1\""));
        assertEquals(2, countOccurrences(xml, "edge=\"1\""));
        // 可被现有正则提取（前端 extractDrawIoXml 同源逻辑）
        Matcher matcher = DRAWIO_XML_PATTERN.matcher(xml);
        assertTrue(matcher.find());
        assertEquals(xml, matcher.group(1));
    }

    @Test
    void nodeIdsAreUniqueAndIncreasing() {
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"a\",\"label\":\"A\",\"kind\":\"process\"},{\"id\":\"b\",\"label\":\"B\",\"kind\":\"process\"}",
                ""));
        assertNotNull(xml);
        assertTrue(xml.contains("id=\"2\""));
        assertTrue(xml.contains("id=\"3\""));
        assertFalse(xml.contains("id=\"4\""));
    }

    @Test
    void edgeEndpointsRemappedToGeneratedIds() {
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"A\",\"kind\":\"process\"},{\"id\":\"n2\",\"label\":\"B\",\"kind\":\"process\"}",
                "{\"source\":\"n1\",\"target\":\"n2\"}"));
        assertNotNull(xml);
        // 边的 source/target 必须指向生成的 mxCell id（2、3），而不是 DSL 原始 id
        assertTrue(xml.contains("source=\"2\" target=\"3\""));
        assertFalse(xml.contains("source=\"n1\""));
    }

    @Test
    void knownKindsMapToDedicatedStyles() {
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"db\",\"kind\":\"database\"},"
                        + "{\"id\":\"n2\",\"label\":\"mq\",\"kind\":\"queue\"},"
                        + "{\"id\":\"n3\",\"label\":\"判断\",\"kind\":\"decision\"},"
                        + "{\"id\":\"n4\",\"label\":\"用户\",\"kind\":\"actor\"},"
                        + "{\"id\":\"n5\",\"label\":\"用例\",\"kind\":\"usecase\"}",
                ""));
        assertNotNull(xml);
        assertTrue(xml.contains("shape=cylinder3"));
        assertTrue(xml.contains("shape=process"));
        assertTrue(xml.contains("rhombus"));
        assertTrue(xml.contains("shape=umlActor"));
        assertTrue(xml.contains("ellipse"));
    }

    @Test
    void unknownKindFallsBackToDefaultStyle() {
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"未知类型\",\"kind\":\"something_unknown\"},{\"id\":\"n2\",\"label\":\"无类型\"}",
                ""));
        assertNotNull(xml);
        assertEquals(2, countOccurrences(xml, StyleTemplates.nodeTemplate(null).style));
    }

    @Test
    void edgeKindStylesDiffer() {
        String defaultXml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"A\",\"kind\":\"process\"},{\"id\":\"n2\",\"label\":\"B\",\"kind\":\"process\"}",
                "{\"source\":\"n1\",\"target\":\"n2\"}"));
        String asyncXml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"A\",\"kind\":\"process\"},{\"id\":\"n2\",\"label\":\"B\",\"kind\":\"process\"}",
                "{\"source\":\"n1\",\"target\":\"n2\",\"kind\":\"async\"}"));
        String dataXml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"A\",\"kind\":\"process\"},{\"id\":\"n2\",\"label\":\"B\",\"kind\":\"process\"}",
                "{\"source\":\"n1\",\"target\":\"n2\",\"kind\":\"data\"}"));

        assertNotNull(defaultXml);
        assertNotNull(asyncXml);
        assertNotNull(dataXml);
        assertFalse(defaultXml.contains("dashed=1"));
        assertTrue(asyncXml.contains("dashed=1"));
        assertTrue(asyncXml.contains("#9673a6"));
        assertTrue(dataXml.contains("#d79b00"));
    }

    @Test
    void danglingEdgeIsSkipped() {
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"A\",\"kind\":\"process\"},{\"id\":\"n2\",\"label\":\"B\",\"kind\":\"process\"}",
                "{\"source\":\"n1\",\"target\":\"n2\"},{\"source\":\"n1\",\"target\":\"ghost\"},{\"source\":\"ghost\",\"target\":\"n2\"}"));
        assertNotNull(xml);
        assertEquals(1, countOccurrences(xml, "edge=\"1\""));
        // 其余节点正常渲染
        assertEquals(2, countOccurrences(xml, "vertex=\"1\""));
    }

    @Test
    void duplicateNodeIdKeepsFirst() {
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"第一个\",\"kind\":\"process\"},{\"id\":\"n1\",\"label\":\"第二个\",\"kind\":\"database\"}",
                ""));
        assertNotNull(xml);
        assertEquals(1, countOccurrences(xml, "vertex=\"1\""));
        assertTrue(xml.contains("第一个"));
        assertFalse(xml.contains("第二个"));
    }

    @Test
    void blankLabelFallsBackToId() {
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"\",\"kind\":\"process\"},{\"id\":\"n2\",\"kind\":\"process\"}",
                ""));
        assertNotNull(xml);
        assertTrue(xml.contains("value=\"n1\""));
        assertTrue(xml.contains("value=\"n2\""));
    }

    @Test
    void specialCharactersAreEscaped() {
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"a<b>&\\\"c\\\"'d\\ne\",\"kind\":\"process\"}",
                "{\"source\":\"n1\",\"target\":\"n1\",\"label\":\"x<y>&z\"}"));
        assertNotNull(xml);
        // 属性值中不得出现未转义的特殊字符
        assertFalse(xml.contains("value=\"a<b>"));
        assertTrue(xml.contains("&lt;b&gt;"));
        assertTrue(xml.contains("&amp;"));
        assertTrue(xml.contains("&quot;"));
        assertTrue(xml.contains("&apos;"));
        assertTrue(xml.contains("&#xa;"));
        assertTrue(xml.contains("&lt;y&gt;"));
    }

    @Test
    void layoutGuaranteesSpacingAndNoOverlap() {
        // 3 层 × 每层 4 节点，共 12 节点
        StringBuilder nodes = new StringBuilder();
        StringBuilder edges = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            int layer = i / 4;
            if (nodes.length() > 0) {
                nodes.append(",");
            }
            nodes.append("{\"id\":\"n").append(i).append("\",\"label\":\"节点").append(i).append("\",\"kind\":\"").append(i % 2 == 0 ? "process" : "database").append("\"}");
            if (layer > 0) {
                if (edges.length() > 0) {
                    edges.append(",");
                }
                edges.append("{\"source\":\"n").append((layer - 1) * 4 + (i % 4)).append("\",\"target\":\"n").append(i).append("\"}");
            }
        }
        String xml = JsonToDrawioConverter.tryConvert(dsl(nodes.toString(), edges.toString()));
        assertNotNull(xml);

        // 从 XML 中回读所有节点矩形，验证间距与无重叠
        Pattern cellPattern = Pattern.compile("<mxCell id=\"(\\d+)\" value=\"[^\"]*\" style=\"[^\"]*\" vertex=\"1\" parent=\"1\"><mxGeometry x=\"(\\d+)\" y=\"(\\d+)\" width=\"(\\d+)\" height=\"(\\d+)\"");
        java.util.List<int[]> rects = new java.util.ArrayList<>();
        Matcher matcher = cellPattern.matcher(xml);
        while (matcher.find()) {
            int x = Integer.parseInt(matcher.group(2));
            int y = Integer.parseInt(matcher.group(3));
            int w = Integer.parseInt(matcher.group(4));
            int h = Integer.parseInt(matcher.group(5));
            rects.add(new int[]{x, y, w, h});
        }
        assertEquals(12, rects.size());

        // 无重叠
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                assertTrue(!overlaps(rects.get(i), rects.get(j)), "节点 " + i + " 与 " + j + " 重叠");
            }
        }
        // 起始坐标
        assertTrue(rects.stream().anyMatch(r -> r[0] == SimpleGridLayout.START_X && r[1] == SimpleGridLayout.START_Y));
    }

    @Test
    void sameLayerNodesKeepHorizontalGap() {
        var graph = DiagramDslParser.parse(dsl(
                "{\"id\":\"a\",\"label\":\"A\",\"kind\":\"process\"},{\"id\":\"b\",\"label\":\"B\",\"kind\":\"process\"},{\"id\":\"c\",\"label\":\"C\",\"kind\":\"process\"},{\"id\":\"d\",\"label\":\"D\",\"kind\":\"process\"}",
                ""));
        assertNotNull(graph);
        Map<String, SimpleGridLayout.Rect> positions = SimpleGridLayout.layout(graph);
        // 同层（无入边，全部第 0 层）相邻节点横向间距 ≥150
        List<Integer> xs = List.of(positions.get("a").x, positions.get("b").x, positions.get("c").x, positions.get("d").x);
        for (int i = 1; i < xs.size(); i++) {
            assertTrue(xs.get(i) - xs.get(i - 1) >= SimpleGridLayout.HORIZONTAL_GAP,
                    "横向间距不足: " + (xs.get(i) - xs.get(i - 1)));
        }
    }

    @Test
    void adjacentLayersKeepVerticalGap() {
        var graph = DiagramDslParser.parse(dsl(
                "{\"id\":\"a\",\"label\":\"A\",\"kind\":\"process\"},{\"id\":\"b\",\"label\":\"B\",\"kind\":\"process\"},{\"id\":\"c\",\"label\":\"C\",\"kind\":\"process\"}",
                "{\"source\":\"a\",\"target\":\"b\"},{\"source\":\"b\",\"target\":\"c\"}"));
        assertNotNull(graph);
        Map<String, SimpleGridLayout.Rect> positions = SimpleGridLayout.layout(graph);
        SimpleGridLayout.Rect a = positions.get("a");
        SimpleGridLayout.Rect b = positions.get("b");
        SimpleGridLayout.Rect c = positions.get("c");
        // a → b → c 分三层，相邻层纵向间距 ≥120
        assertTrue(b.y - (a.y + a.height) >= SimpleGridLayout.VERTICAL_GAP);
        assertTrue(c.y - (b.y + b.height) >= SimpleGridLayout.VERTICAL_GAP);
    }

    @Test
    void cycleFallsBackToSequentialGrid() {
        // 环：a → b → c → a，无法拓扑分层，回退顺序网格；仍需生成合法 XML 且无重叠
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"a\",\"label\":\"A\",\"kind\":\"process\"},{\"id\":\"b\",\"label\":\"B\",\"kind\":\"process\"},{\"id\":\"c\",\"label\":\"C\",\"kind\":\"process\"},{\"id\":\"d\",\"label\":\"D\",\"kind\":\"process\"},{\"id\":\"e\",\"label\":\"E\",\"kind\":\"process\"}",
                "{\"source\":\"a\",\"target\":\"b\"},{\"source\":\"b\",\"target\":\"c\"},{\"source\":\"c\",\"target\":\"a\"}"));
        assertNotNull(xml);
        assertEquals(5, countOccurrences(xml, "vertex=\"1\""));
        assertEquals(3, countOccurrences(xml, "edge=\"1\""));
    }

    @Test
    void graphWithOnlyEdgesMissingNodesReturnsNull() {
        assertNull(JsonToDrawioConverter.tryConvert("{\"type\":\"drawio_graph\",\"nodes\":[],\"edges\":[]}"));
    }

    @Test
    void invalidInputsReturnNull() {
        assertNull(JsonToDrawioConverter.tryConvert(null));
        assertNull(JsonToDrawioConverter.tryConvert("   "));
        assertNull(JsonToDrawioConverter.tryConvert("这不是 JSON"));
        // type 不匹配
        assertNull(JsonToDrawioConverter.tryConvert("{\"type\":\"user\",\"content\":\"你好\"}"));
        // JSON 数组等非对象形态
        assertNull(JsonToDrawioConverter.tryConvert("[1,2,3]"));
    }

    @Test
    void allNodesMissingIdReturnsNull() {
        assertNull(JsonToDrawioConverter.tryConvert(
                "{\"type\":\"drawio_graph\",\"nodes\":[{\"label\":\"无id节点\"}],\"edges\":[]}"));
    }

    @Test
    void nestedGraphContainerIsSupported() {
        String xml = JsonToDrawioConverter.tryConvert(
                "{\"type\":\"drawio_graph\",\"graph\":{\"nodes\":[{\"id\":\"n1\",\"label\":\"A\",\"kind\":\"process\"}],\"edges\":[]}}");
        assertNotNull(xml);
        assertEquals(1, countOccurrences(xml, "vertex=\"1\""));
    }

    @Test
    void fromToAliasesAreSupported() {
        String xml = JsonToDrawioConverter.tryConvert(dsl(
                "{\"id\":\"n1\",\"label\":\"A\",\"kind\":\"process\"},{\"id\":\"n2\",\"label\":\"B\",\"kind\":\"process\"}",
                "{\"from\":\"n1\",\"to\":\"n2\"}"));
        assertNotNull(xml);
        assertEquals(1, countOccurrences(xml, "edge=\"1\""));
    }

    private boolean overlaps(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) != -1) {
            count++;
            index += token.length();
        }
        return count;
    }
}
