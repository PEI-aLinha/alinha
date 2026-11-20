import logging
from types import SimpleNamespace

import pytest

from alinha_job_consumer import collectors


@pytest.fixture(autouse=True)
def fresh_cache():
    collectors.registered.cache_clear()
    yield
    collectors.registered.cache_clear()


def _fake_entries(monkeypatch, entries):
    monkeypatch.setattr(collectors, "entry_points", lambda group: entries)


def test_returns_the_collector_registered_for_the_provider(monkeypatch):
    collector = object()
    entry = SimpleNamespace(name="code-repository", load=lambda: collector)
    _fake_entries(monkeypatch, [SimpleNamespace(name="other", load=lambda: None), entry])

    assert collectors.get_collector("code-repository") is collector


def test_unknown_provider_returns_none(monkeypatch):
    _fake_entries(monkeypatch, [])

    assert collectors.get_collector("code-repository") is None


def test_entry_points_are_scanned_once(monkeypatch):
    scans = []
    monkeypatch.setattr(collectors, "entry_points", lambda group: scans.append(group) or [])

    collectors.get_collector("a")
    collectors.get_collector("b")

    assert len(scans) == 1


def test_a_duplicate_provider_id_is_logged_and_the_first_wins(monkeypatch, caplog):
    first, second = object(), object()
    _fake_entries(
        monkeypatch,
        [
            SimpleNamespace(name="code-repository", value="pkg_a:collect", load=lambda: first),
            SimpleNamespace(name="code-repository", value="pkg_b:collect", load=lambda: second),
        ],
    )

    with caplog.at_level(logging.ERROR):
        assert collectors.get_collector("code-repository") is first
    assert "two packages register collector 'code-repository'" in caplog.text
