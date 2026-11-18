"""Discover provider collectors through the `alinha.collectors` entry-point group."""
from __future__ import annotations

from importlib.metadata import entry_points
from typing import Any, Callable

ENTRY_POINT_GROUP = "alinha.collectors"

Collector = Callable[[dict[str, Any]], dict[str, Any]]


class TransientError(Exception):
    """A retryable failure: timeout, 5xx, connection reset."""


def get_collector(provider: str) -> Collector | None:
    """The collector registered for a provider id, or None when the provider is not installed."""
    for entry_point in entry_points(group=ENTRY_POINT_GROUP):
        if entry_point.name == provider:
            return entry_point.load()
    return None
