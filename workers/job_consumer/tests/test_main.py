import signal

import pytest

from alinha_job_consumer import main as main_module


def test_main_wires_the_consumer_and_exits_cleanly_on_sigterm(monkeypatch):
    monkeypatch.setenv("REDIS_URL", "redis://example:6379/1")
    runs = []

    class FakeConsumer:
        def __init__(self, redis, config):
            runs.append((redis, config))

        def run(self):
            signal.raise_signal(signal.SIGTERM)

    monkeypatch.setattr(main_module, "Consumer", FakeConsumer)
    previous = signal.getsignal(signal.SIGTERM)
    try:
        with pytest.raises(SystemExit) as exit_info:
            main_module.main()
    finally:
        signal.signal(signal.SIGTERM, previous)

    assert exit_info.value.code == 0
    redis, config = runs[0]
    assert config.redis_url == "redis://example:6379/1"
    assert redis.connection_pool.connection_kwargs["host"] == "example"
