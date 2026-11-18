"""Entry point: read the environment, wire the consumer and start the loop."""
from __future__ import annotations

import logging
import os

from redis import Redis

from .consumer import Config, Consumer


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    config = Config.from_env()
    redis = Redis.from_url(config.redis_url, decode_responses=True)
    Consumer(redis, config).run()


if __name__ == "__main__":
    main()
