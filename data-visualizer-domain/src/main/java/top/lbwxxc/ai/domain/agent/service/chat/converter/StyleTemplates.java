package top.lbwxxc.ai.domain.agent.service.chat.converter;

import java.util.HashMap;
import java.util.Map;

/**
 * DSL kind 到 Draw.io 样式/尺寸的确定性映射表。
 * <p>
 * 样式字符串迁移自 agent/skills/drawio-*.md 中沉淀的样式规范（TASK-004 前），
 * LLM 不再输出 style；未知 kind 回退默认样式，不报错。
 */
public final class StyleTemplates {

    private StyleTemplates() {
    }

    /** 节点样式模板：style + 默认宽高。 */
    public static final class NodeTemplate {

        public final String style;
        public final int width;
        public final int height;

        private NodeTemplate(String style, int width, int height) {
            this.style = style;
            this.width = width;
            this.height = height;
        }
    }

    private static final NodeTemplate DEFAULT_NODE = new NodeTemplate(
            "rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;", 120, 60);

    private static final Map<String, NodeTemplate> NODE_TEMPLATES = new HashMap<>();

    static {
        // 流程图（样式来源：drawio-flowchart/SKILL.md）
        NODE_TEMPLATES.put("start", new NodeTemplate(
                "rounded=1;whiteSpace=wrap;html=1;arcSize=50;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;", 120, 60));
        NODE_TEMPLATES.put("end", new NodeTemplate(
                "rounded=1;whiteSpace=wrap;html=1;arcSize=50;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;", 120, 60));
        NODE_TEMPLATES.put("process", new NodeTemplate(
                "rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;", 120, 60));
        NODE_TEMPLATES.put("decision", new NodeTemplate(
                "rhombus;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;", 100, 80));
        NODE_TEMPLATES.put("data", new NodeTemplate(
                "shape=parallelogram;perimeter=parallelogramPerimeter;whiteSpace=wrap;html=1;fixedSize=1;fillColor=#e1d5e7;strokeColor=#9673a6;", 120, 60));
        NODE_TEMPLATES.put("document", new NodeTemplate(
                "shape=document;whiteSpace=wrap;html=1;boundedLbl=1;fillColor=#f8cecc;strokeColor=#b85450;", 120, 80));

        // 架构图（样式来源：drawio-architecture/SKILL.md）
        NODE_TEMPLATES.put("service", new NodeTemplate(
                "rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;shadow=1;fontStyle=1;", 120, 60));
        NODE_TEMPLATES.put("database", new NodeTemplate(
                "shape=cylinder3;whiteSpace=wrap;html=1;boundedLbl=1;backgroundOutline=1;size=15;fillColor=#ffe6cc;strokeColor=#d79b00;shadow=1;", 80, 80));
        NODE_TEMPLATES.put("queue", new NodeTemplate(
                "shape=process;whiteSpace=wrap;html=1;backgroundOutline=1;fillColor=#e1d5e7;strokeColor=#9673a6;", 120, 60));
        NODE_TEMPLATES.put("client", new NodeTemplate(
                "shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;fillColor=#f8cecc;strokeColor=#b85450;", 40, 80));
        // v1 不支持容器嵌套，boundary 退化为虚线区域框
        NODE_TEMPLATES.put("boundary", new NodeTemplate(
                "rounded=1;dashed=1;whiteSpace=wrap;html=1;fillColor=#f5f5f5;fontColor=#333333;strokeColor=#666666;", 240, 80));

        // UML（样式来源：drawio-uml/SKILL.md 简化方案）
        NODE_TEMPLATES.put("class", new NodeTemplate(
                "rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=5;", 160, 100));
        NODE_TEMPLATES.put("interface", new NodeTemplate(
                "rounded=0;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;align=left;verticalAlign=top;spacing=5;", 160, 100));
        NODE_TEMPLATES.put("actor", new NodeTemplate(
                "shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;fillColor=#f8cecc;strokeColor=#b85450;", 40, 80));
        NODE_TEMPLATES.put("usecase", new NodeTemplate(
                "ellipse;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;", 140, 70));
    }

    private static final String DEFAULT_EDGE_STYLE =
            "edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;endArrow=classic;";

    private static final Map<String, String> EDGE_STYLES = new HashMap<>();

    static {
        // 架构图连线（来源：drawio-architecture/SKILL.md）
        EDGE_STYLES.put("default", DEFAULT_EDGE_STYLE);
        EDGE_STYLES.put("call", "endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;strokeWidth=2;strokeColor=#666666;");
        EDGE_STYLES.put("async", "endArrow=classic;html=1;dashed=1;edgeStyle=orthogonalEdgeStyle;strokeColor=#9673a6;");
        EDGE_STYLES.put("data", "endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;strokeColor=#d79b00;");

        // UML 连线（来源：drawio-uml/SKILL.md）
        EDGE_STYLES.put("inherit", "endArrow=block;html=1;endFill=0;edgeStyle=orthogonalEdgeStyle;");
        EDGE_STYLES.put("implement", "endArrow=block;dashed=1;html=1;endFill=0;edgeStyle=orthogonalEdgeStyle;");
        EDGE_STYLES.put("associate", "endArrow=none;html=1;edgeStyle=orthogonalEdgeStyle;");
        EDGE_STYLES.put("aggregate", "endArrow=diamondThin;endFill=0;html=1;edgeStyle=orthogonalEdgeStyle;");
        EDGE_STYLES.put("compose", "endArrow=diamondThin;endFill=1;html=1;edgeStyle=orthogonalEdgeStyle;");
        EDGE_STYLES.put("depend", "endArrow=open;dashed=1;html=1;endSize=8;edgeStyle=orthogonalEdgeStyle;");
    }

    /** 获取节点样式模板；kind 为空或未知时返回默认矩形。 */
    public static NodeTemplate nodeTemplate(String kind) {
        if (kind == null) {
            return DEFAULT_NODE;
        }
        return NODE_TEMPLATES.getOrDefault(kind, DEFAULT_NODE);
    }

    /** 获取连线样式；kind 为空或未知时返回默认正交箭头线。 */
    public static String edgeStyle(String kind) {
        if (kind == null) {
            return DEFAULT_EDGE_STYLE;
        }
        return EDGE_STYLES.getOrDefault(kind, DEFAULT_EDGE_STYLE);
    }
}
