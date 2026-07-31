from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any, Self
from uuid import uuid4


@dataclass(slots=True)
class GatewayCommandEntity:
    """Command sent from the gateway service to a connected client."""

    id: str
    command: str
    ip: str

    @classmethod
    def build_command(cls, command: str, ip: str) -> Self:
        return cls(id=str(uuid4()), command=command, ip=ip)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "command": self.command,
            "ip": self.ip,
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False)

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> Self:
        return cls(
            id=str(payload["id"]),
            command=str(payload["command"]),
            ip=str(payload["ip"]),
        )

    @classmethod
    def from_json(cls, payload: str) -> Self:
        return cls.from_dict(json.loads(payload))


@dataclass(slots=True)
class ImageVO:
    """Image payload returned by the client."""

    data: str | None = None
    screen_width: int | None = None
    screen_height: int | None = None
    screen_orientation: int | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "data": self.data,
            "screenWidth": self.screen_width,
            "screenHeight": self.screen_height,
            "screenOrientation": self.screen_orientation,
        }

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> Self:
        return cls(
            data=payload.get("data"),
            screen_width=payload.get("screenWidth", payload.get("screen_width")),
            screen_height=payload.get("screenHeight", payload.get("screen_height")),
            screen_orientation=payload.get(
                "screenOrientation",
                payload.get("screen_orientation"),
            ),
        )


@dataclass(slots=True)
class GatewayResponseVO:
    """Response returned by the client for a command."""

    id: str
    status: str
    message: str | None = None

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "id": self.id,
            "status": self.status,
            "message": self.message
        }
        return payload

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False)

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> Self:
        return cls(
            id=str(payload["id"]),
            status=str(payload["status"]),
            message=payload.get("message")
        )

    @classmethod
    def from_json(cls, payload: str) -> Self:
        return cls.from_dict(json.loads(payload))
