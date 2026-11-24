"""Network access to hosts a tenant chose (stdlib only).

A collector that connects to an address the tenant typed (a self-managed server, the organisation's own domain)
must go through this module, never through urllib or http.client directly:

- [public_address] resolves the host once and refuses it unless every address is a public internet address, so a
  tenant cannot point the platform at its own network (SSRF);
- the connection functions take that checked address and connect to it directly, with TLS verified against the
  host name, so a second DNS answer cannot move the request somewhere else (DNS rebinding);
- redirects are never followed and bodies are capped: what comes back is untrusted data.

Network failures raise [TransientError] (the core retries the job). A host that is not public raises
[CollectorError] `host_not_allowed`.
"""
from __future__ import annotations

import http.client
import ipaddress
import socket
import ssl
from dataclasses import dataclass, field
from typing import Any, Callable, Mapping

from alinha_job_consumer.collectors import CollectorError, TransientError

TIMEOUT_S = 10.0
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
    except OSError as error:
        raise TransientError("host does not resolve") from error
    addresses = [info[4][0] for info in infos]
    if not addresses or not all(is_public(a) for a in addresses):
        raise CollectorError("host_not_allowed", "the address is not a public internet address")
    return next((a for a in addresses if ":" not in a), addresses[0])


# -- connections --------------------------------------------------------------------------------------------------

class PinnedHTTPSConnection(http.client.HTTPSConnection):
    """Connects to an address already checked by [public_address], with TLS verified against the host name."""

    def __init__(self, host: str, port: int, address: str, timeout: float,
                 context: ssl.SSLContext | None = None) -> None:
        super().__init__(host, port, timeout=timeout, context=context)
        self._address = address

    def connect(self) -> None:
        sock = socket.create_connection((self._address, self.port), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


class _PinnedHTTPConnection(http.client.HTTPConnection):
    def __init__(self, host: str, port: int, address: str, timeout: float) -> None:
        super().__init__(host, port, timeout=timeout)
        self._address = address

    def connect(self) -> None:
        self.sock = socket.create_connection((self._address, self.port), self.timeout)


def read_capped(answer: Any, limit: int = MAX_BODY_BYTES) -> bytes:
    body = answer.read(limit + 1)
    if len(body) > limit:
        raise TransientError("answer too large")
    return body


def _headers(items: Any) -> dict[str, str]:
    return {key.lower(): value for key, value in items}


def pinned_https_get(host: str, address: str, path: str, headers: Mapping[str, str], *, port: int = 443,
                     timeout: float = TIMEOUT_S, max_bytes: int = MAX_BODY_BYTES,
                     context: ssl.SSLContext | None = None) -> Response:
    """One GET over https to [address], never redirected. The body is read only for a 200 answer."""
    if not path.startswith("/"):
        raise ValueError("path must start with /")
    connection = PinnedHTTPSConnection(host, port, address, timeout, context)
    try:
        connection.request("GET", path, headers=dict(headers))
        answer = connection.getresponse()  # http.client never follows redirects
        body = read_capped(answer, max_bytes) if answer.status == 200 else b""
        return Response(answer.status, _headers(answer.getheaders()), body)
    except ssl.SSLError:
        raise  # the caller decides what an invalid certificate means
    except (OSError, http.client.HTTPException) as error:
        raise TransientError("host unreachable") from error
    finally:
        connection.close()


def pinned_http_location(host: str, address: str, path: str = "/", *, port: int = 80,
                         timeout: float = TIMEOUT_S, headers: Mapping[str, str] | None = None
                         ) -> tuple[int, str | None]:
    """One GET over plain http, to see where it sends visitors: returns (status, Location). The body is never read.

    A refused connection raises ConnectionRefusedError, so the caller can tell "port 80 is closed" apart from
    other network failures (TransientError)."""
    if not path.startswith("/"):
        raise ValueError("path must start with /")
    connection = _PinnedHTTPConnection(host, port, address, timeout)
    try:
        connection.request("GET", path, headers=dict(headers or {}))
        answer = connection.getresponse()
        return answer.status, answer.getheader("Location")
    except ConnectionRefusedError:
        raise
    except (OSError, http.client.HTTPException) as error:
        raise TransientError("host unreachable") from error
    finally:
        connection.close()


def tls_handshake(host: str, address: str, context: ssl.SSLContext, *, port: int = 443,
                  timeout: float = TIMEOUT_S) -> TlsInfo:
    """Opens a TLS session to [address] with SNI [host], sends nothing and closes it.

    ssl.SSLError (including SSLCertVerificationError) is raised as it is: for a collector a refused handshake or
    an invalid certificate is a finding, not a network failure. Other network errors raise TransientError."""
    try:
        with socket.create_connection((address, port), timeout) as sock:
            with context.wrap_socket(sock, server_hostname=host) as tls:
                return TlsInfo(tls.version() or "", tls.getpeercert() or {})
    except ssl.SSLError:
        raise
    except OSError as error:
        raise TransientError("host unreachable") from error
