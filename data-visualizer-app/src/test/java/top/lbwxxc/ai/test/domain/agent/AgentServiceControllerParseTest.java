package top.lbwxxc.ai.test.domain.agent;

import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.api.dto.ChatResponseDTO;
import top.lbwxxc.ai.trigger.http.AgentServiceController;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TASK-004：parseChatResponse 新类型 drawio_graph 识别与旧类型回归测试。
 * 通过反射调用私有方法 parseChatResponse（其不依赖注入字段），固化新旧协议行为。
 */
class AgentServiceControllerParseTest {

    private ChatResponseDTO parse(String result, String fallback) throws Exception {
        AgentServiceController controller = new AgentServiceController();
        Method method = AgentServiceController.class.getDeclaredMethod("parseChatResponse", String.class, String.class);
        method.setAccessible(true);
        return (ChatResponseDTO) method.invoke(controller, result, fallback);
    }

    @Test
    void drawioGraphIsConvertedToXml() throws Exception {
        String dsl = "{\"type\":\"drawio_graph\",\"nodes\":[{\"id\":\"n1\",\"label\":\"前端\",\"kind\":\"client\"},{\"id\":\"n2\",\"label\":\"订单服务\",\"kind\":\"service\"}],\"edges\":[{\"source\":\"n1\",\"target\":\"n2\",\"kind\":\"call\",\"label\":\"HTTPS\"}]}";

        ChatResponseDTO response = parse(dsl, dsl);

        assertNotNull(response);
        assertEquals("drawio", response.getType());
        assertTrue(response.getContent().startsWith("<mxGraphModel>"));
        assertTrue(response.getContent().endsWith("</mxGraphModel>"));
    }

    @Test
    void drawioGraphWrappedInMarkdownFenceIsRecognized() throws Exception {
        String dsl = "{\"type\":\"drawio_graph\",\"nodes\":[{\"id\":\"n1\",\"label\":\"开始\",\"kind\":\"start\"}],\"edges\":[]}";
        String raw = "下面是图表结构：\n```json\n" + dsl + "\n```\n以上。";

        ChatResponseDTO response = parse(raw, raw);

        assertNotNull(response);
        assertEquals("drawio", response.getType());
        assertTrue(response.getContent().startsWith("<mxGraphModel>"));
    }

    @Test
    void drawioGraphWithLeadingAndTrailingTextIsRecognized() throws Exception {
        String dsl = "{\"type\":\"drawio_graph\",\"nodes\":[{\"id\":\"n1\",\"label\":\"A\",\"kind\":\"process\"}],\"edges\":[]}";
        String raw = "分析完成。" + dsl + "请查收。";

        ChatResponseDTO response = parse(raw, raw);

        assertNotNull(response);
        assertEquals("drawio", response.getType());
    }

    @Test
    void failedDrawioGraphFallsBackToUserContent() throws Exception {
        // nodes 为空 → 转换器返回 null → 无其他可识别 JSON/XML → 回退 user 原文
        String raw = "{\"type\":\"drawio_graph\",\"nodes\":[],\"edges\":[]}";

        ChatResponseDTO response = parse(raw, raw);

        assertNotNull(response);
        assertEquals("user", response.getType());
        assertEquals(raw, response.getContent());
    }

    @Test
    void lastJsonCandidateWinsBetweenUserAndGraph() throws Exception {
        // 与既有“最后一个 JSON 优先”语义一致：user 在后 → user；drawio_graph 在后 → drawio
        String user = "{\"type\":\"user\",\"content\":\"请补充信息\"}";
        String graph = "{\"type\":\"drawio_graph\",\"nodes\":[{\"id\":\"n1\",\"label\":\"A\",\"kind\":\"process\"}],\"edges\":[]}";

        ChatResponseDTO userWins = parse(user + "\n" + graph, user + "\n" + graph);
        assertNotNull(userWins);
        assertEquals("drawio", userWins.getType());

        ChatResponseDTO graphThenUser = parse(graph + "\n" + user, graph + "\n" + user);
        assertNotNull(graphThenUser);
        assertEquals("user", graphThenUser.getType());
        assertEquals("请补充信息", graphThenUser.getContent());
    }

    @Test
    void legacyDrawioDoneUnchanged() throws Exception {
        String xml = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>";
        String raw = "{\"type\":\"drawio_done\",\"content\":\"" + xml + "\"}";

        ChatResponseDTO response = parse(raw, raw);

        assertNotNull(response);
        assertEquals("drawio", response.getType());
        assertEquals(xml, response.getContent());
    }

    @Test
    void legacyBareXmlUnchanged() throws Exception {
        String xml = "<mxfile><diagram><mxGraphModel><root><mxCell id='0'/></root></mxGraphModel></diagram></mxfile>";

        ChatResponseDTO response = parse(xml, xml);

        assertNotNull(response);
        assertEquals("drawio", response.getType());
        assertEquals(xml, response.getContent());
    }

    @Test
    void legacyUserJsonUnchanged() throws Exception {
        String raw = "{\"type\":\"user\",\"content\":\"请补充关于数据库选型的具体信息\"}";

        ChatResponseDTO response = parse(raw, raw);

        assertNotNull(response);
        assertEquals("user", response.getType());
        assertEquals("请补充关于数据库选型的具体信息", response.getContent());
    }

    @Test
    void plainTextFallsBackToUserWithFallbackContent() throws Exception {
        ChatResponseDTO response = parse("普通文本结果", "fallback 全量内容");

        assertNotNull(response);
        assertEquals("user", response.getType());
        // 非 JSON/XML 输入使用 fallback 内容（同步路径为全量消息 join）
        assertEquals("fallback 全量内容", response.getContent());
    }
}
