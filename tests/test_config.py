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


# ── AC1: enabled defaults to True when unclassified key is absent ─────────────

def test_load_no_unclassified_key_enabled_defaults_to_true(tmp_path):
    """load() with no 'unclassified' key must produce unclassified.enabled == True."""
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "tag_groups": [],
        # no 'unclassified' key at all
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.unclassified.enabled is True, (
        f"Expected enabled=True when 'unclassified' key is absent, got {config.unclassified.enabled}"
    )


# ── AC2: enabled defaults to True when unclassified block present but no enabled ─

def test_load_unclassified_block_no_enabled_key_defaults_to_true(tmp_path):
    """load() with unclassified block but no 'enabled' key must produce enabled == True."""
    from imagesorter.config import load
    config_data = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path),
        "threads": 1,
        "tag_groups": [],
        "unclassified": {"folder_name": "others", "destination": "./sorted"},
        # 'enabled' key intentionally omitted from unclassified block
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.unclassified.enabled is True, (
        f"Expected enabled=True when 'enabled' key omitted from unclassified block, got {config.unclassified.enabled}"
    )


# ── AC8: specification.md documents the corrected default and validation ───────

def test_spec_documents_unclassified_enabled_default():
    """`specification.md` must state that unclassified.enabled defaults to true when omitted."""
    from pathlib import Path
    spec = (Path(__file__).parent.parent / "specification.md").read_text(encoding="utf-8")
    assert "unclassified.enabled` defaults to `true` when the key is omitted" in spec, (
        "specification.md missing: unclassified.enabled defaults to true when the key is omitted"
    )


def test_spec_documents_unclassified_moves_or_copies():
    """`specification.md` must state that non-matching images are moved or copied."""
    from pathlib import Path
    spec = (Path(__file__).parent.parent / "specification.md").read_text(encoding="utf-8")
    assert "moved or copied" in spec, (
        "specification.md missing: non-matching images are moved or copied"
    )


def test_spec_documents_unclassified_destination_validated_at_startup():
    """`specification.md` must state that unclassified.destination is validated at startup."""
    from pathlib import Path
    spec = (Path(__file__).parent.parent / "specification.md").read_text(encoding="utf-8")
    assert "unclassified.destination` is validated against `source_folder` at startup" in spec, (
        "specification.md missing: unclassified.destination is validated against source_folder at startup"
    )


# ── AC3: TEMPLATE parses to unclassified.enabled == True (no regression) ──────

def test_template_unclassified_enabled_is_true():
    """yaml.safe_load(TEMPLATE)['unclassified']['enabled'] must be True."""
    from imagesorter.config import TEMPLATE
    parsed = yaml.safe_load(TEMPLATE)
    assert parsed["unclassified"]["enabled"] is True, (
        f"Expected TEMPLATE unclassified.enabled=True, got {parsed['unclassified']['enabled']}"
    )


# ── web_ui and similarity_time_window_minutes config keys ─────────────────────

def test_load_web_ui_default_is_false(tmp_path):
    """When web_ui is absent from config, Config.web_ui defaults to False."""
    from imagesorter.config import load
    config_data = {
        "mode": "SimilaritySearch",
        "source_folder": str(tmp_path),
        "threads": 1,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.web_ui is False


def test_load_web_ui_true(tmp_path):
    """When web_ui: true is set, Config.web_ui is True."""
    from imagesorter.config import load
    config_data = {
        "mode": "SimilaritySearch",
        "source_folder": str(tmp_path),
        "threads": 1,
        "web_ui": True,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.web_ui is True


def test_load_similarity_time_window_default_is_5(tmp_path):
    """When similarity_time_window_minutes is absent, default is 5."""
    from imagesorter.config import load
    config_data = {
        "mode": "SimilaritySearch",
        "source_folder": str(tmp_path),
        "threads": 1,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.similarity_time_window_minutes == 5


def test_load_similarity_time_window_custom(tmp_path):
    """similarity_time_window_minutes is read from yaml."""
    from imagesorter.config import load
    config_data = {
        "mode": "SimilaritySearch",
        "source_folder": str(tmp_path),
        "threads": 1,
        "similarity_time_window_minutes": 10,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    config = load(str(cfg_file))
    assert config.similarity_time_window_minutes == 10


def test_load_raises_for_similarity_time_window_negative(tmp_path):
    """Negative similarity_time_window_minutes must raise ValueError at load time."""
    from imagesorter.config import load
    config_data = {
        "mode": "SimilaritySearch",
        "source_folder": str(tmp_path),
        "threads": 1,
        "similarity_time_window_minutes": -1,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path)},
    }
    cfg_file = tmp_path / "cfg.yaml"
    cfg_file.write_text(yaml.dump(config_data))
    with pytest.raises(ValueError, match="similarity_time_window_minutes"):
        load(str(cfg_file))
