from __future__ import annotations

import asyncio
from contextlib import suppress
import logging

from .command_runner import SafeCommandExecutor
from .models import GatewayCommandEntity, GatewayResponseVO

logger = logging.getLogger(__name__)


class GatewaySocketClient:
    def __init__(
        self,
        host: str,
        port: int,
        *,
        reconnect_delay: float = 3.0,
        executor: SafeCommandExecutor | None = None,
    ) -> None:
        self.host = host
        self.port = port
        self.reconnect_delay = reconnect_delay
        self.executor = executor or SafeCommandExecutor()
        self._running = True
        self._writer: asyncio.StreamWriter | None = None

    async def run_forever(self) -> None:
        while self._running:
            try:
                logger.info("Connecting to server %s:%s", self.host, self.port)
                reader, writer = await asyncio.open_connection(self.host, self.port)
                self._writer = writer
                logger.info("Connected to server %s:%s", self.host, self.port)
                await self._listen(reader, writer)
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Client connection failed")
            finally:
                if self._writer is not None:
                    self._writer.close()
                    with suppress(Exception):
                        await self._writer.wait_closed()
                    self._writer = None

            if self._running:
                logger.info("Reconnecting in %s seconds", self.reconnect_delay)
                await asyncio.sleep(self.reconnect_delay)

    async def stop(self) -> None:
        self._running = False
        if self._writer is not None:
            self._writer.close()
            with suppress(Exception):
                await self._writer.wait_closed()
            self._writer = None

    async def _listen(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        while not reader.at_eof() and self._running:
            data = await reader.readline()
            if not data:
                break

            message = data.decode("utf-8").rstrip("\r\n")
            if not message:
                continue

            command = GatewayCommandEntity.from_json(message)
            logger.info(
                "Received command %s for host %s",
                command.command,
                command.host_string,
            )
            response = self._handle_command(command)
            logger.info("Command %s finished with status %s", command.id, response.status)

            writer.write(f"{response.to_json()}\n".encode("utf-8"))
            await writer.drain()

    def _handle_command(self, command: GatewayCommandEntity) -> GatewayResponseVO:
        try:
            result = self.executor.execute(command.command)
            return GatewayResponseVO(
                id=command.id,
                status="success",
                message=result,
            )
        except Exception as exc:
            return GatewayResponseVO(
                id=command.id,
                status="error",
                message=str(exc),
            )
