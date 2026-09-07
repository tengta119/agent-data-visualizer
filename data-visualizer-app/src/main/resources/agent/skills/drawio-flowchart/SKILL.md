---
name: drawio-flowchart
description: 提供 Draw.io 流程图 (Flowchart) 的节点与连线类型规范。当用户要求绘制流程图、业务流程、审批流、算法逻辑时调用。
license: Apache-2.0
metadata:
  author: xfg-studio
  version: "2.0.0"
  category: drawio-design
---

# Draw.io 流程图类型规范（JSON DSL）

## 1. 适用场景
当用户请求绘制：流程图 (Flowchart)、业务流程、审批流、算法控制流时使用。

## 2. 节点类型 (kind)
drawio_graph JSON 中每个节点的 `kind` 从以下类型中选取，样式与尺寸由系统自动渲染：

| kind | 用途 | 视觉样式（系统自动） |
| --- | --- | --- |
| `start` | 流程起点 | 绿色胶囊形 |
| `end` | 流程终点 | 绿色胶囊形 |
| `process` | 处理/步骤 | 蓝色直角矩形 |
| `decision` | 判断/条件分支 | 黄色菱形 |
| `data` | 数据输入/输出 | 紫色平行四边形 |
| `document` | 生成/参考的文档 | 红色文档形状 |

## 3. 连线类型 (kind)

| kind | 用途 |
| --- | --- |
| `default` | 默认流程线（可省略 kind 字段） |
| `async` | 异步/旁路分支 |

从 `decision` 节点引出的连线必须在 `label` 中标注条件（如 "是"/"否"）。

## 4. 输出示例
```json
{"type":"drawio_graph","nodes":[{"id":"n1","label":"开始","kind":"start"},{"id":"n2","label":"执行数据校验","kind":"process"},{"id":"n3","label":"是否合法？","kind":"decision"},{"id":"n4","label":"输出报告","kind":"document"}],"edges":[{"source":"n1","target":"n2"},{"source":"n2","target":"n3"},{"source":"n3","target":"n4","label":"是"},{"source":"n3","target":"n1","label":"否"}]}
```

## 5. 注意事项
- 不要输出坐标、XML 或 style；节点位置与样式由系统自动生成。
- 节点 id 必须唯一；连线只能引用存在的节点 id。
