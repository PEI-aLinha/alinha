"""Network access to hosts a tenant chose (stdlib only).

A collector that connects to an address the tenant typed (a self-managed server, the organisation's own domain)
must go through this module, never through urllib or http.client directly:

- [public_addresses] resolves the host once and refuses it unless every address is a public internet address, so a
  tenant cannot point the platform at its own network (SSRF);
- the connection functions take those checked addresses and connect to them directly, one after the other until one
  answers, with TLS verified against the host name, so a second DNS answer cannot move the request somewhere else
  (DNS rebinding);
- redirects are never followed, bodies are capped and every exchange has a total deadline (a host that trickles
  its answer cannot hold a worker): what comes back is untrusted data.

Time limits: each connection attempt takes at most min(timeout, time left), and the attempts on every address
together at most `deadline`; from the moment the connection is open, the TLS handshake, request and answer together
take at most `deadline`. The DNS lookup in [public_addresses] uses the system
resolver's own timeouts: a collector that must finish in a fixed time puts its own budget around the whole run.

Network failures raise [TransientError] (the core retries the job), and so do TLS errors after the handshake (a
connection reset is not an invalid certificate). Answers that would be the same on a retry raise
[CollectorError]: `host_not_allowed` (not a public address), `host_not_found` (the name does not exist) and
`response_too_large`.
"""
from __future__ import annotations

import http.client
import ipaddress
import socket
import ssl
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Mapping, Sequence

from alinha_job_consumer.collectors import CollectorError, TransientError

TIMEOUT_S = 10.0  # each socket operation
# Once connected, the TLS handshake, request and answer together: by default twice the per-operation timeout.
DEADLINE_FACTOR = 2
# Larger answers are refused: collected data is untrusted. Collectors keep only what they need from a body, so
# their job result stays under its own 1 MiB cap (docs/jobs.md).
MAX_BODY_BYTES = 2 * 1024 * 1024

Resolve = Callable[..., Any]
# One address checked by [public_addresses], or all of them in the order to try.
Address = str | Sequence[str]


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

# Prefixes that `ipaddress` counts as global although they embed an IPv4 address that something on the path may
# reach: NAT64 (a gateway turns them into any IPv4 address), IPv4-compatible `::/96` (deprecated; routed to the IPv4
# address on a host with a `sit` tunnel up) and IPv4-translated `::ffff:0:0:0/96` (SIIT).
_EMBEDS_IPV4 = tuple(ipaddress.ip_network(n) for n in ("64:ff9b::/96", "64:ff9b:1::/48", "::/96", "::ffff:0:0:0/96"))


_NOT_FOUND = {code for code in (getattr(socket, "EAI_NONAME", None), getattr(socket, "EAI_NODATA", None))
              if code is not None}


def is_public(address: str) -> bool:
    ip = ipaddress.ip_address(address.split("%", 1)[0])
    if ip.is_multicast:  # some multicast scopes count as global, but they never name one web server
        return False
    if isinstance(ip, ipaddress.IPv6Address):
        if ip.ipv4_mapped is not None:
            return is_public(str(ip.ipv4_mapped))
        # 6to4 (2002::/16) and Teredo (2001::/32) carry an IPv4 address a relay may reach. Both are deprecated, and
        # whether `ipaddress` counts them as global depends on the Python version, so they are refused outright.
        if ip.is_site_local or ip.sixtofour is not None or ip.teredo is not None:
            return False
        if any(ip in net for net in _EMBEDS_IPV4):
            return False
    return ip.is_global


def public_addresses(host: str, port: int, resolve: Resolve = socket.getaddrinfo) -> tuple[str, ...]:
    """Resolves [host] once and refuses it unless EVERY address is public. Returns them all, without repeats and
    IPv4 first (containers often have no IPv6 route), so a connection can fall back to the next one when an address
    does not answer. The lookup uses the system resolver's timeouts, not [TIMEOUT_S]."""
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
    unique = list(dict.fromkeys(addresses))
    return tuple(sorted(unique, key=lambda a: ":" in a))  # stable: keeps the resolver's order within a family


def public_address(host: str, port: int, resolve: Resolve = socket.getaddrinfo) -> str:
    """The first of [public_addresses]: for a caller that connects once and does not need a fallback."""
    return public_addresses(host, port, resolve)[0]


# -- connections --------------------------------------------------------------------------------------------------

class _Watchdog:
    """Shuts the connection down once [seconds] have passed, which ends any read or handshake still waiting on it.

    The socket timeout only bounds each operation: a host sending one byte at a time would otherwise keep the
    exchange going for as long as it likes.

    It keeps its own duplicate of the descriptor until [cancel]: whoever closes the original (wrap_socket after a
    failed handshake, http.client after an answer with `Connection: close`), the number cannot be reused by another
    socket while the watchdog may still shut it down. Always call [cancel] when the exchange is over."""

    def __init__(self, sock: socket.socket, seconds: float) -> None:
        self._duplicate: socket.socket | None = sock.dup()
        self.fired = False
        self._lock = threading.Lock()
        self._timer = threading.Timer(seconds, self._fire)
        self._timer.daemon = True
        self._timer.start()

    def _fire(self) -> None:
        with self._lock:
            if self._duplicate is None:
                return
            self.fired = True
            try:
                # shutdown acts on the connection, so the duplicate ends reads on the original too
                self._duplicate.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass  # already gone
            self._release()

    def cancel(self) -> None:
        self._timer.cancel()
        with self._lock:
            self._release()

    def _release(self) -> None:
        if self._duplicate is not None:
            self._duplicate.close()
            self._duplicate = None


def _deadline(timeout: float, deadline: float | None) -> float:
    return deadline if deadline is not None else timeout * DEADLINE_FACTOR


def _connect(address: Address, port: int, timeout: float, limit: float) -> socket.socket:
    """Connects to the first of [address] that answers, in order. Each attempt takes at most min(timeout, time left),
    all of them together at most [limit]. Only addresses already checked by [public_addresses] may be passed: no
    name is resolved here.

    When every attempt fails, raises the last error, and ConnectionRefusedError only when every address refused (a
    closed port, not a host that is down)."""
    candidates = (address,) if isinstance(address, str) else tuple(address)
    if not candidates:
        raise ValueError("no address to connect to")
    ends = time.monotonic() + limit
    errors: list[OSError] = []
    for candidate in candidates:
        left = ends - time.monotonic()
        if left <= 0:
            break
        try:
            return socket.create_connection((candidate, port), min(timeout, left))
        except OSError as error:
            errors.append(error)
    if not errors:
        raise TimeoutError("no time left to connect")
    other = [error for error in errors if not isinstance(error, ConnectionRefusedError)]
    raise (other or errors)[-1]


def _handshake(context: ssl.SSLContext, sock: socket.socket, host: str) -> ssl.SSLSocket:
    """TLS over [sock]. A failed handshake raises [_HandshakeError] wrapping the ssl.SSLError, so callers can tell it
    apart from TLS errors later in the exchange (a reset, an unexpected end of the stream)."""
    try:
        return context.wrap_socket(sock, server_hostname=host)
    except ssl.SSLError as error:
        raise _HandshakeError(error) from error


class _HandshakeError(Exception):
    def __init__(self, error: ssl.SSLError) -> None:
        super().__init__(str(error))
        self.error = error


class _PinnedConnectionMixin:
    """Connects to addresses already checked by [public_addresses] and arms the total deadline on that socket.
    The deadline lasts until [disarm], not until close(): http.client closes the connection before the body of a
    `Connection: close` answer is read."""

    _address: Address
    _deadline_s: float
    _watchdog: _Watchdog | None = None

    def _open(self, port: int, timeout: float) -> socket.socket:
        sock = _connect(self._address, port, timeout, self._deadline_s)
        self._watchdog = _Watchdog(sock, self._deadline_s)
        return sock

    @property
    def expired(self) -> bool:
        return self._watchdog is not None and self._watchdog.fired

    def disarm(self) -> None:
        if self._watchdog is not None:
            self._watchdog.cancel()


class PinnedHTTPSConnection(_PinnedConnectionMixin, http.client.HTTPSConnection):
    """https to a checked address, with TLS verified against the host name and a total deadline."""

    def __init__(self, host: str, port: int, address: Address, timeout: float,
                 context: ssl.SSLContext | None = None, deadline: float | None = None) -> None:
        super().__init__(host, port, timeout=timeout, context=context)
        self._address = address
        self._deadline_s = _deadline(timeout, deadline)

    def connect(self) -> None:
        self.sock = _handshake(self._context, self._open(self.port, self.timeout), self.host)


class _PinnedHTTPConnection(_PinnedConnectionMixin, http.client.HTTPConnection):
    def __init__(self, host: str, port: int, address: Address, timeout: float, deadline: float | None = None) -> None:
        super().__init__(host, port, timeout=timeout)
        self._address = address
        self._deadline_s = _deadline(timeout, deadline)

    def connect(self) -> None:
        self.sock = self._open(self.port, self.timeout)


def read_capped(answer: Any, limit: int = MAX_BODY_BYTES) -> bytes:
    body = answer.read(limit + 1)
    if len(body) > limit:
        # The host would send the same next time: a retry cannot help.
        raise CollectorError("response_too_large", "the answer is larger than allowed")
    return body


def _headers(items: Any) -> dict[str, str]:
    return {key.lower(): value for key, value in items}


def pinned_https_get(host: str, address: Address, path: str, headers: Mapping[str, str], *, port: int = 443,
                     timeout: float = TIMEOUT_S, max_bytes: int = MAX_BODY_BYTES,
                     context: ssl.SSLContext | None = None, deadline: float | None = None) -> Response:
    """One GET over https to [address], never redirected. The body is read only for a 200 answer, and never with
    `max_bytes=0` (only the status and headers matter). [deadline] bounds the TLS handshake, request and answer
    once connected (default: twice [timeout]); past it the call raises TransientError."""
    if not path.startswith("/"):
        raise ValueError("path must start with /")
    connection = PinnedHTTPSConnection(host, port, address, timeout, context, deadline)
    answer: http.client.HTTPResponse | None = None
    try:
        connection.request("GET", path, headers=dict(headers))
        answer = connection.getresponse()  # http.client never follows redirects
        body = read_capped(answer, max_bytes) if answer.status == 200 and max_bytes > 0 else b""
        if connection.expired:  # a read cut short by the deadline can look complete
            raise TransientError("answer too slow")
        return Response(answer.status, _headers(answer.getheaders()), body)
    except _HandshakeError as error:
        if connection.expired:
            raise TransientError("answer too slow") from error.error
        raise error.error from None  # the caller decides what an invalid certificate means
    except (OSError, http.client.HTTPException) as error:  # includes TLS errors after the handshake
        raise TransientError("answer too slow" if connection.expired else "host unreachable") from error
    finally:
        connection.disarm()
        if answer is not None:
            answer.close()
        connection.close()


def pinned_http_location(host: str, address: Address, path: str = "/", *, port: int = 80,
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
        try:
            if connection.expired:
                raise TransientError("answer too slow")
            return answer.status, answer.getheader("Location")
        finally:
            answer.close()
    except ConnectionRefusedError:
        raise
    except (OSError, http.client.HTTPException) as error:
        raise TransientError("answer too slow" if connection.expired else "host unreachable") from error
    finally:
        connection.disarm()
        connection.close()


def tls_handshake(host: str, address: Address, context: ssl.SSLContext, *, port: int = 443,
                  timeout: float = TIMEOUT_S, deadline: float | None = None) -> TlsInfo:
    """Opens a TLS session to [address] with SNI [host], sends nothing and closes it.

    A failed handshake raises its ssl.SSLError (including SSLCertVerificationError) as it is: for a collector a
    refused handshake or an invalid certificate is a finding, not a network failure. Other network errors raise
    TransientError."""
    watchdog: _Watchdog | None = None
    try:
        limit = _deadline(timeout, deadline)
        with _connect(address, port, timeout, limit) as sock:
            watchdog = _Watchdog(sock, limit)
            with _handshake(context, sock, host) as tls:
                return TlsInfo(tls.version() or "", tls.getpeercert() or {})
    except _HandshakeError as error:
        if watchdog is not None and watchdog.fired:
            raise TransientError("handshake too slow") from error.error
        raise error.error from None
    except OSError as error:
        raise TransientError("handshake too slow" if watchdog and watchdog.fired else "host unreachable") from error
    finally:
        if watchdog is not None:
            watchdog.cancel()
