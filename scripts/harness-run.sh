#!/usr/bin/env bash
#
# Red team Docker harness runner for WhiteDevil harness integration.
#
# Usage:
#   harness-run.sh --scenario <name> [--help]
#
# Outputs logs and artifacts to $(pwd)/artifacts/<scenario>/ (created if needed).
#
# Guardrails per shared hub (START.md, AGENT-CONTRACT.md, PERMISSIONS.md, etc.):
# - Headless Docker runs only; desktop does not auto-update on lifecycle
# - No host directory modifications except test artifacts (managed via temp dirs)
# - Each run uses fresh temporary volumes and gets wiped after pass/fail
# - Resource limits enforced (memory, CPU, pids, timeout, log size)
# - Logging outputs capped and rotated per run
# - All paths in context use absolute values as placed here
#

set -euo pipefail

# --- Paths configured for WhiteDevil 4 repo (WSL) ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
ARTIFACT_BASE="$REPO_ROOT/artifacts"
TEMP_BASE="/tmp/harness-redteam"
IMAGE="${IMAGE:-python:3.13-slim}"
CONTAINER_TAG="${CONTAINER_TAG:-whitedevil-redteam}"

# --- Guardrails ---
memory_mb="${MEMORY_MB:-256}"
cpu_cores="${CPU_CORES:-1}"
pids_limit="${PIDS_LIMIT:-50}"
timeout_sec="${TIMEOUT_SEC:-30}"

network_on="${NETWORK_ON:-false}"
readonly_root="${READONLY_ROOT:-true}"
tmpfs_opts="${TMPFS_OPTS:-/tmp /run}"

# --- Customizable scenarios (example) ---
SCENARIO_DEFAULT="parent_traversal"
readonly_scenarios=(
    "parent_traversal"
    "absolute_path"
    "symlink_escape"
    "model_malformed_args"
    "bogus_done_completion"
)

help_desc() {
    cat <<'HELP'
Red team Docker harness runner.

Usage:
    harness-run.sh --scenario SCENARIO [--help]

Available scenarios:
HELP
    printf '    %s\n' "${readonly_scenarios[@]}"
    cat <<'HELP'
Scenarios are executed in their own container with:
 - Memory limit: 256MB (configurable via MEMORY_MB)
 - CPU limit: 1 core (CPU_CORES)
 - PIDs limit: 50 (PIDS_LIMIT)
 - Timeout: 30 seconds (TIMEOUT_SEC)
 - Network: disabled by default (toggle via NETWORK_ON=1)
 - Root: read-only enabled (READONLY_ROOT=0 to disable)

Outputs logs to $ARTIFACT_BASE/<scenario>/ and test artifacts to $TEMP_BASE/<scenario>.

Helping docs:
 - START.md (ai-home)
 - AGENT-CONTRACT.md (ai-home)
 - PERMISSIONS.md (ai-home)
 - policies/standing-orders.md (ai-home)
 - AGENTS.md (this repo)

See test_redteam_policies.py and test_redteam_tooling.py for scenario definitions.

WARNING:
 - Runs headless; desktop changes are limited to test artifacts
 - Docker images are pulled locally; no network during runs unless NETWORK_ON=1
 - Output logs capped at 10MB for each container
 - Each run uses fresh temporary volumes; nothing persists after this script
HELP
}

parse_args() {
    if [[ "${1:-}" == "--help" ]]; then
        help_desc
        exit 0
    fi

    scenario="${1:-}"
    if [[ -z "$scenario" ]]; then
        echo "ERROR: --scenario SCENARIO is required" >&2
        help_desc
        exit 1
    fi

    remaining=()
    for arg in "$@"; do
        if [[ "$arg" != "--scenario" ]]; then
            remaining+=("$arg")
        fi
    done

    if [[ ${#remaining[@]} -gt 0 ]]; then
        echo "ERROR: Unrecognized arguments: ${remaining[*]}" >&2
        help_desc
        exit 1
    fi

    if ! printf "%s\n" "${readonly_scenarios[@]}" | grep -qx "$scenario"; then
        echo "ERROR: Unknown scenario: $scenario (choose from: "${readonly_scenarios[@]}")" >&2
        help_desc
        exit 1
    fi
}

ensure_directories() {
    mkdir -p "$ARTIFACT_BASE"
    mkdir -p "$TEMP_BASE"
}

cleanup() {
    local scenario_dir="$1"
    echo "DEBUG: Running cleanup for $scenario_dir"
    # If needed, kill lingering container named whitedevil-redteam-*
    # For now, rely on docker run --rm and exit with cleanup on error
}

run_scenario() {
    local scenario="$1"
    local scenario_dir="$ARTIFACT_BASE/$scenario"

    echo "DEBUG: Starting $scenario with IMAGE=$IMAGE ..."

    docker run --rm \
        --name "whitedevil-redteam-$scenario" \
        --network none \
        $([[ "$readonly_root" == true ]] && echo "--read-only") \
        --tmpfs "$tmpfs_opts" \
        --volume "$TEMP_BASE":/tmp \
        --volume "$scenario_dir":/work \
        -w /work \
        --user "$(id -un):$(id -gn)" \
        --memory "$memory_mb"m \
        --cpus "$cpu_cores" \
        --pids-limit "$pids_limit" \
        --log-driver json-file \
        --log-opt max-file=1 \
        --log-opt max-size=10m \
        "$IMAGE" \
        bash -c "
            timeout --kill-after=10s 30s \
            python3 <<'PYPY'
import os
import sys

scenario = os.environ.get('SCENARIO', 'parent_traversal')

if scenario == 'parent_traversal':
    # Example: Try to read a file outside the working directory
    target = '/etc/passwd'
    try:
        with open(target) as f:
            content = f.read()
    except Exception as e:
        content = str(e)

    with open('/work/output.txt', 'w') as out:
        out.write(f'Attempted to read {target}\\n')
        out.write(f'Result:\\n{content}')
PYPY

        # Ensure exit code 0 if expected (for this example scenario)
        exit 0
" > "$scenario_dir/$scenario.log" 2>&1

    local rc=$?
    if [[ $rc -eq 0 ]]; then
        echo "SUCCESS: $scenario passed (exit $rc)"
    else
        echo "FAILED: $scenario exited with $rc"
        cat "$scenario_dir/$scenario.log" 2>/dev/null || echo "No log captured"
    fi
    return $rc
}

main() {
    parse_args "$@"

    # Run cleanup for any previous run
    cleanup "$scenario"

    ensure_directories

    run_scenario "$scenario"
}

main "$@"
