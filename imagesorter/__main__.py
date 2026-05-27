"""Entry point: python -m imagesorter"""
from __future__ import annotations

import sys

from .cli import parse_args
from .config import TEMPLATE, load
from .logging_setup import setup


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)

    if args.generate_config:
        print(TEMPLATE, end="")
        return

    # Load config and apply CLI overrides
    overrides = {}
    if args.threads is not None:
        overrides["threads"] = args.threads

    config = load(args.config, overrides)
    setup(config.log_level, config.log_file)

    if config.mode == "GroupByTags":
        from .sorter import run
    elif config.mode == "SimilaritySearch":
        from .similarity import run
    else:
        print(f"Unknown mode: {config.mode}", file=sys.stderr)
        sys.exit(1)

    run(config)


if __name__ == "__main__":
    main()
