import http.server
import os
import shutil
import socket
import ssl
import subprocess
import threading
import time

import pytest

from alinha_job_consumer import net
from alinha_job_consumer.collectors import CollectorError, TransientError


def resolving(*addresses):
    def resolve(host, port, type=None):
        return [(socket.AF_INET6 if ":" in a else socket.AF_INET, socket.SOCK_STREAM, 6, "", (a, port))
                for a in addresses]
    return resolve


# -- addresses ----------------------------------------------------------------------------------------------------

@pytest.mark.parametrize("addresses", [("10.1.2.3",), ("127.0.0.1",), ("169.254.169.254",), ("192.168.1.1",),
                                       ("8.8.8.8", "10.0.0.1"), ("::1",), ("fd00::1",), ("::ffff:10.0.0.1",),
                                       ("64:ff9b::a00:1",), ("64:ff9b:1::1",), ()])
def test_refuses_hosts_that_resolve_to_a_private_address(addresses):
    with pytest.raises(CollectorError) as error:
        net.public_address("www.acme.pt", 443, resolving(*addresses))
    assert error.value.code == "host_not_allowed"


def test_public_host_resolves_once_to_its_first_address():
    assert net.public_address("www.acme.pt", 443, resolving("8.8.8.8", "1.1.1.1")) == "8.8.8.8"


def test_prefers_an_ipv4_address():
    assert net.public_address("www.acme.pt", 443, resolving("2606:4700::1", "1.1.1.1")) == "1.1.1.1"
    assert net.public_address("www.acme.pt", 443, resolving("2606:4700::1")) == "2606:4700::1"


def test_public_addresses_returns_every_address_once_ipv4_first():
    answer = resolving("2606:4700::1", "8.8.8.8", "2606:4700::2", "1.1.1.1", "8.8.8.8")
    assert net.public_addresses("www.acme.pt", 443, answer) == ("8.8.8.8", "1.1.1.1", "2606:4700::1", "2606:4700::2")


def test_public_addresses_refuses_the_host_when_any_address_is_private():
    with pytest.raises(CollectorError) as error:
        net.public_addresses("www.acme.pt", 443, resolving("8.8.8.8", "2606:4700::1", "fd00::1"))
    assert error.value.code == "host_not_allowed"


@pytest.mark.parametrize("address", [
    "2002:0a00:0001::",                          # 6to4 of 10.0.0.1
    "2002:7f00:0001::1",                         # 6to4 of 127.0.0.1
    "2001:0:4136:e378:8000:63bf:f5ff:fffe",      # Teredo, client 10.0.0.1 (stored inverted)
    "2001:0:0a00:0001:8000:63bf:3fff:fdd2",      # Teredo, server 10.0.0.1
])
def test_6to4_and_teredo_with_a_private_ipv4_are_refused(address):
    assert not net.is_public(address)


def test_teredo_is_refused_even_with_public_ipv4_addresses():
    assert not net.is_public("2001:0:4136:e378:8000:63bf:3fff:fdd2")  # server 65.54.227.120, client 192.0.2.45
    assert not net.is_public("2001:0:0808:0808:8000:63bf:f7f7:f7f7")  # server and client 8.8.8.8


def test_6to4_with_a_public_ipv4_is_allowed():
    assert net.is_public("2002:0808:0808::1") == net.ipaddress.ip_address("2002:0808:0808::1").is_global


def test_public_ipv4_mapped_address_is_allowed():
    assert net.is_public("::ffff:8.8.8.8")


def test_unresolvable_host_is_retryable():
    def fail(*_args, **_kwargs):
        raise socket.gaierror(socket.EAI_AGAIN, "temporary failure in name resolution")

    with pytest.raises(TransientError):
        net.public_address("www.acme.pt", 443, fail)


def test_a_name_that_does_not_exist_is_final():
    def not_found(*_args, **_kwargs):
        raise socket.gaierror(socket.EAI_NONAME, "name or service not known")

    with pytest.raises(CollectorError) as error:
        net.public_address("wwww.acme.pt", 443, not_found)
    assert error.value.code == "host_not_found"


def test_user_agent_names_the_provider():
    assert net.user_agent("external-surface") == "aLinha-external-surface/1.0"


# -- local servers ------------------------------------------------------------------------------------------------

@pytest.fixture(scope="module")
def certificate(tmp_path_factory):
    """A throwaway self-signed certificate for `localhost`, made at test time (no key in the repository)."""
    if shutil.which("openssl") is None:
        pytest.skip("openssl is not installed")
    directory = tmp_path_factory.mktemp("tls")
    cert, key = directory / "cert.pem", directory / "key.pem"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                    "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
                    "-keyout", str(key), "-out", str(cert)], check=True, capture_output=True)
    return cert, key


class _Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802 - http.server's name
        if self.path == "/redirect":
            self.send_response(301)
            self.send_header("Location", "https://localhost/")
            self.end_headers()
            return
        if self.path == "/big":
            body = b"x" * 64
        else:
            body = f"agent={self.headers.get('User-Agent')}".encode()
        self.send_response(200 if self.path != "/missing" else 404)
        self.send_header("Strict-Transport-Security", "max-age=31536000")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        pass


def _serve(server):
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


@pytest.fixture()
def http_server():
    server = _serve(http.server.HTTPServer(("127.0.0.1", 0), _Handler))
    yield server.server_address[1]
    server.shutdown()
    server.server_close()


@pytest.fixture()
def https_server(certificate):
    server = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(*map(str, certificate))
    server.socket = context.wrap_socket(server.socket, server_side=True)
    _serve(server)
    yield server.server_address[1]
    server.shutdown()
    server.server_close()


def trusting(certificate):
    return ssl.create_default_context(cafile=str(certificate[0]))


@pytest.fixture()
def trickling_server(certificate):
    """Accepts connections and sends one byte every 0.1 s, forever: each read succeeds, the answer never ends.

    `tls=True` completes the TLS handshake first; `tls="handshake"` trickles before any handshake; `tls="body"`
    sends whole headers with `Connection: close` and trickles the body; `tls="corrupt"` answers with a TLS record
    that fails its integrity check."""
    servers = []

    def start(tls=False):
        listener = socket.create_server(("127.0.0.1", 0))
        stop = threading.Event()
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(*map(str, certificate))

        def trickle(conn):
            try:
                if tls in (True, "body", "corrupt"):
                    conn = context.wrap_socket(conn, server_side=True)
                    conn.recv(4096)  # the request
                if tls == "corrupt":
                    # a TLS record that fails its integrity check: the client's read raises ssl.SSLError
                    with socket.socket(fileno=os.dup(conn.fileno())) as raw:
                        raw.sendall(b"\x17\x03\x03\x00\x20" + b"\x00" * 32)
                    return
                data = {"handshake": b"\x16",
                        "body": b"HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 100000\r\n\r\n"}.get(
                    tls, b"HTTP/1.1 200 OK\r\nX-Slow: ")
                conn.sendall(data)
                while not stop.wait(0.1):
                    conn.sendall(b"\x03" if tls == "handshake" else b"a")
            except OSError:
                pass
            finally:
                conn.close()

        def accept():
            listener.settimeout(0.2)
            while not stop.is_set():
                try:
                    conn, _ = listener.accept()
                except OSError:
                    continue
                threading.Thread(target=trickle, args=(conn,), daemon=True).start()

        threading.Thread(target=accept, daemon=True).start()
        servers.append((listener, stop))
        return listener.getsockname()[1]

    yield start
    for listener, stop in servers:
        stop.set()
        listener.close()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


# -- https --------------------------------------------------------------------------------------------------------

def test_https_get_reads_headers_and_body_from_the_pinned_address(https_server, certificate):
    answer = net.pinned_https_get("localhost", "127.0.0.1", "/", {"User-Agent": "probe"}, port=https_server,
                                  context=trusting(certificate))
    assert answer.status == 200
    assert answer.headers["strict-transport-security"] == "max-age=31536000"
    assert answer.body == b"agent=probe"


def test_https_get_reads_no_body_unless_200(https_server, certificate):
    answer = net.pinned_https_get("localhost", "127.0.0.1", "/missing", {}, port=https_server,
                                  context=trusting(certificate))
    assert (answer.status, answer.body) == (404, b"")


def test_https_get_does_not_follow_redirects(https_server, certificate):
    answer = net.pinned_https_get("localhost", "127.0.0.1", "/redirect", {}, port=https_server,
                                  context=trusting(certificate))
    assert answer.status == 301
    assert answer.headers["location"] == "https://localhost/"


def test_https_get_can_skip_the_body(https_server, certificate):
    answer = net.pinned_https_get("localhost", "127.0.0.1", "/big", {}, port=https_server, max_bytes=0,
                                  context=trusting(certificate))
    assert (answer.status, answer.body) == (200, b"")


def test_https_get_refuses_a_large_body_for_good(https_server, certificate):
    with pytest.raises(CollectorError) as error:
        net.pinned_https_get("localhost", "127.0.0.1", "/big", {}, port=https_server, max_bytes=10,
                             context=trusting(certificate))
    assert error.value.code == "response_too_large"


def test_https_get_raises_an_untrusted_certificate_as_it_is(https_server):
    with pytest.raises(ssl.SSLCertVerificationError):
        net.pinned_https_get("localhost", "127.0.0.1", "/", {}, port=https_server)


def test_https_get_checks_the_certificate_against_the_host_name(https_server, certificate):
    with pytest.raises(ssl.SSLCertVerificationError):
        net.pinned_https_get("www.acme.pt", "127.0.0.1", "/", {}, port=https_server, context=trusting(certificate))


def test_https_get_unreachable_host_is_retryable():
    with pytest.raises(TransientError):
        net.pinned_https_get("localhost", "127.0.0.1", "/", {}, port=free_port(), timeout=2)


def test_paths_must_be_absolute():
    with pytest.raises(ValueError):
        net.pinned_https_get("localhost", "127.0.0.1", "evil.example/", {})
    with pytest.raises(ValueError):
        net.pinned_http_location("localhost", "127.0.0.1", "evil.example/")


# -- plain http ---------------------------------------------------------------------------------------------------

def test_http_location_returns_the_redirect_target(http_server):
    assert net.pinned_http_location("localhost", "127.0.0.1", "/redirect", port=http_server) == (
        301, "https://localhost/")


def test_http_location_of_a_page_served_over_http(http_server):
    assert net.pinned_http_location("localhost", "127.0.0.1", port=http_server) == (200, None)


def test_http_location_tells_a_closed_port_apart():
    with pytest.raises(ConnectionRefusedError):
        net.pinned_http_location("localhost", "127.0.0.1", port=free_port(), timeout=2)


def test_http_location_other_failures_are_retryable(monkeypatch):
    def time_out(*_args, **_kwargs):
        raise TimeoutError()

    monkeypatch.setattr(socket, "create_connection", time_out)
    with pytest.raises(TransientError):
        net.pinned_http_location("localhost", "127.0.0.1")


# -- tls ----------------------------------------------------------------------------------------------------------

def test_tls_handshake_reports_the_version_and_certificate(https_server, certificate):
    info = net.tls_handshake("localhost", "127.0.0.1", trusting(certificate), port=https_server)
    assert info.version in ("TLSv1.2", "TLSv1.3")
    assert ("commonName", "localhost") in info.peercert["subject"][0]


def test_tls_handshake_raises_an_invalid_certificate_as_it_is(https_server):
    with pytest.raises(ssl.SSLCertVerificationError):
        net.tls_handshake("localhost", "127.0.0.1", ssl.create_default_context(), port=https_server)


@pytest.mark.filterwarnings("ignore::DeprecationWarning")  # TLS 1.0/1.1 are deprecated: that is the point
def test_tls_handshake_raises_a_refused_protocol_as_it_is(https_server):
    old_only = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    old_only.check_hostname = False
    old_only.verify_mode = ssl.CERT_NONE
    try:
        old_only.minimum_version = ssl.TLSVersion.TLSv1
        old_only.maximum_version = ssl.TLSVersion.TLSv1_1
    except (ValueError, ssl.SSLError):
        pytest.skip("this OpenSSL cannot offer TLS 1.0/1.1")
    with pytest.raises(ssl.SSLError):
        net.tls_handshake("localhost", "127.0.0.1", old_only, port=https_server)


def test_tls_handshake_unreachable_host_is_retryable():
    with pytest.raises(TransientError):
        net.tls_handshake("localhost", "127.0.0.1", ssl.create_default_context(), port=free_port(), timeout=2)


# -- total deadline -----------------------------------------------------------------------------------------------

def _within(seconds, call):
    started = time.monotonic()
    with pytest.raises(TransientError):
        call()
    assert time.monotonic() - started < seconds


def test_http_location_gives_up_on_a_host_that_trickles(trickling_server):
    port = trickling_server()
    _within(3, lambda: net.pinned_http_location("localhost", "127.0.0.1", port=port, timeout=1, deadline=0.5))


def test_https_get_gives_up_on_a_host_that_trickles(trickling_server, certificate):
    port = trickling_server(tls=True)
    _within(3, lambda: net.pinned_https_get("localhost", "127.0.0.1", "/", {}, port=port, timeout=1, deadline=0.5,
                                            context=trusting(certificate)))


def test_https_get_gives_up_on_a_body_that_trickles_after_connection_close(trickling_server, certificate):
    # http.client closes the connection before the body of a `Connection: close` answer: the deadline must hold.
    port = trickling_server(tls="body")
    _within(3, lambda: net.pinned_https_get("localhost", "127.0.0.1", "/", {}, port=port, timeout=1, deadline=0.5,
                                            context=trusting(certificate)))


def test_a_tls_error_after_the_handshake_is_retryable_not_a_certificate_problem(trickling_server, certificate):
    port = trickling_server(tls="corrupt")
    with pytest.raises(TransientError) as error:
        net.pinned_https_get("localhost", "127.0.0.1", "/", {}, port=port, timeout=2, context=trusting(certificate))
    assert not isinstance(error.value, ssl.SSLError)


def test_the_watchdog_never_shuts_down_a_socket_that_reused_the_descriptor():
    first, peer = socket.socketpair()
    watchdog = net._Watchdog(first, 0.2)
    first.close()  # as wrap_socket does after a failed handshake, before the watchdog is cancelled
    reused, other = socket.socketpair()
    try:
        time.sleep(0.4)  # the watchdog fires on its own duplicate, not on whatever has the old number now
        assert watchdog.fired
        reused.sendall(b"ok")
        assert other.recv(2) == b"ok"
        peer.settimeout(1)
        assert peer.recv(1) == b""  # the original connection was the one shut down
    finally:
        watchdog.cancel()
        for sock in (peer, reused, other):
            sock.close()


def test_tls_handshake_gives_up_on_a_host_that_trickles(trickling_server, certificate):
    port = trickling_server(tls="handshake")
    _within(3, lambda: net.tls_handshake("localhost", "127.0.0.1", trusting(certificate), port=port, timeout=1,
                                         deadline=0.5))


def test_the_deadline_defaults_to_twice_the_timeout():
    assert net._deadline(5, None) == 10
    assert net._deadline(5, 1.5) == 1.5


def test_connecting_never_takes_longer_than_the_deadline(monkeypatch):
    used = []

    def record(address, timeout):
        used.append(timeout)
        raise TimeoutError()

    monkeypatch.setattr(socket, "create_connection", record)
    with pytest.raises(TransientError):
        net.pinned_https_get("localhost", "127.0.0.1", "/", {}, timeout=10, deadline=2)
    with pytest.raises(TransientError):
        net.tls_handshake("localhost", "127.0.0.1", ssl.create_default_context(), timeout=10, deadline=2)
    assert used == [pytest.approx(2, abs=0.1)] * 2


# -- several addresses --------------------------------------------------------------------------------------------

DOWN = "192.0.2.1"  # TEST-NET-1: stands for an address of the host that does not answer


def failing_for(monkeypatch, failures):
    """Makes the connection to each address in [failures] raise that error; the others really connect."""
    real = socket.create_connection
    tried = []

    def connect(address, timeout):
        tried.append(address[0])
        if address[0] in failures:
            raise failures[address[0]]
        return real(address, timeout)

    monkeypatch.setattr(socket, "create_connection", connect)
    return tried


def test_https_get_falls_back_to_the_next_address(monkeypatch, https_server, certificate):
    tried = failing_for(monkeypatch, {DOWN: TimeoutError()})
    answer = net.pinned_https_get("localhost", (DOWN, "127.0.0.1"), "/", {}, port=https_server,
                                  context=trusting(certificate))
    assert answer.status == 200
    assert tried == [DOWN, "127.0.0.1"]


def test_http_location_falls_back_to_the_next_address(monkeypatch, http_server):
    failing_for(monkeypatch, {DOWN: OSError("network unreachable")})
    assert net.pinned_http_location("localhost", [DOWN, "127.0.0.1"], port=http_server) == (200, None)


def test_tls_handshake_falls_back_to_the_next_address(monkeypatch, https_server, certificate):
    failing_for(monkeypatch, {DOWN: ConnectionRefusedError()})
    info = net.tls_handshake("localhost", (DOWN, "127.0.0.1"), trusting(certificate), port=https_server)
    assert info.version in ("TLSv1.2", "TLSv1.3")


def test_the_first_address_that_answers_is_used_and_the_rest_are_not_tried(monkeypatch, http_server):
    tried = failing_for(monkeypatch, {})
    net.pinned_http_location("localhost", ("127.0.0.1", DOWN), port=http_server)
    assert tried == ["127.0.0.1"]


def test_a_closed_port_is_reported_only_when_every_address_refuses(monkeypatch):
    other = "192.0.2.2"
    failing_for(monkeypatch, {DOWN: ConnectionRefusedError(), other: ConnectionRefusedError()})
    with pytest.raises(ConnectionRefusedError):
        net.pinned_http_location("localhost", (DOWN, other))
    failing_for(monkeypatch, {DOWN: ConnectionRefusedError(), other: TimeoutError()})
    with pytest.raises(TransientError):
        net.pinned_http_location("localhost", (DOWN, other))


def test_every_address_failing_is_retryable(monkeypatch):
    failing_for(monkeypatch, {DOWN: TimeoutError(), "192.0.2.2": OSError("no route")})
    with pytest.raises(TransientError):
        net.pinned_https_get("localhost", (DOWN, "192.0.2.2"), "/", {})
    with pytest.raises(TransientError):
        net.tls_handshake("localhost", (DOWN, "192.0.2.2"), ssl.create_default_context())


def test_the_attempts_together_never_take_longer_than_the_deadline(monkeypatch):
    used = []

    def slow(address, timeout):
        used.append(timeout)
        time.sleep(0.3)
        raise TimeoutError()

    monkeypatch.setattr(socket, "create_connection", slow)
    started = time.monotonic()
    with pytest.raises(TransientError):
        net.pinned_https_get("localhost", ("192.0.2.1", "192.0.2.2", "192.0.2.3"), "/", {}, timeout=10,
                             deadline=0.5)
    assert time.monotonic() - started < 0.9
    assert len(used) == 2  # the third address had no time left
    assert used[0] == pytest.approx(0.5, abs=0.05) and used[1] < 0.25


def test_no_address_is_a_programming_error():
    with pytest.raises(ValueError):
        net.tls_handshake("localhost", (), ssl.create_default_context())
