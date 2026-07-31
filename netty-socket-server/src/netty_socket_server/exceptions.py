class GatewayServerError(Exception):
    """Base exception for gateway socket server errors."""


class ClientNotConnectedError(GatewayServerError):
    """Raised when a command is sent to an IP without an active client."""


class CommandTimeoutError(GatewayServerError):
    """Raised when a client does not respond before the timeout."""
