import os
import pytest
import yaml
from unittest.mock import patch


# ── Criterion 5: default threads ──────────────────────────────────────────────

def test_null_threads_uses_cpu_count_minus_one(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": None,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path), "group_by_year": False,
                         "group_by_month": False},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))

    with patch("os.cpu_count", return_value=8):
        config = load(str(cfg_file))
    assert config.threads == 7


def test_null_threads_minimum_is_one(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": None,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path), "group_by_year": False,
                         "group_by_month": False},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))

    with patch("os.cpu_count", return_value=1):
        config = load(str(cfg_file))
    assert config.threads == 1


def test_explicit_threads_not_overridden(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 4,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path), "group_by_year": False,
                         "group_by_month": False},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.threads == 4


# ── Criterion 4: --threads CLI overrides config file ─────────────────────────

def test_threads_cli_override_sets_config_threads(tmp_path):
    """Config has threads=1; passing overrides={"threads": 4} must yield config.threads == 4."""
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path), "group_by_year": False,
                         "group_by_month": False},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file), overrides={"threads": 4})
    assert config.threads == 4, (
        f"Expected config.threads == 4 after CLI override, got {config.threads}"
    )
