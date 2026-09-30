# ADR-001 绘图链路采用 JSON DSL 中间表示，由服务端转换为 Draw.io XML

- 状态：已接受
- 日期：2026-02（TASK-004 实施）
- 关联任务：`docs/tasks/DOING/TASK-004-DrawIO-JSON-DSL中间表示与转换器.md`

## 背景

原实现要求 LLM 直接生成完整 Draw.io XML：`agent_drawer` 输出内嵌 `mxCell` XML 的节点/连线 JSON，再在 `drawio_done` 中全量重复输出，`agent_reviewer` 审查后又全量重输出一遍。同一图形内容被生成约 3 次，且每次都包含 LLM 不擅长的信息：drawio 样式魔法字符串与节点坐标计算（提示词要求模型做网格数学并保证间距）。输出 token 中约 70%~80% 花费在 XML 语法与坐标上，模型输出速度成为端到端延迟瓶颈；LLM 手算坐标仍经常出现节点重叠、连线悬空等错误。

## 决策

将图形的"语义"与"呈现"解耦：

1. LLM（`agent_drawer`）只输出单行紧凑 JSON DSL，协议类型 `drawio_graph`：`nodes`（id/label/kind）与 `edges`（source/target/label/kind），不含坐标、不含 XML、不含 style。
2. 服务端 `data-visualizer-domain` 的 `JsonToDrawioConverter`（`DiagramDslParser` + `StyleTemplates` + `SimpleGridLayout`）确定性生成 `<mxGraphModel>` XML：解析校验（节点 id 唯一、边引用存在）→ 分层网格布局（起始 100,100、横向间距 ≥150、纵向间距 ≥120、有环回退顺序网格）→ kind→样式模板映射（样式迁移自原 SKILL.md）→ XML 生成（label 特殊字符转义、节点 id 重编号）。
3. `parseChatResponse` 新增 `drawio_graph` 分支；旧类型（`user/drawio/drawio_done`/裸 XML）全部保留兜底，转换失败静默回退，不产生新错误形态。
4. 时序图豁免：`drawio-sequence` 场景继续走旧"LLM 生成 XML"协议（时序图的 lifeline/activation/消息坐标是强结构化的，通用网格布局不适用）。
5. 对外 API 与前端零改动：转换器输出合法 XML，走既有 `type=drawio` 契约。

## 为什么这么做

- **输出 token 从约 3000+ 降到约 600（10 节点示例），延迟同比例下降**——LLM 输出时间是端到端延迟主因。
- **消除三类现网错误**：坐标重叠（布局算法保证间距）、样式错误（模板保证）、边引用悬空（校验保证）。
- **改造封闭在后端**，前端、API DTO、流式消息协议、装配链全部不动，风险面最小。

## 被否决的替代方案

| 方案 | 否决原因 |
| --- | --- |
| 引入 ELK/dagre 布局库 | v1 网格布局纯 Java 可实现，外部依赖违反"现有架构 > 新技术"；可作二期升级。 |
| 前端 JSON + dagre.js 转换 | 需改前端渲染与 `type=drawio` 契约，破坏"前端零改动"。 |
| Mermaid 中间语言 | 表达能力受限、引入新转换依赖、LLM 仍可能输出非法 Mermaid。 |
| 工具调用式绘图（add_node/add_edge） | 多轮工具调用往返开销大，首次生成可能更慢；适合二期做增量编辑。 |
| 流式增量渲染 | 需改前端消费逻辑，与本决策正交，可独立后续任务。 |

## 后果

- 布局质量退化为确定性网格分层：可保证无重叠与间距，但不承诺美观最优（正交路由、紧凑排布等留给二期 ELK 评估）。
- 嵌套分组（VPC/swimlane 容器）暂不支持：`boundary` kind 以独立虚线框渲染。
- 运行期通过 `/admin` 提交的旧式自定义 Agent 配置不受影响，但也不享受新链路（旧协议输出走兜底路径）。
- 模型不遵守新协议时退化为旧性能而非故障（旧路径全量保留）。
- kind 枚举（节点/连线语义类型）现在有三处关联：`StyleTemplates`、Agent YAML 提示词、3 个 SKILL.md；修改时必须同步。
