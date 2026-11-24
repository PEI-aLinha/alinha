import http.server
import shutil
import socket
import ssl
import subprocess
import threading

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


def test_public_ipv4_mapped_address_is_allowed():
    assert net.is_public("::ffff:8.8.8.8")


def test_unresolvable_host_is_retryable():
    def fail(*_args, **_kwargs):
        raise socket.gaierror("no such host")

    with pytest.raises(TransientError):
        net.public_address("www.acme.pt", 443, fail)


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


def test_https_get_refuses_a_large_body(https_server, certificate):
    with pytest.raises(TransientError):
        net.pinned_https_get("localhost", "127.0.0.1", "/big", {}, port=https_server, max_bytes=10,
                             context=trusting(certificate))


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
