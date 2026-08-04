---
name: drawio-flowchart
description: 提供 Draw.io 流程图 (Flowchart) 的规范、样式和连线设计指南。当用户要求绘制流程图、业务流程、算法逻辑时调用。
license: Apache-2.0
metadata:
  author: xfg-studio
  version: "1.0.0"
  category: drawio-design
---

# Draw.io 流程图设计规范

## 1. 适用场景
当用户请求绘制：
- 流程图 (Flowchart)
- 业务流程
- 审批流
- 算法控制流

## 2. 节点样式规范 (Style)

### 2.1 开始 / 结束 (Start / End)
表示流程的起点或终点。
样式：圆角极大（胶囊状）或椭圆形。
```xml
<mxCell id="2" value="开始" style="rounded=1;whiteSpace=wrap;html=1;arcSize=50;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;" vertex="1" parent="1">
    <mxGeometry x="300" y="50" width="120" height="60" as="geometry"/>
</mxCell>
```

### 2.2 处理 / 步骤 (Process / Step)
表示具体的动作或处理过程。
样式：标准直角矩形或微小圆角矩形。
```xml
<mxCell id="3" value="执行数据校验" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;" vertex="1" parent="1">
    <mxGeometry x="300" y="150" width="120" height="60" as="geometry"/>
</mxCell>
```

### 2.3 判断 / 条件 (Decision)
表示条件分支。
样式：菱形 (shape=rhombus)。
```xml
<mxCell id="4" value="是否合法？" style="rhombus;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;" vertex="1" parent="1">
    <mxGeometry x="310" y="250" width="100" height="80" as="geometry"/>
</mxCell>
```

### 2.4 数据 / 输入输出 (Data / I/O)
表示数据的输入或输出。
样式：平行四边形 (shape=parallelogram)。
```xml
<mxCell id="5" value="输出报告" style="shape=parallelogram;perimeter=parallelogramPerimeter;whiteSpace=wrap;html=1;fixedSize=1;fillColor=#e1d5e7;strokeColor=#9673a6;" vertex="1" parent="1">
    <mxGeometry x="300" y="380" width="120" height="60" as="geometry"/>
</mxCell>
```

### 2.5 文档 (Document)
表示生成或参考的文档。
样式：底部波浪形的文档形状 (shape=document)。
```xml
<mxCell id="6" value="生成PDF" style="shape=document;whiteSpace=wrap;html=1;boundedLbl=1;fillColor=#f8cecc;strokeColor=#b85450;" vertex="1" parent="1">
    <mxGeometry x="500" y="150" width="120" height="80" as="geometry"/>
</mxCell>
```

## 3. 连线样式规范 (Edge Style)

### 3.1 默认流程线
带有单向箭头的实线。
样式：`edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;`
```xml
<mxCell id="e1" value="" style="edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;" edge="1" parent="1" source="2" target="3">
    <mxGeometry relative="1" as="geometry"/>
</mxCell>
```

### 3.2 条件分支连线 (带有 "是" / "否" 标签)
从判断节点引出的连线，必须有 value。
```xml
<mxCell id="e2" value="是" style="edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;" edge="1" parent="1" source="4" target="5">
    <mxGeometry relative="1" as="geometry"/>
</mxCell>
```

## 4. 布局建议
1. **主干向下**：流程的主线应当垂直向下发展。
2. **分支向两侧**：遇到条件判断时，主干分支（如"是"）继续向下，例外分支（如"否"）向左或向右伸出，处理完毕后汇合或直接结束。
3. **对称和对齐**：同一级别的节点应当在 Y 坐标上对齐；同一条直线上的节点应当在 X 坐标中心对齐。
4. **统一间距**：相邻节点间的垂直距离建议保持 40-60，以便阅读。