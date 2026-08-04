---
name: drawio-uml
description: 提供 Draw.io UML图 (类图、用例图等) 的规范、样式和连线设计指南。当用户要求绘制 UML 图、类图、用例图或对象图时调用。
license: Apache-2.0
metadata:
  author: xfg-studio
  version: "1.0.0"
  category: drawio-design
---

# Draw.io UML 图设计规范

## 1. 适用场景
当用户请求绘制：
- 类图 (Class Diagram)
- 用例图 (Use Case Diagram)
- 组件图 (Component Diagram)
等面向对象设计图表时使用。

## 2. 节点样式规范 (Style)

### 2.1 类图 (Class Node)
类图通常包含类名、属性区、方法区。可以分成三个独立的文本块，或者使用 UML 专属形状。
推荐使用带有标题、属性、方法的泳道形式：
```xml
<mxCell id="类节点ID" value="ClassName" style="swimlane;fontStyle=1;childLayout=stackLayout;horizontal=1;startSize=30;horizontalStack=0;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=1;marginBottom=0;fillColor=#dae8fc;strokeColor=#6c8ebf;" vertex="1" parent="1">
    <mxGeometry x="100" y="100" width="160" height="120" as="geometry"/>
</mxCell>
<!-- 属性和方法作为子节点 -->
<mxCell id="属性节点ID" value="+ attribute: Type" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=4;spacingRight=4;overflow=hidden;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;rotatable=0;" vertex="1" parent="类节点ID">
    <mxGeometry y="30" width="160" height="26" as="geometry"/>
</mxCell>
```
*简化方案*：如果结构较简单，可直接使用普通矩形加 HTML 文本，使用 `<hr>` 分隔：
```xml
<mxCell id="2" value="&lt;b&gt;ClassName&lt;/b&gt;&lt;hr&gt;+ attribute: Type&lt;br&gt;- privateAttr: int&lt;hr&gt;+ method(): void" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=5;" vertex="1" parent="1">
    <mxGeometry x="100" y="100" width="160" height="100" as="geometry"/>
</mxCell>
```

### 2.2 接口 (Interface)
与类节点类似，但通常包含 `&lt;i&gt;&lt;&lt;interface&gt;&gt;&lt;/i&gt;`。
样式：`rounded=0;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;`

### 2.3 用例 (Use Case)
椭圆形，表示系统的功能单元。
样式：`ellipse;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;`

### 2.4 参与者 (Actor)
人形图标，表示用户或外部系统。
样式：`shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;fillColor=#f8cecc;strokeColor=#b85450;`

## 3. 连线样式规范 (Edge Style)

### 3.1 继承/泛化 (Generalization)
实线，空心三角形箭头，指向父类。
样式：`endArrow=block;html=1;endFill=0;edgeStyle=orthogonalEdgeStyle;`

### 3.2 实现 (Realization)
虚线，空心三角形箭头，指向接口。
样式：`endArrow=block;dashed=1;html=1;endFill=0;edgeStyle=orthogonalEdgeStyle;`

### 3.3 关联 (Association)
实线，无箭头或普通箭头，表示对象间的引用。
样式：`endArrow=none;html=1;edgeStyle=orthogonalEdgeStyle;` 或者 `endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;`

### 3.4 聚合 (Aggregation)
实线，空心菱形，指向整体。
样式：`endArrow=diamondThin;endFill=0;html=1;edgeStyle=orthogonalEdgeStyle;`

### 3.5 组合 (Composition)
实线，实心菱形，指向整体。
样式：`endArrow=diamondThin;endFill=1;html=1;edgeStyle=orthogonalEdgeStyle;`

### 3.6 依赖 (Dependency)
虚线，普通箭头，指向被依赖项。
样式：`endArrow=open;dashed=1;html=1;endSize=8;edgeStyle=orthogonalEdgeStyle;`

## 4. 布局算法建议 (网格法)
为避免节点重叠，请严格计算 x 和 y 坐标：
1. **分层/分列**：将类图分为几列。例如，核心类在中间列 (x=300)，关联类在左侧 (x=50) 或右侧 (x=550)。
2. **纵向排列**：同一列中的节点，y 坐标按顺序增加（例如：y=100, 280, 460）。
3. **安全间距**：横向间距 >= 200，纵向间距 >= 150。
4. 属性和方法的访问修饰符：`+` 公有，`-` 私有，`#` 受保护，`~` 包级。