"""Network access to hosts a tenant chose (stdlib only).

A collector that connects to an address the tenant typed (a self-managed server, the organisation's own domain)
must go through this module, never through urllib or http.client directly:

- [public_address] resolves the host once and refuses it unless every address is a public internet address, so a
  tenant cannot point the platform at its own network (SSRF);
- the connection functions take that checked address and connect to it directly, with TLS verified against the
  host name, so a second DNS answer cannot move the request somewhere else (DNS rebinding);
- redirects are never followed, bodies are capped and every exchange has a total deadline (a host that trickles
  its answer cannot hold a worker): what comes back is untrusted data.

Network failures raise [TransientError] (the core retries the job). Answers that would be the same on a retry raise
[CollectorError]: `host_not_allowed` (not a public address), `host_not_found` (the name does not exist) and
`response_too_large`.
"""
from __future__ import annotations

import http.client
import ipaddress
import socket
import ssl
import threading
from dataclasses import dataclass, field
from typing import Any, Callable, Mapping

from alinha_job_consumer.collectors import CollectorError, TransientError

TIMEOUT_S = 10.0  # each socket operation
# The whole exchange (connect, TLS, request, answer) by default: twice the per-operation timeout.
DEADLINE_FACTOR = 2
# Larger answers are refused: collected data is untrusted and the job result is capped at 1 MiB.
MAX_BODY_BYTES = 2 * 1024 * 1024

Resolve = Callable[..., Any]


@dataclass(frozen=True)
class Response:
    status: int
    headers: Mapping[str, str]  # names in lower case; a repeated header keeps its last value
    body: bytes = b""


@dataclass(frozen=True)
class TlsInfo:
    version: str  # "TLSv1.3", "TLSv1.2", ...
    peercert: dict[str, Any] = field(default_factory=dict)  # empty when the context did not verify the certificate


def user_agent(provider: str) -> str:
    """Identifies the platform to the owner of the host, so the requests are not mistaken for an attack."""
    return f"aLinha-{provider}/1.0"


# -- addresses ----------------------------------------------------------------------------------------------------

# NAT64 prefixes count as global to `ipaddress`, but a NAT64 gateway would turn them into any IPv4 address.
_TRANSLATED = (ipaddress.ip_network("64:ff9b::/96"), ipaddress.ip_network("64:ff9b:1::/48"))


_NOT_FOUND = {code for code in (getattr(socket, "EAI_NONAME", None), getattr(socket, "EAI_NODATA", None))
              if code is not None}


def is_public(address: str) -> bool:
    ip = ipaddress.ip_address(address.split("%", 1)[0])
    if isinstance(ip, ipaddress.IPv6Address):
        if ip.ipv4_mapped is not None:
            ip = ip.ipv4_mapped
        elif any(ip in net for net in _TRANSLATED):
            return False
    return ip.is_global


def public_address(host: str, port: int, resolve: Resolve = socket.getaddrinfo) -> str:
    """Resolves [host] once and refuses it unless EVERY address is public. Prefers an IPv4 address: containers
    often have no IPv6 route."""
    try:
        infos = resolve(host, port, type=socket.SOCK_STREAM)
    except socket.gaierror as error:
        if error.errno in _NOT_FOUND:  # the name does not exist: retrying gives the same answer
            raise CollectorError("host_not_found", "the host name does not exist") from error
        raise TransientError("host does not resolve") from error
    except OSError as error:
        raise TransientError("host does not resolve") from error
    addresses = [info[4][0] for info in infos]
    if not addresses or not all(is_public(a) for a in addresses):
        raise CollectorError("host_not_allowed", "the address is not a public internet address")
    return next((a for a in addresses if ":" not in a), addresses[0])


# -- connections --------------------------------------------------------------------------------------------------

class _Watchdog:
    """Shuts [sock] down once [seconds] have passed, which ends any read or handshake still waiting on it.

    The socket timeout only bounds each operation: a host sending one byte at a time would otherwise keep the
    exchange going for as long as it likes."""

    def __init__(self, sock: socket.socket, seconds: float) -> None:
        # The descriptor, not the object: wrap_socket detaches [sock] and goes on with the same descriptor.
        self._fd = sock.fileno()
        self._family, self._type = sock.family, sock.type
        self.fired = False
        self._done = False
        self._lock = threading.Lock()
        self._timer = threading.Timer(seconds, self._fire)
        self._timer.daemon = True
        self._timer.start()

    def _fire(self) -> None:
        with self._lock:  # never after cancel(), which runs before the descriptor is closed and maybe reused
            if self._done:
                return
            self.fired = True
            try:
                # shutdown acts on the connection, so a duplicate of the descriptor ends reads on the original too
                with socket.fromfd(self._fd, self._family, self._type) as duplicate:
                    duplicate.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass  # already gone

    def cancel(self) -> None:
        """Call before the socket is closed."""
        with self._lock:
            self._done = True
        self._timer.cancel()


def _deadline(timeout: float, deadline: float | None) -> float:
    return deadline if deadline is not None else timeout * DEADLINE_FACTOR


class _PinnedConnectionMixin:
    """Connects to an address already checked by [public_address] and arms the total deadline on that socket."""

    _address: str
    _deadline_s: float
    _watchdog: _Watchdog | None = None

    def _open(self, port: int, timeout: float) -> socket.socket:
        sock = socket.create_connection((self._address, port), timeout)
        self._watchdog = _Watchdog(sock, self._deadline_s)
        return sock

    @property
    def expired(self) -> bool:
        return self._watchdog is not None and self._watchdog.fired

    def _disarm(self) -> None:
        if self._watchdog is not None:
            self._watchdog.cancel()


class PinnedHTTPSConnection(_PinnedConnectionMixin, http.client.HTTPSConnection):
    """https to a checked address, with TLS verified against the host name and a total deadline."""

    def __init__(self, host: str, port: int, address: str, timeout: float,
                 context: ssl.SSLContext | None = None, deadline: float | None = None) -> None:
        super().__init__(host, port, timeout=timeout, context=context)
        self._address = address
        self._deadline_s = _deadline(timeout, deadline)

    def connect(self) -> None:
        self.sock = self._context.wrap_socket(self._open(self.port, self.timeout), server_hostname=self.host)

    def close(self) -> None:
        self._disarm()
        super().close()


class _PinnedHTTPConnection(_PinnedConnectionMixin, http.client.HTTPConnection):
    def __init__(self, host: str, port: int, address: str, timeout: float, deadline: float | None = None) -> None:
        super().__init__(host, port, timeout=timeout)
        self._address = address
        self._deadline_s = _deadline(timeout, deadline)

    def connect(self) -> None:
        self.sock = self._open(self.port, self.timeout)

    def close(self) -> None:
        self._disarm()
        super().close()


def read_capped(answer: Any, limit: int = MAX_BODY_BYTES) -> bytes:
    body = answer.read(limit + 1)
    if len(body) > limit:
        # The host would send the same next time: a retry cannot help.
        raise CollectorError("response_too_large", "the answer is larger than allowed")
    return body


def _headers(items: Any) -> dict[str, str]:
    return {key.lower(): value for key, value in items}


def pinned_https_get(host: str, address: str, path: str, headers: Mapping[str, str], *, port: int = 443,
                     timeout: float = TIMEOUT_S, max_bytes: int = MAX_BODY_BYTES,
                     context: ssl.SSLContext | None = None, deadline: float | None = None) -> Response:
    """One GET over https to [address], never redirected. The body is read only for a 200 answer, and never with
    `max_bytes=0` (only the status and headers matter). [deadline] bounds the whole exchange (default: twice
    [timeout]); past it the call raises TransientError."""
    if not path.startswith("/"):
        raise ValueError("path must start with /")
    connection = PinnedHTTPSConnection(host, port, address, timeout, context, deadline)
    try:
        connection.request("GET", path, headers=dict(headers))
        answer = connection.getresponse()  # http.client never follows redirects
        body = read_capped(answer, max_bytes) if answer.status == 200 and max_bytes > 0 else b""
        if connection.expired:  # a read cut short by the deadline can look complete
            raise TransientError("answer too slow")
        return Response(answer.status, _headers(answer.getheaders()), body)
    except ssl.SSLError as error:
        if connection.expired:
            raise TransientError("answer too slow") from error
        raise  # the caller decides what an invalid certificate means
    except (OSError, http.client.HTTPException) as error:
        raise TransientError("answer too slow" if connection.expired else "host unreachable") from error
    finally:
        connection.close()


def pinned_http_location(host: str, address: str, path: str = "/", *, port: int = 80,
                         timeout: float = TIMEOUT_S, headers: Mapping[str, str] | None = None,
                         deadline: float | None = None) -> tuple[int, str | None]:
    """One GET over plain http, to see where it sends visitors: returns (status, Location). The body is never read.

    A refused connection raises ConnectionRefusedError, so the caller can tell "port 80 is closed" apart from
    other network failures (TransientError)."""
    if not path.startswith("/"):
        raise ValueError("path must start with /")
    connection = _PinnedHTTPConnection(host, port, address, timeout, deadline)
    try:
        connection.request("GET", path, headers=dict(headers or {}))
        answer = connection.getresponse()
        if connection.expired:
            raise TransientError("answer too slow")
        return answer.status, answer.getheader("Location")
    except ConnectionRefusedError:
        raise
    except (OSError, http.client.HTTPException) as error:
        raise TransientError("answer too slow" if connection.expired else "host unreachable") from error
    finally:
        connection.close()


def tls_handshake(host: str, address: str, context: ssl.SSLContext, *, port: int = 443,
                  timeout: float = TIMEOUT_S, deadline: float | None = None) -> TlsInfo:
    """Opens a TLS session to [address] with SNI [host], sends nothing and closes it.

    ssl.SSLError (including SSLCertVerificationError) is raised as it is: for a collector a refused handshake or
    an invalid certificate is a finding, not a network failure. Other network errors raise TransientError."""
    watchdog: _Watchdog | None = None
    try:
        sock = socket.create_connection((address, port), timeout)
        watchdog = _Watchdog(sock, _deadline(timeout, deadline))
        try:
            tls = context.wrap_socket(sock, server_hostname=host)
            try:
                return TlsInfo(tls.version() or "", tls.getpeercert() or {})
            finally:
                watchdog.cancel()
                tls.close()
        finally:
            watchdog.cancel()  # before closing the descriptor (a no-op when already cancelled)
            sock.close()
    except ssl.SSLError as error:
        if watchdog is not None and watchdog.fired:
            raise TransientError("handshake too slow") from error
        raise
    except OSError as error:
        raise TransientError("handshake too slow" if watchdog and watchdog.fired else "host unreachable") from error
