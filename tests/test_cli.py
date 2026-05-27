import subprocess
import sys
import pytest


PYTHON = sys.executable


def run(*args, input=None):
    """Run imagesorter as a subprocess and return CompletedProcess."""
    return subprocess.run(
        [PYTHON, "-m", "imagesorter", *args],
        capture_output=True,
        text=True,
        input=input,
        cwd="X:\\projekty\\image-sorter",
    )


# ── Criterion 1 ────────────────────────────────────────────────────────────────

def test_package_is_importable():
    result = subprocess.run(
        [PYTHON, "-c", "import imagesorter"],
        capture_output=True,
        text=True,
        cwd="X:\\projekty\\image-sorter",
    )
    assert result.returncode == 0, result.stderr


def test_package_is_executable_without_error():
    # Running with no args should not crash with an unhandled exception
    # (it will exit non-zero for missing args — that's fine, tested separately)
    result = run()
    # No unhandled Python tracebacks
    assert "Traceback" not in result.stderr


# ── Criterion 2 ────────────────────────────────────────────────────────────────

def test_no_args_exits_nonzero():
    result = run()
    assert result.returncode != 0


def test_no_args_prints_usage():
    result = run()
    output = result.stdout + result.stderr
    assert "usage" in output.lower() or "error" in output.lower()


# ── Criterion 3 ────────────────────────────────────────────────────────────────

def test_generate_config_outputs_valid_yaml():
    import yaml
    result = run("--generate-config")
    assert result.returncode == 0, result.stderr
    parsed = yaml.safe_load(result.stdout)
    assert parsed is not None


def test_generate_config_contains_required_keys():
    import yaml
    result = run("--generate-config")
    parsed = yaml.safe_load(result.stdout)
    required_keys = {"mode", "source_folder", "recursive", "copy_instead_of_move",
                     "include_formats", "threads", "log_level", "log_file",
                     "tag_groups", "unclassified", "similarity_threshold"}
    for key in required_keys:
        assert key in parsed, f"Missing key: {key}"


def test_generate_config_has_inline_comments():
    result = run("--generate-config")
    assert "#" in result.stdout


# ── Criterion 4 ────────────────────────────────────────────────────────────────

def test_config_starts_without_error(tmp_path):
    import yaml
    # Write a minimal valid config
    config = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path / "src"),
        "recursive": False,
        "copy_instead_of_move": False,
        "include_formats": [".jpg"],
        "threads": 1,
        "log_level": "INFO",
        "log_file": None,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path / "dst"),
                         "group_by_year": False, "group_by_month": False},
        "similarity_threshold": 0.96,
    }
    (tmp_path / "src").mkdir()
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text(yaml.dump(config))
    result = run("--config", str(cfg_file))
    assert result.returncode == 0, result.stderr + result.stdout


# ── Criterion 25: unknown mode exits cleanly ───────────────────────────────────

def test_unknown_mode_exits_nonzero(tmp_path):
    """An unrecognised mode in config exits with a non-zero code."""
    import yaml
    config = {
        "mode": "Bogus",
        "source_folder": str(tmp_path / "src"),
        "recursive": False,
        "copy_instead_of_move": False,
        "include_formats": [".jpg"],
        "threads": 1,
        "log_level": "INFO",
        "log_file": None,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path / "dst"),
                         "group_by_year": False, "group_by_month": False},
        "similarity_threshold": 0.96,
    }
    (tmp_path / "src").mkdir()
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text(yaml.dump(config))
    result = run("--config", str(cfg_file))
    assert result.returncode != 0, "Unknown mode must exit with non-zero code"


def test_unknown_mode_no_traceback(tmp_path):
    """An unrecognised mode must not produce an unhandled exception traceback."""
    import yaml
    config = {
        "mode": "Bogus",
        "source_folder": str(tmp_path / "src"),
        "recursive": False,
        "copy_instead_of_move": False,
        "include_formats": [".jpg"],
        "threads": 1,
        "log_level": "INFO",
        "log_file": None,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path / "dst"),
                         "group_by_year": False, "group_by_month": False},
        "similarity_threshold": 0.96,
    }
    (tmp_path / "src").mkdir()
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text(yaml.dump(config))
    result = run("--config", str(cfg_file))
    assert "Traceback" not in result.stderr, "No unhandled exception expected for unknown mode"


def test_unknown_mode_explanatory_message(tmp_path):
    """An unrecognised mode must print an explanatory message."""
    import yaml
    config = {
        "mode": "Bogus",
        "source_folder": str(tmp_path / "src"),
        "recursive": False,
        "copy_instead_of_move": False,
        "include_formats": [".jpg"],
        "threads": 1,
        "log_level": "INFO",
        "log_file": None,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path / "dst"),
                         "group_by_year": False, "group_by_month": False},
        "similarity_threshold": 0.96,
    }
    (tmp_path / "src").mkdir()
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text(yaml.dump(config))
    result = run("--config", str(cfg_file))
    output = result.stdout + result.stderr
    assert "Bogus" in output or "mode" in output.lower(), (
        f"Expected explanatory message mentioning mode or 'Bogus', got: {output!r}"
    )


def test_threads_cli_overrides_config(tmp_path):
    import yaml
    config = {
        "mode": "GroupByTags",
        "source_folder": str(tmp_path / "src"),
        "recursive": False,
        "copy_instead_of_move": False,
        "include_formats": [".jpg"],
        "threads": 1,
        "log_level": "INFO",
        "log_file": None,
        "tag_groups": [],
        "unclassified": {"enabled": False, "folder_name": "others",
                         "destination": str(tmp_path / "dst"),
                         "group_by_year": False, "group_by_month": False},
        "similarity_threshold": 0.96,
    }
    (tmp_path / "src").mkdir()
    cfg_file = tmp_path / "config.yaml"
    cfg_file.write_text(yaml.dump(config))
    result = run("--config", str(cfg_file), "--threads", "4")
    assert result.returncode == 0, result.stderr + result.stdout
