from __future__ import annotations

import argparse
import asyncio
import logging
import os

from .client import GatewaySocketClient
from .command_runner import SafeCommandExecutor


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Safe gateway socket client that connects to the server and runs whitelisted commands."
    )
    parser.add_argument(
        "--server-host",
        default=os.getenv("GATEWAY_SERVER_HOST", "127.0.0.1"),
        help="Gateway socket server host.",
    )
    parser.add_argument(
        "--server-port",
        type=int,
        default=int(os.getenv("GATEWAY_SERVER_PORT", "9000")),
        help="Gateway socket server port.",
    )
    parser.add_argument(
        "--retry-delay",
        type=float,
        default=float(os.getenv("GATEWAY_RETRY_DELAY", "3")),
        help="Reconnect delay in seconds after disconnect.",
    )
    parser.add_argument(
        "--working-dir",
        default=os.getenv("GATEWAY_CLIENT_WORKDIR", os.getcwd()),
        help="Allowed working directory for safe file commands.",
    )
    parser.add_argument(
        "--log-level",
        default=os.getenv("LOG_LEVEL", "INFO"),
        help="Logging level.",
    )
    return parser


async def _run(args: argparse.Namespace) -> None:
    client = GatewaySocketClient(
        args.server_host,
        args.server_port,
        reconnect_delay=args.retry_delay,
        executor=SafeCommandExecutor(args.working_dir),
    )
    await client.run_forever()


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, str(args.log_level).upper(), logging.INFO),
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )

    try:
        asyncio.run(_run(args))
    except KeyboardInterrupt:
        logging.getLogger(__name__).info("Client stopped by user.")
