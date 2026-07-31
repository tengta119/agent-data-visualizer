import unittest

from netty_socket_server.cli import build_parser


class CliParserTestCase(unittest.TestCase):
    def test_clients_subcommand_has_admin_host_default(self) -> None:
        parser = build_parser()

        args = parser.parse_args(["clients"])

        self.assertEqual(args.subcommand, "clients")
        self.assertEqual(args.admin_host, "127.0.0.1")
        self.assertEqual(args.admin_port, 9001)
