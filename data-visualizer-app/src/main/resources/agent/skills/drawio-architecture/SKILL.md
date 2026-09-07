---
name: drawio-architecture
description: 提供 Draw.io 架构图 (Architecture Diagram) 的节点与连线类型规范。当用户要求绘制系统架构图、部署图、微服务架构图时调用。
license: Apache-2.0
metadata:
  author: xfg-studio
  version: "2.0.0"
  category: drawio-design
---

# Draw.io 架构图类型规范（JSON DSL）

## 1. 适用场景
当用户请求绘制：系统架构图 (System Architecture)、部署图 (Deployment Diagram)、网络拓扑图、微服务架构图时使用。

## 2. 节点类型 (kind)
drawio_graph JSON 中每个节点的 `kind` 从以下类型中选取，样式与尺寸由系统自动渲染：

| kind | 用途 | 视觉样式（系统自动） |
| --- | --- | --- |
| `client` | 用户/客户端（Web、App、外部系统） | 人形图标 |
| `service` | 微服务、应用实例、容器 | 蓝色圆角矩形（阴影） |
| `database` | MySQL、Redis、MongoDB 等存储 | 黄色圆柱体 |
| `queue` | Kafka、RocketMQ 等消息队列/中间件 | 紫色折角矩形 |
| `boundary` | 区域/分组（VPC、子网、集群等） | 灰色虚线框 |

注：当前版本 `boundary` 作为独立虚线框渲染，不支持把其他节点嵌套在区域框内部。

## 3. 连线类型 (kind)

| kind | 用途 |
| --- | --- |
| `default` | 默认调用关系（可省略 kind 字段） |
| `call` | HTTP / API 调用 |
| `async` | 异步消息/事件（虚线） |
| `data` | 数据读写 |

## 4. 输出示例
```json
{"type":"drawio_graph","nodes":[{"id":"n1","label":"Mobile App","kind":"client"},{"id":"n2","label":"API 网关","kind":"service"},{"id":"n3","label":"订单服务","kind":"service"},{"id":"n4","label":"MySQL 主库","kind":"database"},{"id":"n5","label":"Kafka","kind":"queue"}],"edges":[{"source":"n1","target":"n2","kind":"call","label":"HTTPS"},{"source":"n2","target":"n3","kind":"call"},{"source":"n3","target":"n4","kind":"data","label":"JDBC"},{"source":"n3","target":"n5","kind":"async"}]}
```

## 5. 注意事项
- 不要输出坐标、XML 或 style；节点位置与样式由系统自动生成（分层网格布局）。
- 节点 id 必须唯一；连线只能引用存在的节点 id。
- 通常按"客户端层 → 接入层 → 服务层 → 数据层"组织调用关系即可，无需指定位置。
