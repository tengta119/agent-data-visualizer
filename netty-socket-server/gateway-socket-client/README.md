## Gateway Socket Client

This project is a safe socket client for the gateway server.

Behavior:
- connects to the server on startup
- keeps reconnecting after disconnect
- executes only whitelisted local commands
- returns results as `GatewayResponseVO`

Supported commands:
- `PING`
- `GET_TIME`
- `GET_HOSTNAME`
- `PWD`
- `ECHO <text>`
- `LIST_FILES [relative_path]`
- `READ_TEXT <relative_path>`
- `HELP`

Example:

```powershell
python -m uv run gateway-socket-client --server-host 127.0.0.1 --server-port 9000
```
