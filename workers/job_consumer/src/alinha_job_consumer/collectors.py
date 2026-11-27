"""Discover provider collectors through the `alinha.collectors` entry-point group."""
from __future__ import annotations

import functools
import logging
import re
from importlib.metadata import EntryPoint, entry_points
from typing import Any, Callable

ENTRY_POINT_GROUP = "alinha.collectors"

log = logging.getLogger(__name__)

Collector = Callable[[dict[str, Any]], dict[str, Any]]


class TransientError(Exception):
    """A retryable failure: timeout, 5xx, connection reset."""


class CollectorError(Exception):
    """A final failure the core may show: `code` and `message` go into the result as they are.

    Use it for expected failures (`invalid_credentials`, `connection_not_found`); any other exception becomes a
    generic `collector_error`. Never put a credential, a URL with a token or provider response text in either field.
    """

    _CODE = re.compile(r"[a-z][a-z0-9_]{0,99}")

    def __init__(self, code: str, message: str) -> None:
        if not self._CODE.fullmatch(code):
            raise ValueError(f"error code must match {self._CODE.pattern}")
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message[:500]


@functools.lru_cache(maxsize=1)
def registered() -> dict[str, EntryPoint]:
    """Provider id -> entry point, scanned once; the first package wins when two register the same id."""
    found: dict[str, EntryPoint] = {}
    for entry_point in entry_points(group=ENTRY_POINT_GROUP):
        if entry_point.name in found:
            kept = found[entry_point.name].value
            log.error("two packages register collector %r; keeping %s", entry_point.name, kept)
            continue
        found[entry_point.name] = entry_point
    return found


def get_collector(provider: str) -> Collector | None:
    """The collector registered for a provider id, or None when the provider is not installed."""
    entry_point = registered().get(provider)
    return entry_point.load() if entry_point else None
