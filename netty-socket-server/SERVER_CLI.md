# Netty Socket Server CLI

服务端项目位于当前根目录，命令行入口为 `netty-socket-server`。

## 启动方式

在项目根目录运行：

```powershell
python -m uv run netty-socket-server --help
```

当前 CLI 支持以下子命令：

- `serve`
- `clients`
- `send-command`

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
python -m uv run netty-socket-server clients --admin-port 9001
```

JSON 输出：

```powershell
python -m uv run netty-socket-server clients --admin-port 9001 --json
```

参数说明：

- `--admin-host`
  - 管理端口地址
  - 默认值：`127.0.0.1`
- `--admin-port`
  - 管理端口端口号
  - 默认值：`9001`
- `--json`
  - 输出原始 JSON 响应

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

JSON 输出：

```powershell
python -m uv run netty-socket-server send-command --admin-port 9001 --ip 127.0.0.1 --command "PING" --json
```

参数说明：

- `--admin-host`
  - 管理端口地址
  - 默认值：`127.0.0.1`
- `--admin-port`
  - 管理端口端口号
  - 默认值：`9001`
- `--ip`
  - 目标客户端 IP
- `--command`
  - 下发给客户端的命令内容
- `--json`
  - 输出原始 JSON 响应

示例输出：

```text
Request ID: 7f4a6a62-6d78-4fd0-b95a-90c52b1d55fb
Target IP: 127.0.0.1
Command: PING
Response Status: success
Response Message: PONG
Image: no
```

## 常用联调流程

1. 启动服务端

```powershell
python -m uv run netty-socket-server serve --port 9000 --admin-port 9001
```

2. 启动客户端

```powershell
cd D:\python-project\netty-socket-server\gateway-socket-client
python -m uv run gateway-socket-client --server-host 127.0.0.1 --server-port 9000
```

3. 查询在线客户端

```powershell
cd D:\python-project\netty-socket-server
python -m uv run netty-socket-server clients --admin-port 9001
```

4. 向客户端发送命令

```powershell
python -m uv run netty-socket-server send-command --admin-port 9001 --ip 127.0.0.1 --command "HELP"
```

## 当前客户端支持的安全命令

如果你使用当前仓库里的 `gateway-socket-client`，可用命令包括：

- `PING`
- `GET_TIME`
- `GET_HOSTNAME`
- `PWD`
- `ECHO <text>`
- `LIST_FILES [relative_path]`
- `READ_TEXT <relative_path>`
- `HELP`

说明：

- 客户端当前不支持执行任意 shell 命令。
- `LIST_FILES` 和 `READ_TEXT` 仅允许访问客户端启动时配置的工作目录内文件。
