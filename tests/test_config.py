import os
import pytest
import yaml
from unittest.mock import patch


# ── Criterion: batch_size ──────────────────────────────────────────────────────

def test_batch_size_default_is_16():
    from imagesorter.config import Config, Unclassified
    config = Config(
        mode="GroupByTags",
        source_folder="./photos",
        recursive=True,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="INFO",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(
            enabled=False, folder_name="others", destination="./sorted",
        ),
        similarity_threshold=0.96,
    )
    assert config.batch_size == 16


def test_load_reads_batch_size_from_yaml(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "batch_size": 8,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.batch_size == 8


def test_load_batch_size_falls_back_to_16(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.batch_size == 16


# ── Criterion: confidence_threshold ───────────────────────────────────────────

def test_confidence_threshold_default_is_0_5():
    from imagesorter.config import Config, Unclassified
    config = Config(
        mode="GroupByTags",
        source_folder="./photos",
        recursive=True,
        copy_instead_of_move=False,
        include_formats=[".jpg"],
        threads=1,
        log_level="INFO",
        log_file=None,
        tag_groups=[],
        unclassified=Unclassified(
            enabled=False, folder_name="others", destination="./sorted",
        ),
        similarity_threshold=0.96,
    )
    assert config.confidence_threshold == 0.5


def test_load_reads_confidence_threshold_from_yaml(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "confidence_threshold": 0.75,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.confidence_threshold == 0.75


def test_load_confidence_threshold_falls_back_to_0_5(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.confidence_threshold == 0.5


# ── Criterion: TEMPLATE includes batch_size and confidence_threshold ───────────

def test_template_contains_batch_size():
    from imagesorter.config import TEMPLATE
    assert "batch_size" in TEMPLATE


def test_template_contains_confidence_threshold():
    from imagesorter.config import TEMPLATE
    assert "confidence_threshold" in TEMPLATE


def test_template_batch_size_has_inline_comment():
    from imagesorter.config import TEMPLATE
    for line in TEMPLATE.splitlines():
        if "batch_size" in line and "#" in line:
            return
    raise AssertionError("TEMPLATE batch_size line has no inline comment")


def test_template_confidence_threshold_has_inline_comment():
    from imagesorter.config import TEMPLATE
    for line in TEMPLATE.splitlines():
        if "confidence_threshold" in line and "#" in line:
            return
    raise AssertionError("TEMPLATE confidence_threshold line has no inline comment")


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

# ── Criterion 11: config validation at load time ──────────────────────────────

def test_load_raises_for_confidence_threshold_above_1(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "confidence_threshold": 1.5,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    with pytest.raises(ValueError, match="confidence_threshold"):
        load(str(cfg_file))


def test_load_raises_for_confidence_threshold_below_0(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "confidence_threshold": -0.1,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    with pytest.raises(ValueError, match="confidence_threshold"):
        load(str(cfg_file))


def test_load_raises_for_batch_size_zero(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "batch_size": 0,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    with pytest.raises(ValueError, match="batch_size"):
        load(str(cfg_file))


def test_load_raises_for_batch_size_negative(tmp_path):
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "batch_size": -1,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    with pytest.raises(ValueError, match="batch_size"):
        load(str(cfg_file))


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
