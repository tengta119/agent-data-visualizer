from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any, Self


@dataclass(slots=True)
class GatewayCommandEntity:
    id: str
    command: str
    host_string: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "command": self.command,
            "hostString": self.host_string,
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False)

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> Self:
        return cls(
            id=str(payload["id"]),
            command=str(payload["command"]),
            host_string=str(payload.get("hostString", payload.get("ip", ""))),
        )

    @classmethod
    def from_json(cls, payload: str) -> Self:
        return cls.from_dict(json.loads(payload))


@dataclass(slots=True)
class ImageVO:
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
    id: str
    status: str
    message: str | None = None
    image: ImageVO | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "status": self.status,
            "message": self.message,
            "image": self.image.to_dict() if self.image is not None else None,
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False)

    @classmethod
    def from_dict(cls, payload: dict[str, Any]) -> Self:
        image_payload = payload.get("image")
        return cls(
            id=str(payload["id"]),
            status=str(payload["status"]),
            message=payload.get("message"),
            image=ImageVO.from_dict(image_payload) if image_payload else None,
        )

    @classmethod
    def from_json(cls, payload: str) -> Self:
        return cls.from_dict(json.loads(payload))
