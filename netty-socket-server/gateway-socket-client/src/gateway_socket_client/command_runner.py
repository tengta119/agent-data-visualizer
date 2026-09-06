from __future__ import annotations

import os
import shlex
import shutil
import subprocess
from pathlib import Path


class SafeCommandExecutor:
    DEFAULT_ALLOW_PREFIXES = (
        ("pwd",),
        ("ls",),
        ("dir",),
        ("whoami",),
        ("uname",),
        ("git", "status"),
        ("git", "diff"),
    )
    FORBIDDEN_EXECUTABLES = {
        "rm", "rmdir", "del", "erase", "format", "mkfs", "dd", "shutdown",
        "reboot", "halt", "poweroff", "kill", "pkill", "killall", "sudo", "su",
        "chmod", "chown", "setfacl", "mount", "umount", "iptables", "nft", "docker",
        "podman", "eval", "exec", "xargs", "python", "python3", "node", "perl",
        "ruby", "sh", "bash", "pwsh", "powershell", "cmd",
    }
    FORBIDDEN_ARGUMENTS = {
        "-c", "-command", "-e", "-exec", "restart", "stop", "disable", "run",
        "exec", "cp", "rm",
    }
    UNSUPPORTED_OPERATORS = ("|", ">", "<", "`", "$(", "${", "&", ";", "(", ")", "{", "}")

    def __init__(
        self,
        base_dir: str | Path | None = None,
        allow_prefixes: tuple[tuple[str, ...], ...] | None = None,
    ) -> None:
        self.base_dir = Path(base_dir or os.getcwd()).resolve()
        self.allow_prefixes = allow_prefixes or self.DEFAULT_ALLOW_PREFIXES
        self.shell_command = self._resolve_shell_command()

    def execute(self, raw_command: str) -> str:
        parts = self.check_permission(raw_command)
        completed = subprocess.run(
            self._build_shell_command(raw_command),
            cwd=self.base_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
            timeout=30,
        )
        if completed.returncode != 0:
            raise RuntimeError(completed.stdout.rstrip("\n") or f"Command exited with {completed.returncode}")
        return completed.stdout.rstrip("\n")

    def check_permission(self, raw_command: str) -> list[str]:
        if not raw_command or not raw_command.strip():
            raise ValueError("Command cannot be empty.")
        if len(raw_command) > 1000 or "\x00" in raw_command or "\r" in raw_command or "\n" in raw_command:
            raise PermissionError("Command has an invalid structure.")
        if self._contains_unsupported_operator(raw_command):
            raise PermissionError("Compound commands and shell control operators are forbidden.")

        try:
            parts = shlex.split(raw_command, posix=os.name != "nt")
        except ValueError as exc:
            raise PermissionError("Command cannot be parsed safely.") from exc
        if not parts:
            raise ValueError("Command cannot be empty.")

        executable = Path(parts[0].replace("\\", "/")).name.lower()
        if executable in self.FORBIDDEN_EXECUTABLES:
            raise PermissionError(f"Command {executable} is forbidden.")
        lowered = tuple(part.lower() for part in parts)
        if any(argument in self.FORBIDDEN_ARGUMENTS for argument in lowered[1:]):
            raise PermissionError("Command contains a forbidden argument.")
        if not any(lowered[:len(prefix)] == prefix for prefix in self.allow_prefixes):
            raise PermissionError("Command does not match an allow rule.")
        return parts

    def _contains_unsupported_operator(self, command: str) -> bool:
        quote: str | None = None
        escaped = False
        index = 0
        while index < len(command):
            char = command[index]
            if escaped:
                escaped = False
                index += 1
                continue
            if char == "\\" and quote != "'":
                escaped = True
                index += 1
                continue
            if char in ("'", '"'):
                quote = char if quote is None else None if quote == char else quote
                index += 1
                continue
            if quote is None and any(command.startswith(operator, index) for operator in self.UNSUPPORTED_OPERATORS):
                return True
            index += 1
        return quote is not None or escaped

    @staticmethod
    def _resolve_shell_command() -> list[str]:
        if os.name == "nt":
            candidates = (os.getenv("GATEWAY_CLIENT_SHELL"), "pwsh.exe", "pwsh", "powershell.exe")
        else:
            candidates = (os.getenv("GATEWAY_CLIENT_SHELL"), os.getenv("SHELL"), "fish", "bash", "sh")
        for candidate in candidates:
            if not candidate:
                continue
            command = shlex.split(candidate, posix=os.name != "nt")
            executable = shutil.which(command[0])
            if executable:
                return [executable, *command[1:]]
        raise FileNotFoundError("No supported shell found. Set GATEWAY_CLIENT_SHELL to a valid shell path.")

    def _build_shell_command(self, raw_command: str) -> list[str]:
        shell_name = Path(self.shell_command[0]).name.lower()
        if os.name == "nt" or "pwsh" in shell_name or "powershell" in shell_name:
            return [*self.shell_command, "-NoLogo", "-NoProfile", "-Command", raw_command]
        return [*self.shell_command, "-c", raw_command]

    def close(self) -> None:
        return None
