package top.lbwxxc.ai.domain.agent.service.chat.converter;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Draw.io JSON DSL 模型（协议类型 drawio_graph）。
 * <p>
 * LLM 只输出图形语义（节点、连线、类型），不输出坐标、XML 与样式；
 * 坐标与样式由 {@link SimpleGridLayout} 与 {@link StyleTemplates} 确定性生成。
 */
@Data
public class DrawioGraphDslVO {

    /** 节点列表，至少一个节点才视为可转换图形。 */
    private List<Node> nodes = new ArrayList<>();

    /** 连线列表，source/target 必须引用存在的节点 id（解析阶段已过滤悬空引用）。 */
    private List<Edge> edges = new ArrayList<>();

    @Data
    public static class Node {

        /** DSL 中 LLM 给定的节点 id（原始字符串）。 */
        private final String id;

        /** 节点显示文本；解析阶段保证非空（为空时回退为 id）。 */
        private final String label;

        /** 节点语义类型（如 service/database/decision），null 或未知值由样式模板回退默认样式。 */
        private final String kind;
    }

    @Data
    public static class Edge {

        /** 起点节点的 DSL id。 */
        private final String source;

        /** 终点节点的 DSL id。 */
        private final String target;

        /** 连线标签，允许为空串。 */
        private final String label;

        /** 连线语义类型（如 async/data/inherit），null 或未知值由样式模板回退默认连线。 */
        private final String kind;
    }
}
