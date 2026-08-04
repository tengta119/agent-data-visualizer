---
name: drawio-architecture
description: 提供 Draw.io 架构图 (Architecture Diagram) 的规范、样式和连线设计指南。当用户要求绘制系统架构图、部署图、微服务架构图时调用。
license: Apache-2.0
metadata:
  author: xfg-studio
  version: "1.0.0"
  category: drawio-design
---

# Draw.io 架构图设计规范

## 1. 适用场景
当用户请求绘制：
- 系统架构图 (System Architecture)
- 部署图 (Deployment Diagram)
- 网络拓扑图
- 微服务架构图

## 2. 节点样式规范 (Style)

### 2.1 区域框 / 边界 (Boundary / Group)
用于表示 VPC、子网、集群、可用区、模块分组等大范围。
样式：圆角虚线边框，浅色填充，标题置于左上角或居中上方。
```xml
<mxCell id="2" value="VPC - 生产环境" style="swimlane;whiteSpace=wrap;html=1;dashed=1;fillColor=#f5f5f5;fontColor=#333333;strokeColor=#666666;startSize=30;align=center;" vertex="1" parent="1">
    <mxGeometry x="50" y="50" width="600" height="400" as="geometry"/>
</mxCell>
```

### 2.2 计算/服务节点 (Service / Server)
用于表示微服务、应用实例、EC2、容器。
样式：圆角矩形，立体感或阴影，特定颜色（如蓝色系）。
```xml
<mxCell id="3" value="订单服务\n(Order Service)" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;shadow=1;fontStyle=1;" vertex="1" parent="2">
    <mxGeometry x="50" y="80" width="120" height="60" as="geometry"/>
</mxCell>
```

### 2.3 数据库/存储 (Database / Storage)
用于表示 MySQL, Redis, MongoDB 等。
样式：圆柱体 (shape=cylinder3)
```xml
<mxCell id="4" value="MySQL\n(主库)" style="shape=cylinder3;whiteSpace=wrap;html=1;boundedLbl=1;backgroundOutline=1;size=15;fillColor=#ffe6cc;strokeColor=#d79b00;shadow=1;" vertex="1" parent="2">
    <mxGeometry x="250" y="80" width="80" height="80" as="geometry"/>
</mxCell>
```

### 2.4 消息队列 / 中间件 (Message Queue)
样式：普通矩形带折角或双竖线 (shape=process 或 shape=component)。
```xml
<mxCell id="5" value="Kafka" style="shape=process;whiteSpace=wrap;html=1;backgroundOutline=1;fillColor=#e1d5e7;strokeColor=#9673a6;" vertex="1" parent="2">
    <mxGeometry x="50" y="200" width="120" height="60" as="geometry"/>
</mxCell>
```

### 2.5 用户 / 客户端 (Client / User)
样式：人形图标或手机/电脑图标。
```xml
<mxCell id="6" value="Mobile App" style="shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;fillColor=#f8cecc;strokeColor=#b85450;" vertex="1" parent="1">
    <mxGeometry x="100" y="550" width="30" height="60" as="geometry"/>
</mxCell>
```

## 3. 连线样式规范 (Edge Style)

### 3.1 HTTP / API 调用
双向或单向实线箭头，表示网络请求。
样式：`endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;strokeWidth=2;strokeColor=#666666;`

### 3.2 异步消息 / 事件
虚线箭头。
样式：`endArrow=classic;html=1;dashed=1;edgeStyle=orthogonalEdgeStyle;strokeColor=#9673a6;`

### 3.3 数据读写
实线带不同端点或直接箭头。
样式：`endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;strokeColor=#d79b00;`

## 4. 布局算法建议 (网格法)
为避免节点重叠，请严格计算 x 和 y 坐标：
1. **分层架构**：通常从上到下为：客户端层 (y=100) -> 接入层 (y=250) -> 应用服务层 (y=400) -> 数据层 (y=550)。
2. **同层排列**：同一层的节点，x 坐标按顺序增加（例如：x=100, 300, 500）。
3. **安全间距**：节点间横向距离 >= 150，纵向距离 >= 120。
4. **区域嵌套**：利用父子关系 (parent) 或视觉覆盖实现组件的归属，如将服务放在 VPC 框内。
5. **颜色区分**：不同类型的节点用不同颜色：服务蓝色，数据库黄色，中间件紫色，边框灰色。