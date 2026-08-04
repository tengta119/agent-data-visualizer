---
name: drawio-sequence
description: 提供 Draw.io 时序图 (Sequence Diagram) 的规范、样式和连线设计指南。当用户要求绘制时序图或交互图时调用。
license: Apache-2.0
metadata:
  author: xfg-studio
  version: "1.0.0"
  category: drawio-design
---

# Draw.io 时序图设计规范

## 1. 适用场景
当用户请求绘制：
- 时序图 (Sequence Diagram)
- 系统交互图
- API 调用时序等强调时间先后顺序的图表时使用。

## 2. 节点样式规范 (Style)

### 2.1 生命周期对象/参与者 (Lifeline)
时序图的核心。一个包含标题（Actor 或 Object）和一条垂直虚线的形状。
在 Draw.io 中，标准的时序图对象样式如下：
```xml
<mxCell id="2" value="Client" style="shape=umlLifeline;perimeter=lifelinePerimeter;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;outlineConnect=0;fillColor=#dae8fc;strokeColor=#6c8ebf;" vertex="1" parent="1">
    <mxGeometry x="100" y="50" width="100" height="400" as="geometry"/>
</mxCell>
```
*注意*：height 必须足够大，以覆盖所有的时间轴连线。

### 2.2 激活块 (Activation / Execution Specification)
在生命周期虚线上的长条矩形，表示对象正在执行操作。
作为 Lifeline 的子节点（parent 为 Lifeline 的 id）：
```xml
<mxCell id="3" value="" style="html=1;points=[];perimeter=orthogonalPerimeter;fillColor=#fff2cc;strokeColor=#d6b656;" vertex="1" parent="2">
    <!-- 相对于 Lifeline 的局部坐标。x 通常是 (宽度-10)/2，以居中。比如 width=100, x=45 -->
    <mxGeometry x="45" y="80" width="10" height="120" as="geometry"/>
</mxCell>
```

## 3. 连线样式规范 (Edge Style)

### 3.1 同步消息 (Synchronous Message)
实线，实心箭头。表示调用方等待响应。
连线需要连接两个激活块或 Lifeline。
样式：`html=1;verticalAlign=bottom;endArrow=block;edgeStyle=elbowEdgeStyle;elbow=vertical;`
XML 示例：
```xml
<mxCell id="e1" value="1. 请求数据" style="html=1;verticalAlign=bottom;endArrow=block;edgeStyle=elbowEdgeStyle;elbow=vertical;" edge="1" parent="1" source="2" target="4">
    <!-- 通过 geometry 设定相对位置，如果需要的话 -->
    <mxGeometry relative="1" as="geometry">
        <Array as="points">
            <mxPoint x="160" y="100"/>
            <mxPoint x="300" y="100"/>
        </Array>
    </mxGeometry>
</mxCell>
```

### 3.2 异步消息 (Asynchronous Message)
实线，空心普通箭头。表示调用方不等待响应。
样式：`html=1;verticalAlign=bottom;endArrow=open;endSize=8;edgeStyle=elbowEdgeStyle;elbow=vertical;`

### 3.3 返回消息 (Return Message)
虚线，普通箭头。表示调用的返回。
样式：`html=1;verticalAlign=bottom;endArrow=open;dashed=1;endSize=8;edgeStyle=elbowEdgeStyle;elbow=vertical;`

### 3.4 自调用消息 (Self Message)
连线从一个激活块出发，绕一圈回到同一个激活块的下方。
样式：`html=1;verticalAlign=bottom;endArrow=block;edgeStyle=elbowEdgeStyle;elbow=vertical;`
需要在 `<Array as="points">` 中定义转折点。

## 4. 布局算法建议 (严格计算)
为避免重叠和线条交叉，请严格计算坐标：
1. **Lifeline X坐标**：参与者从左到右排列，x=100, x=300, x=500, x=700... 保持固定间距 200。Y 坐标一致（如 y=50）。
2. **消息 Y坐标**：消息按时间顺序自上而下排列，第一条消息 y=120，后续每条消息 y 坐标递增 50（例如 120, 170, 220）。
3. **激活块**：x 坐标相对于 Lifeline 居中（如 Lifeline 宽100，激活块宽10，x=45），y 坐标等于接收消息的 y 坐标，height 等于持续的时间跨度。
4. 标注消息序号（如 "1. login()", "1.1 validate()"），更清晰。
5. 使用 elbowEdgeStyle 保证线条水平。