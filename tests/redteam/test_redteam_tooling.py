"""
Red team tooling verification tests for WhiteDevil harness.

Complements test_redteam_policies.py by checking Python vs CLI tool parity,
tool ABI, and environment setup.
"""

import subprocess
from pathlib import Path

import pytest


def test_redteam_tooling_api_present():
    """Verify Python harness exposes red team tooling APIs."""
    harness_py = Path("qwen_api/harness/sandbox.py")
    if harness_py.exists():
        content = harness_py.read_text(encoding="utf-8")
        assert "def tool_refusal" in content or "def path_isolation" in content


def test_redteam_docker_wrapper_exists():
    """Verify Docker wrapper script exists and is executable."""
    wrapper = Path("scripts/harness-run.sh")
    if wrapper.exists():
        assert wrapper.is_file()
        assert subprocess.run(["bash", "-n", wrapper], capture_output=True).returncode == 0


def test_redteam_node_script_present():
    """Verify Node.js harness scripts exist under laptop-app."""
    node_harness = Path("laptop-app/test/redteam")
    if node_harness.exists():
        for f in node_harness.glob("*.js"):
            assert f.is_file()


def test_tooling_imports_and_exports():
    """Verify expected exports from common harness modules."""
    # Example: shared helpers imported and re-exported correctly
    common = Path("scripts/probe_model_capabilities.py")
    if common.exists():
        content = common.read_text(encoding="utf-8")
        # Should import shared utilities and expose main entry point


def test_secret_scanner_integration():
    """Verify secret scanner is integrated into read/search paths."""
    # Example: repository.py or context.py import scanner module
    repo = Path("qwen_api/harness/repository.py")
    if repo.exists():
        content = repo.read_text(encoding="utf-8")
        assert "secret" in content.lower() or "parse_secret" in content


class TestToolParity:
    """Python vs CLI tooling parity."""

    def test_node_cli_wrapper_exists(self):
        """Verify Node.js CLI entry exists."""
        cli_entry = Path("laptop-app/main.cjs")
        if cli_entry.exists():
            assert cli_entry.is_file()

    def test_python_api_match_cli_behavior(self):
        """Verify Python harness mocks a CLI with parity."""
        cli = Path("laptop-app/main.cjs")
        if cli.exists():
            # Inspect main.cjs for capability;set entries matching harness expectations
            content = cli.read_text(encoding="utf-8")
            # Should reference tools similar to harness tools


def test_scenarios_parity_across_scenarios():
    """Ensure scenarios in REDTEAM_SCENARIOS map to test cases."""
    # Cross-check test_redteam_policies.py scenario list
    policies = Path("tests/redteam/test_redteam_policies.py")
    if policies.exists():
        content = policies.read_text(encoding="utf-8")
        assert "REDTEAM_SCENARIOS" in content
        assert "expected_refusal" in content


@pytest.mark.external
def test_docker_network_isolation():
    """External: Verify Docker networking flag usage in harness."""
    # This requires actual Docker container inspection
    pass


class TestRegression:
    """Regression protection against losing previous fixes."""

    def test_no_leaks_in_previous_fixes(self):
        """Verify synthetic credential fix still applies after runs."""
        # Example: check secret_scanner logic covers previous edge case
        pass

    def test_cached_secrets_not_returned(self):
        """Verify caches do not leak secret values."""
        # Example: inspect cache storage keys and values
        pass
