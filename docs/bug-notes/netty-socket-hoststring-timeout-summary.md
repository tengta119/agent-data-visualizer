# Netty Socket Client 重连与命令超时问题复盘

## 问题现象

- 服务端启动后，客户端先连上，随后在执行命令时立刻断开。
- 客户端断开后会在约 3 秒后自动重连。
- 服务端日志显示 `Sent command` 之后没有收到响应，最终抛出 `Command execution failed or timed out`。

## 触发时间

- 首次定位时间：2026-08-01

## 根因

Java 服务端与 Python 客户端的命令协议字段不一致。

服务端发送的命令 JSON 为：

```json
{
  "id": "xxx",
  "command": "ls",
  "hostString": "127.0.0.1"
}
```

但 Python 客户端原先按下面的结构解析：

```python
GatewayCommandEntity(
    id=payload["id"],
    command=payload["command"],
    ip=payload["ip"],
)
```

也就是客户端要求字段是 `ip`，而服务端实际发送的是 `hostString`。

## 为什么会出现“断开重连 + 超时”

1. 服务端正常发出命令。
2. 客户端读取到消息后，`from_json()` 解析 `payload["ip"]` 时抛出 `KeyError`。
3. 异常从监听循环冒泡到外层 `run_forever()`，客户端连接被关闭。
4. 客户端进入自动重连逻辑，所以日志里看到“断开后 3 秒重连”。
5. 服务端已经把请求放进 `pendingResponses`，但始终收不到响应。
6. 30 秒后 `future.get(30, TimeUnit.SECONDS)` 超时，于是报 `Command execution failed or timed out`。

## 本次修复

已将 Python 客户端协议字段改为 `hostString`，并兼容旧字段 `ip`：

- `gateway-socket-client/src/gateway_socket_client/models.py`
- `gateway-socket-client/src/gateway_socket_client/client.py`

## 后续建议

- 服务端与客户端共享统一协议文档，避免字段漂移。
- 客户端单条消息解析异常时，尽量只记录错误并跳过该消息，不要直接让整个连接退出。
- 如果协议会演进，建议显式加版本号或做字段兼容处理。
