

当前 远程 CLI 支持以下子命令：

- `clients`
- 其它常见命令

兼容说明：

- 如果直接运行 `python -m uv run netty-socket-server --port 9000`，会自动按 `serve` 子命令处理。

## 1. 启动服务端

命令：

```powershell
python -m uv run netty-socket-server serve --port 9000 --admin-port 9001
```

参数说明：

- `--host`
  - Socket 服务监听地址
  - 默认值：`0.0.0.0`
- `--port`
  - Socket 服务监听端口
  - 默认值：`9000`
- `--timeout`
  - 等待客户端响应超时时间，单位秒
  - 默认值：`30`
- `--log-level`
  - 日志级别
  - 默认值：`INFO`
- `--admin-host`
  - 本地管理端口监听地址
  - 默认值：`127.0.0.1`
- `--admin-port`
  - 本地管理端口监听端口
  - 默认值：`9001`

说明：

- 业务 socket 服务负责接收客户端连接和收发业务指令。
- 管理端口只用于本机命令行查询在线客户端、下发命令。

## 2. 查询当前连接客户端

命令：

```powershell
clients
```

示例输出：

```text
Connected clients: 1
127.0.0.1
```

## 3. 向指定客户端发送命令

命令：

```powershell
python -m uv run netty-socket-server send-command --admin-port 9001 --ip 127.0.0.1 --command "PING"
```
