---
name: drawio-uml
description: 提供 Draw.io UML图 (类图、用例图等) 的节点与连线类型规范。当用户要求绘制 UML 图、类图、用例图时调用。
license: Apache-2.0
metadata:
  author: xfg-studio
  version: "2.0.0"
  category: drawio-design
---

# Draw.io UML 图类型规范（JSON DSL）

## 1. 适用场景
当用户请求绘制：类图 (Class Diagram)、用例图 (Use Case Diagram)、组件图等面向对象设计图表时使用。

## 2. 节点类型 (kind)
drawio_graph JSON 中每个节点的 `kind` 从以下类型中选取，样式与尺寸由系统自动渲染：

| kind | 用途 | 视觉样式（系统自动） |
| --- | --- | --- |
| `class` | 类（类名可带属性/方法，用换行 `\n` 分隔） | 蓝色矩形，顶部对齐 |
| `interface` | 接口（label 建议以 `<<interface>>` 开头） | 绿色矩形，顶部对齐 |
| `actor` | 参与者/用户/外部系统 | 人形图标 |
| `usecase` | 用例/系统功能 | 黄色椭圆 |

类节点 label 示例：`"OrderService\n+ createOrder()\n+ cancelOrder()"`。

## 3. 连线类型 (kind)

| kind | UML 关系 | 视觉样式（系统自动） |
| --- | --- | --- |
| `inherit` | 继承/泛化（指向父类） | 实线空心三角 |
| `implement` | 实现（指向接口） | 虚线空心三角 |
| `associate` | 关联 | 实线无箭头 |
| `aggregate` | 聚合（指向整体） | 实线空心菱形 |
| `compose` | 组合（指向整体） | 实线实心菱形 |
| `depend` | 依赖 | 虚线开口箭头 |

## 4. 输出示例
```json
{"type":"drawio_graph","nodes":[{"id":"n1","label":"OrderRepository\n+ save()\n+ findById()","kind":"class"},{"id":"n2","label":"<<interface>> IRepository","kind":"interface"},{"id":"n3","label":"用户","kind":"actor"},{"id":"n4","label":"下单","kind":"usecase"}],"edges":[{"source":"n1","target":"n2","kind":"implement"},{"source":"n3","target":"n4","kind":"associate"}]}
```

## 5. 注意事项
- 不要输出坐标、XML 或 style；节点位置与样式由系统自动生成。
- 节点 id 必须唯一；连线只能引用存在的节点 id。
- 属性/方法的访问修饰符：`+` 公有，`-` 私有，`#` 受保护，`~` 包级。
