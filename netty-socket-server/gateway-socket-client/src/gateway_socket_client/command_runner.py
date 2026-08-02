from __future__ import annotations

import os
import shlex
import shutil
import subprocess
from pathlib import Path


class SafeCommandExecutor:

    def __init__(self, base_dir: str | Path | None = None) -> None:

        self.base_dir = Path(base_dir or os.getcwd()).resolve()
        self.shell_command = self._resolve_shell_command()

    def execute(self, raw_command: str) -> str:
        parts = shlex.split(raw_command, posix=os.name != "nt")

        if not parts:
            raise ValueError(
                "Command cannot be empty."
            )

        action = parts[0].upper()
        args = parts[1:]

        self.check_permission(action, args)

        completed = subprocess.run(
            self._build_shell_command(raw_command),
            cwd=self.base_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )

        return completed.stdout.rstrip("\n")

    def check_permission(self, action: str, args: list[str]):
        blacklist = {
            "RM",
            "MKFS",
            "DD",
            "SHUTDOWN",
            "REBOOT"
        }

        if action in blacklist:
            raise PermissionError(
                f"Command {action} is forbidden"
            )

    @staticmethod
    def _resolve_shell_command() -> list[str]:
        if os.name == "nt":
            candidates = (
                os.getenv("GATEWAY_CLIENT_SHELL"),
                "pwsh.exe",
                "pwsh",
                "powershell.exe",
            )
        else:
            candidates = (
                os.getenv("GATEWAY_CLIENT_SHELL"),
                os.getenv("SHELL"),
                "fish",
                "bash",
                "sh",
            )

        for candidate in candidates:
            if not candidate:
                continue

            command = shlex.split(candidate, posix=os.name != "nt")
            executable = shutil.which(command[0])
            if executable:
                return [executable, *command[1:]]

        raise FileNotFoundError(
            "No supported shell found. Set GATEWAY_CLIENT_SHELL to a valid shell path."
        )

    def _build_shell_command(self, raw_command: str) -> list[str]:
        shell_name = Path(self.shell_command[0]).name.lower()
        if os.name == "nt" or "pwsh" in shell_name or "powershell" in shell_name:
            return [
                *self.shell_command,
                "-NoLogo",
                "-NoProfile",
                "-Command",
                raw_command,
            ]

        return [
            *self.shell_command,
            "-c",
            raw_command,
        ]

    def close(self):
        return None
