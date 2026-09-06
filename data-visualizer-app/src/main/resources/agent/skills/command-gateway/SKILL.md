---
name: command-gateway
description: 通过 ShellExecutor MCP 工具查询网关客户端并在本机或指定客户端执行命令。适用于需要运行 clients、ls、dir、pwd、进程/端口检查、远程客户端诊断等命令行任务。
license: MIT
---

# Command Gateway Skill

## 使用场景

当用户要求查询在线客户端、检查远程机器状态、执行命令行诊断、运行 `clients`、`ls`、`dir`、`pwd` 或排查端口/进程/文件问题时，优先使用 `ShellExecutor.execute` MCP 工具。

该工具支持两种模式：

- `local`：在当前 data-visualizer 应用所在机器的持久 PowerShell 进程中执行命令。
- `remote`：通过网关 Socket 服务，把命令发送到指定 `hostName` 对应的客户端执行。

## 工具调用格式

必须调用 `ShellExecutor.execute`，入参固定为：

```json
{
  "command": "要执行的命令",
  "commandType": "local 或 remote",
  "hostName": "客户端地址"
}
```

字段说明：

- `command`：终端命令，例如 `clients`、`ls`、`dir`、`pwd`。
- `commandType`：命令类型，只能是 `local` 或 `remote`。
- `hostName`：远程客户端地址。`remote` 模式必须填写，例如 `127.0.0.1`；`local` 模式可传空字符串。

## 查询客户端

查询当前连接的远程客户端时，使用本地模式执行 `clients`：

```json
{
  "command": "clients",
  "commandType": "local",
  "hostName": ""
}
```

如果返回中包含客户端地址，例如 `127.0.0.1`，后续远程命令需要把这个地址填入 `hostName`。

## 远程执行命令

向指定客户端执行命令时，使用远程模式：

```json
{
  "command": "ls",
  "commandType": "remote",
  "hostName": "127.0.0.1"
}
```

远程模式依赖 `gateway-socket-client` 已连接到 Java 服务端。如果远程执行超时，应先执行 `clients` 确认客户端在线。

## 本地执行命令

如果用户明确要求在服务端本机执行，或者远程客户端不可用，使用本地模式：

```json
{
  "command": "pwd",
  "commandType": "local",
  "hostName": ""
}
```

## 执行原则

1. 在远程执行前，若用户没有给出客户端地址，先执行 `clients` 获取在线客户端。
2. `remote` 模式必须使用 `hostName`，不要使用 `ip`、`hostString` 或其他字段名。
3. 如果工具返回 `Command execution failed or timed out`，先说明可能是客户端未连接、字段协议不一致、客户端执行异常或响应超时，再建议查询 `clients`。
4. 服务端采用默认拒绝策略。只有命中当前环境配置的允许规则才会执行；未命中、无法解析或包含复合 Shell 控制结构的请求返回 `forbidden`，不会执行部分命令，也不要通过拆分、Shell 包装器或脚本解释器绕过审查。
5. `forbidden` 表示命令未执行；不要把它当作客户端离线或执行失败，也不要重复改写命令绕过策略。
6. 返回结果时要说明命令执行位置：本机 `local` 或远程客户端 `remote: hostName`。

