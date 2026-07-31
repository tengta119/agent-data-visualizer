from __future__ import annotations

import asyncio
from contextlib import suppress
import json
import logging

from .exceptions import ClientNotConnectedError, CommandTimeoutError
from .models import GatewayCommandEntity, GatewayResponseVO

logger = logging.getLogger(__name__)


class NettySocketServer:
    """A newline-delimited JSON socket server modeled after the Netty service."""

    def __init__(
        self,
        port: int,
        *,
        host: str = "0.0.0.0",
        timeout: float = 30.0,
        admin_host: str = "127.0.0.1",
        admin_port: int | None = None,
    ) -> None:
        self.host = host
        self.port = port
        self.timeout = timeout
        self.admin_host = admin_host
        self.admin_port = admin_port
        self.server: asyncio.AbstractServer | None = None
        self.admin_server: asyncio.AbstractServer | None = None
        self.client_writers: dict[str, asyncio.StreamWriter] = {}
        self.pending_responses: dict[str, asyncio.Future[GatewayResponseVO]] = {}

    async def start(self) -> None:
        if self.server is not None:
            return

        self.server = await asyncio.start_server(
            self._handle_client,
            host=self.host,
            port=self.port,
            limit=1024 * 1024 * 5,
        )

        sockets = self.server.sockets or []
        if sockets:
            self.port = int(sockets[0].getsockname()[1])

        if self.admin_port is None:
            self.admin_port = self.port + 1 if self.port else 0

        self.admin_server = await asyncio.start_server(
            self._handle_admin_client,
            host=self.admin_host,
            port=self.admin_port,
        )
        admin_sockets = self.admin_server.sockets or []
        if admin_sockets:
            self.admin_port = int(admin_sockets[0].getsockname()[1])

        logger.info("Netty Socket Server started on port: %s", self.port)
        logger.info(
            "Admin control server started on %s:%s",
            self.admin_host,
            self.admin_port,
        )

    async def stop(self) -> None:
        for future in self.pending_responses.values():
            if not future.done():
                future.cancel()
        self.pending_responses.clear()

        for writer in list(self.client_writers.values()):
            writer.close()
            with suppress(Exception):
                await writer.wait_closed()
        self.client_writers.clear()

        if self.server is not None:
            self.server.close()
            await self.server.wait_closed()
            self.server = None

        if self.admin_server is not None:
            self.admin_server.close()
            await self.admin_server.wait_closed()
            self.admin_server = None

    async def serve_forever(self) -> None:
        if self.server is None:
            raise RuntimeError("Server has not been started.")
        async with self.server:
            await self.server.serve_forever()

    async def send_command(self, command: GatewayCommandEntity) -> GatewayResponseVO:
        writer = self.client_writers.get(command.ip)
        if writer is None or writer.is_closing():
            raise ClientNotConnectedError(
                f"No active client connection found for IP {command.ip}."
            )

        loop = asyncio.get_running_loop()
        future: asyncio.Future[GatewayResponseVO] = loop.create_future()
        self.pending_responses[command.id] = future

        try:
            payload = f"{command.to_json()}\n".encode("utf-8")
            writer.write(payload)
            await writer.drain()
            logger.info("Sent command: %s", command.to_json())
            return await asyncio.wait_for(future, timeout=self.timeout)
        except asyncio.TimeoutError as exc:
            raise CommandTimeoutError(
                f"Command {command.id} timed out after {self.timeout} seconds."
            ) from exc
        finally:
            self.pending_responses.pop(command.id, None)

    def connected_clients(self) -> list[str]:
        return sorted(self.client_writers)

    async def _handle_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        peername = writer.get_extra_info("peername")
        client_ip = peername[0] if isinstance(peername, tuple) else str(peername)
        self.client_writers[client_ip] = writer
        logger.info("Client connected: %s", peername)

        try:
            while not reader.at_eof():
                data = await reader.readline()
                if not data:
                    break

                message = data.decode("utf-8").rstrip("\r\n")
                if not message:
                    continue

                logger.debug("Received message: %s", message)
                try:
                    response = GatewayResponseVO.from_json(message)
                except Exception:
                    logger.exception("Error parsing response")
                    continue

                future = self.pending_responses.get(response.id)
                if future is None:
                    logger.warning(
                        "Received response for unknown or expired request ID: %s",
                        response.id,
                    )
                    continue

                if not future.done():
                    future.set_result(response)
        except Exception:
            logger.exception("Connection error")
        finally:
            logger.info("Client disconnected: %s", peername)
            mapped_writer = self.client_writers.get(client_ip)
            if mapped_writer is writer:
                self.client_writers.pop(client_ip, None)
            writer.close()
            with suppress(Exception):
                await writer.wait_closed()

    async def _handle_admin_client(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        try:
            data = await reader.readline()
            message = data.decode("utf-8").rstrip("\r\n")
            if not message:
                return

            request = json.loads(message)
            action = request.get("action")

            if action == "list_clients":
                payload = {
                    "status": "success",
                    "action": action,
                    "clients": self.connected_clients(),
                    "count": len(self.client_writers),
                }
            elif action == "send_command":
                payload = await self._handle_send_command_request(request)
            else:
                payload = {
                    "status": "error",
                    "message": f"Unsupported admin action: {action!r}",
                }

            writer.write(f"{json.dumps(payload, ensure_ascii=False)}\n".encode("utf-8"))
            await writer.drain()
        except Exception:
            logger.exception("Admin control request failed")
        finally:
            writer.close()
            with suppress(Exception):
                await writer.wait_closed()

    async def _handle_send_command_request(self, request: dict[str, object]) -> dict[str, object]:
        command_name = request.get("command")
        client_ip = request.get("ip")

        if not isinstance(command_name, str) or not command_name.strip():
            return {
                "status": "error",
                "message": "Admin request field `command` must be a non-empty string.",
            }

        if not isinstance(client_ip, str) or not client_ip.strip():
            return {
                "status": "error",
                "message": "Admin request field `ip` must be a non-empty string.",
            }

        command = GatewayCommandEntity.build_command(command_name.strip(), client_ip.strip())

        try:
            response = await self.send_command(command)
        except (ClientNotConnectedError, CommandTimeoutError) as exc:
            return {
                "status": "error",
                "message": str(exc),
                "request": command.to_dict(),
            }

        return {
            "status": "success",
            "action": "send_command",
            "request": command.to_dict(),
            "response": response.to_dict(),
        }
