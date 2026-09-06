import unittest

from gateway_socket_client.command_runner import SafeCommandExecutor


class SafeCommandExecutorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.executor = object.__new__(SafeCommandExecutor)
        self.executor.allow_prefixes = SafeCommandExecutor.DEFAULT_ALLOW_PREFIXES

    def test_allows_read_only_command_prefix(self) -> None:
        self.assertTrue(self.executor.check_permission("git status --short"))

    def test_rejects_compound_and_interpreter_commands(self) -> None:
        for command in ("pwd && rm -rf /", "sh -c 'pwd'", "pwd > output.txt"):
            with self.subTest(command=command):
                with self.assertRaises(PermissionError):
                    self.executor.check_permission(command)

    def test_rejects_unlisted_command(self) -> None:
        with self.assertRaises(PermissionError):
            self.executor.check_permission("cat secret.txt")


if __name__ == "__main__":
    unittest.main()
