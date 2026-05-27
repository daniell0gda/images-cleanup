"""Logger configuration."""
from __future__ import annotations

import logging


LOG_FORMAT = "[%(asctime)s] [%(levelname)s] %(message)s"
DATE_FORMAT = "%Y-%m-%d %H:%M:%S"


def setup(log_level: str, log_file: str | None = None) -> None:
    level = getattr(logging, log_level.upper(), logging.INFO)
    handlers: list[logging.Handler] = [logging.StreamHandler()]
    if log_file:
        handlers.append(logging.FileHandler(log_file, encoding="utf-8"))
    logging.basicConfig(
        level=level,
        format=LOG_FORMAT,
        datefmt=DATE_FORMAT,
        handlers=handlers,
        force=True,
    )
