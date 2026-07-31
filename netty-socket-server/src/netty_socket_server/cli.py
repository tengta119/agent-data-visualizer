from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import sys

from .server import NettySocketServer


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Gateway socket server CLI.")
    subparsers = parser.add_subparsers(dest="subcommand", required=True)

    serve_parser = subparsers.add_parser("serve", help="Start the socket server.")
    serve_parser.add_argument(
        "--host",
        default=os.getenv("GATEWAY_SOCKET_HOST", "0.0.0.0"),
        help="Socket bind host.",
    )
    serve_parser.add_argument(
        "--port",
        type=int,
        default=int(os.getenv("GATEWAY_SOCKET_PORT", "9000")),
        help="Socket bind port.",
    )
    serve_parser.add_argument(
        "--timeout",
        type=float,
        default=float(os.getenv("GATEWAY_SOCKET_TIMEOUT", "30")),
        help="Response timeout in seconds.",
    )
    serve_parser.add_argument(
        "--log-level",
        default=os.getenv("LOG_LEVEL", "INFO"),
        help="Logging level.",
    )
    serve_parser.add_argument(
        "--admin-host",
        default=os.getenv("GATEWAY_ADMIN_HOST", "127.0.0.1"),
        help="Local admin control host.",
    )
    serve_parser.add_argument(
        "--admin-port",
        type=int,
        default=int(os.getenv("GATEWAY_ADMIN_PORT", "9001")),
        help="Local admin control port.",
    )

    clients_parser = subparsers.add_parser(
        "clients",
        help="Query the running service for connected clients.",
    )
    clients_parser.add_argument(
        "--admin-host",
        default=os.getenv("GATEWAY_ADMIN_HOST", "127.0.0.1"),
        help="Local admin control host.",
    )
    clients_parser.add_argument(
        "--admin-port",
        type=int,
        default=int(os.getenv("GATEWAY_ADMIN_PORT", "9001")),
        help="Local admin control port.",
    )
    clients_parser.add_argument(
        "--json",
        action="store_true",
        help="Print the raw JSON response.",
    )

    send_parser = subparsers.add_parser(
        "send-command",
        help="Send a command to a specified connected client.",
    )
    send_parser.add_argument(
        "--admin-host",
        default=os.getenv("GATEWAY_ADMIN_HOST", "127.0.0.1"),
        help="Local admin control host.",
    )
    send_parser.add_argument(
        "--admin-port",
        type=int,
        default=int(os.getenv("GATEWAY_ADMIN_PORT", "9001")),
        help="Local admin control port.",
    )
    send_parser.add_argument(
        "--ip",
        required=True,
        help="Target client IP.",
    )
    send_parser.add_argument(
        "--command",
        required=True,
        help="Command text to send to the client.",
    )
    send_parser.add_argument(
        "--json",
        action="store_true",
        help="Print the raw JSON response.",
    )
    return parser


async def _run(args: argparse.Namespace) -> None:
    server = NettySocketServer(
        port=args.port,
        host=args.host,
        timeout=args.timeout,
        admin_host=args.admin_host,
        admin_port=args.admin_port,
    )
    await server.start()
    try:
        await server.serve_forever()
    finally:
        await server.stop()


async def _send_admin_request(
    host: str,
    port: int,
    payload: dict[str, object],
) -> dict[str, object]:
    reader, writer = await asyncio.open_connection(host, port)
    try:
        writer.write(f"{json.dumps(payload, ensure_ascii=False)}\n".encode("utf-8"))
        await writer.drain()

        response = await reader.readline()
        if not response:
            raise RuntimeError("Admin server closed the connection without a response.")
        return json.loads(response.decode("utf-8"))
    finally:
        writer.close()
        await writer.wait_closed()


async def _run_clients(args: argparse.Namespace) -> None:
    payload = await _send_admin_request(
        args.admin_host,
        args.admin_port,
        {"action": "list_clients"},
    )
    if payload.get("status") != "success":
        raise RuntimeError(str(payload.get("message", "Admin request failed.")))

    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return

    clients = payload.get("clients", [])
    print(f"targetIp: null")
    print(f"command: clients")
    print(f"responseStatus: success")
    print(f"responseMessage: {payload.get('count', 0)}")
    for client in clients:
        print(client)


async def _run_send_command(args: argparse.Namespace) -> None:
    payload = await _send_admin_request(
        args.admin_host,
        args.admin_port,
        {
            "action": "send_command",
            "ip": args.ip,
            "command": args.command,
        },
    )

    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return

    if payload.get("status") != "success":
        raise RuntimeError(str(payload.get("message", "Admin request failed.")))

    request_payload = payload.get("request", {})
    response_payload = payload.get("response", {})
    ##print(f"requestId: {request_payload.get('id', '')}")
    print(f"targetIp: {request_payload.get('ip', '')}")
    print(f"command: {request_payload.get('command', '')}")
    print(f"responseStatus: {response_payload.get('status', '')}")
    print(f"responseMessage: {response_payload.get('message', '')}")



def normalize_argv(argv: list[str]) -> list[str]:
    if not argv:
        return ["serve"]
    if argv[0] in {"serve", "clients", "send-command", "-h", "--help"}:
        return argv
    return ["serve", *argv]


def main() -> None:
    parser = build_parser()
    args = parser.parse_args(normalize_argv(sys.argv[1:]))

    logging.basicConfig(
        level=getattr(logging, str(getattr(args, "log_level", "INFO")).upper(), logging.INFO),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

    try:
        if args.subcommand == "clients":
            asyncio.run(_run_clients(args))
        elif args.subcommand == "send-command":
            asyncio.run(_run_send_command(args))
        else:
            asyncio.run(_run(args))
    except KeyboardInterrupt:
        logging.getLogger(__name__).info("Server stopped by user.")
