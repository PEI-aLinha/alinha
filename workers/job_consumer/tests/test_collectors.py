import uuid
from types import SimpleNamespace

from alinha_job_consumer import collectors


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
