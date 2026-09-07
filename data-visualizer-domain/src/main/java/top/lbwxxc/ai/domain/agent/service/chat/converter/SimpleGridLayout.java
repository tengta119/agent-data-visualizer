package top.lbwxxc.ai.domain.agent.service.chat.converter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 分层网格布局：按边关系把节点分配到层（源节点层 → 下游层），同层横排、层间纵排。
 * <p>
 * 间距规则与原提示词约束一致：起始坐标 100,100，同层相邻节点横向间距 ≥150，相邻层纵向间距 ≥120，
 * 任意两节点矩形不重叠。检测到环时回退为顺序网格（按节点顺序切分为近方阵）。
 * 布局为纯函数，无共享可变状态。
 */
public final class SimpleGridLayout {

    public static final int START_X = 100;
    public static final int START_Y = 100;
    public static final int HORIZONTAL_GAP = 150;
    public static final int VERTICAL_GAP = 120;

    private SimpleGridLayout() {
    }

    /** 节点布局矩形。 */
    public static final class Rect {

        public final int x;
        public final int y;
        public final int width;
        public final int height;

        public Rect(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 计算所有节点的布局位置。
     *
     * @param graph 已通过解析校验的图形模型
     * @return DSL 节点 id → 布局矩形；图形无节点时返回空 Map
     */
    public static Map<String, Rect> layout(DrawioGraphDslVO graph) {
        Map<String, Rect> result = new HashMap<>();
        List<DrawioGraphDslVO.Node> nodes = graph.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return result;
        }

        Map<String, Integer> layerOf = assignLayers(graph);
        if (layerOf == null) {
            // 有环时回退顺序网格
            layerOf = sequentialLayers(nodes);
        }

        // 按层分组，保持节点原始顺序
        TreeMap<Integer, List<DrawioGraphDslVO.Node>> layers = new TreeMap<>();
        for (DrawioGraphDslVO.Node node : nodes) {
            layers.computeIfAbsent(layerOf.getOrDefault(node.getId(), 0), k -> new ArrayList<>()).add(node);
        }

        // 列宽 = 同列（层内同位置）节点最大宽度；行高 = 同层节点最大高度
        Map<Integer, Integer> columnWidths = new HashMap<>();
        Map<Integer, Integer> rowHeights = new HashMap<>();
        for (Map.Entry<Integer, List<DrawioGraphDslVO.Node>> entry : layers.entrySet()) {
            List<DrawioGraphDslVO.Node> layerNodes = entry.getValue();
            for (int column = 0; column < layerNodes.size(); column++) {
                int width = StyleTemplates.nodeTemplate(layerNodes.get(column).getKind()).width;
                columnWidths.merge(column, width, Math::max);
            }
            int maxHeight = 0;
            for (DrawioGraphDslVO.Node node : layerNodes) {
                maxHeight = Math.max(maxHeight, StyleTemplates.nodeTemplate(node.getKind()).height);
            }
            rowHeights.put(entry.getKey(), maxHeight);
        }

        // 列起始 x：前一列宽 + 横向间距；层起始 y：前行高 + 纵向间距
        Map<Integer, Integer> columnX = new HashMap<>();
        int x = START_X;
        for (int column = 0; column < columnWidths.size(); column++) {
            columnX.put(column, x);
            x += columnWidths.get(column) + HORIZONTAL_GAP;
        }
        Map<Integer, Integer> layerY = new HashMap<>();
        int y = START_Y;
        for (Integer layer : layers.keySet()) {
            layerY.put(layer, y);
            y += rowHeights.get(layer) + VERTICAL_GAP;
        }

        for (Map.Entry<Integer, List<DrawioGraphDslVO.Node>> entry : layers.entrySet()) {
            Integer layer = entry.getKey();
            List<DrawioGraphDslVO.Node> layerNodes = entry.getValue();
            for (int column = 0; column < layerNodes.size(); column++) {
                DrawioGraphDslVO.Node node = layerNodes.get(column);
                StyleTemplates.NodeTemplate template = StyleTemplates.nodeTemplate(node.getKind());
                result.put(node.getId(), new Rect(columnX.get(column), layerY.get(layer), template.width, template.height));
            }
        }
        return result;
    }

    /**
     * Kahn 分层：无入边节点为第 0 层；layer(target) = max(layer(source)) + 1。
     * 返回 null 表示存在环。
     */
    private static Map<String, Integer> assignLayers(DrawioGraphDslVO graph) {
        Map<String, Integer> layerOf = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (DrawioGraphDslVO.Node node : graph.getNodes()) {
            adjacency.put(node.getId(), new ArrayList<>());
            inDegree.put(node.getId(), 0);
        }
        Set<String> nodeIds = inDegree.keySet();
        for (DrawioGraphDslVO.Edge edge : graph.getEdges()) {
            if (nodeIds.contains(edge.getSource()) && nodeIds.contains(edge.getTarget())
                    && !edge.getSource().equals(edge.getTarget())) {
                adjacency.get(edge.getSource()).add(edge.getTarget());
                inDegree.merge(edge.getTarget(), 1, Integer::sum);
            }
        }

        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
                layerOf.put(entry.getKey(), 0);
            }
        }

        int processed = 0;
        while (!queue.isEmpty()) {
            String current = queue.remove(queue.size() - 1);
            processed++;
            int nextLayer = layerOf.get(current) + 1;
            for (String target : adjacency.get(current)) {
                layerOf.merge(target, nextLayer, Math::max);
                if (inDegree.merge(target, -1, Integer::sum) == 0) {
                    queue.add(target);
                }
            }
        }

        if (processed < nodeIds.size()) {
            // 存在环（或自环），无法完成拓扑分层
            return null;
        }
        return layerOf;
    }

    /** 顺序网格回退：按节点顺序每行 sqrt(n) 个切分为近方阵。 */
    private static Map<String, Integer> sequentialLayers(List<DrawioGraphDslVO.Node> nodes) {
        Map<String, Integer> layerOf = new HashMap<>();
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(nodes.size())));
        for (int i = 0; i < nodes.size(); i++) {
            layerOf.put(nodes.get(i).getId(), i / columns);
        }
        return layerOf;
    }
}
