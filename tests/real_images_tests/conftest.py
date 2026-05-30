import pytest


def pytest_configure(config):
    config.addinivalue_line("markers", "real: tests that use real YOLO inference against real photos")
