"""Argument parsing for imagesorter CLI."""
from __future__ import annotations

import argparse
import sys


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="imagesorter",
        description="Sort images by detected tags or visual similarity.",
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--config",
        metavar="FILE",
        help="Path to a YAML config file.",
    )
    group.add_argument(
        "--generate-config",
        action="store_true",
        default=False,
        help="Print a commented template config to stdout and exit.",
    )
    parser.add_argument(
        "--threads",
        type=int,
        default=None,
        metavar="N",
        help="Override the number of worker threads.",
    )
    return parser


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = build_parser()
    return parser.parse_args(argv)
