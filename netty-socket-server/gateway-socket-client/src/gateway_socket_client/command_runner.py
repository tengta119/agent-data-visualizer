from __future__ import annotations

import os
import shlex
import subprocess
import uuid
from pathlib import Path


class SafeCommandExecutor:

    def __init__(self, base_dir: str | Path | None = None) -> None:

        self.base_dir = Path(base_dir or os.getcwd()).resolve()

        # 启动持久 shell
        self.process = subprocess.Popen(
            ["pwsh.exe"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            cwd=self.base_dir,
            bufsize=1
        )

    def execute(self, raw_command: str) -> str:
        parts = shlex.split(raw_command, posix=False)

        if not parts:
            raise ValueError(
                "Command cannot be empty."
            )

        action = parts[0].upper()
        args = parts[1:]

        self.check_permission(action, args)

        marker = (
            f"__COMMAND_END_"
            f"{uuid.uuid4().hex}__"
        )
        command = (
            raw_command
            + "\n"
            + f"echo {marker}"
            + "\n"
        )

        self.process.stdin.write(command)
        self.process.stdin.flush()

        output = []

        while True:
            line = (self.process.stdout.readline())
            if not line:
                break

            line = line.rstrip("\n")
            if line == marker:
                break

            output.append(line)

        return "\n".join(output)



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

    def close(self):
        if self.process:
            self.process.stdin.write(
                "exit\n"
            )
            self.process.stdin.flush()
            self.process.wait()


