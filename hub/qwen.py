"""Qwen API operations router for Forge Hub.

Monitors gateway health and model readiness, and integrates with Vast VM lifecycle
for GPU host power and billing when configured.
Does NOT expose a general shell endpoint.
"""
from __future__ import annotations

import json
import logging
import os
import re
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

log = logging.getLogger("forge-hub.qwen")
router = APIRouter(prefix="/api/qwen")

CONFIG_PATH = Path.home() / ".config" / "qwen-api" / "relay.json"
DEFAULT_GATEWAY_URL = "http://127.0.0.1:18080"
ID_PATTERN = re.compile(r"^\d{1,12}$")


class QwenVmActionRequest(BaseModel):
    instance_id: str
    action: str  # "start" or "stop"


def _read_config() -> dict[str, Any]:
    cfg: dict[str, Any] = {
        "gateway_url": os.environ.get("QWEN_GATEWAY_URL", DEFAULT_GATEWAY_URL),
        "client_key": os.environ.get("QWEN_CLIENT_KEY", ""),
        "vast_instance_id": os.environ.get("QWEN_VAST_INSTANCE_ID", ""),
    }
    if CONFIG_PATH.is_file():
        try:
            data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                cfg.update({k: v for k, v in data.items() if v is not None})
        except Exception as e:
            log.warning("Could not read Qwen config from %s: %s", CONFIG_PATH, e)
    return cfg


def _probe_http(url: str, headers: Optional[dict[str, str]] = None, timeout: float = 4.0) -> tuple[int, str, float]:
    """Sends a probe GET request and returns (status_code, body_snippet, elapsed_ms)."""
    t0 = time.monotonic()
    req = urllib.request.Request(url, headers=headers or {}, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            elapsed = (time.monotonic() - t0) * 1000.0
            body = resp.read().decode("utf-8", errors="replace")[:500]
            return resp.status, body, round(elapsed, 1)
    except urllib.error.HTTPError as e:
        elapsed = (time.monotonic() - t0) * 1000.0
        body = e.read().decode("utf-8", errors="replace")[:500]
        return e.code, body, round(elapsed, 1)
    except Exception as e:
        elapsed = (time.monotonic() - t0) * 1000.0
        return 0, str(e)[:300], round(elapsed, 1)


@router.get("/state")
def get_state(
    gateway_url: Optional[str] = Query(None, description="Override gateway URL to probe"),
    vast_instance_id: Optional[str] = Query(None, description="Associated Vast instance ID"),
):
    """Returns the operational status of the Qwen API and underlying host.

    Distinguishes gateway alive (healthz) from model ready (readyz).
    Never exposes API keys or secrets in the payload.
    """
    cfg = _read_config()
    target_url = (gateway_url or cfg.get("gateway_url") or DEFAULT_GATEWAY_URL).rstrip("/")
    client_key = cfg.get("client_key") or ""
    vast_id = vast_instance_id or cfg.get("vast_instance_id") or ""

    now = int(time.time())
    gateway_alive = False
    model_ready = False
    health_latency_ms: Optional[float] = None
    ready_latency_ms: Optional[float] = None
    active_requests: Optional[int] = None
    max_concurrency: Optional[int] = None
    model_alias = "qwen-agent"
    error_msg: Optional[str] = None

    # Step 1: Probe Gateway Healthz (Liveness - no auth required)
    h_code, h_body, h_lat = _probe_http(f"{target_url}/healthz", timeout=3.5)
    health_latency_ms = h_lat
    if h_code == 200:
        gateway_alive = True
    elif h_code == 0:
        error_msg = f"Gateway unreachable: {h_body}"
    else:
        error_msg = f"Gateway /healthz returned HTTP {h_code}"

    # Step 2: Probe Model Readiness (Readyz - requires auth if keys configured)
    if gateway_alive:
        r_headers = {}
        if client_key:
            r_headers["Authorization"] = f"Bearer {client_key}"
        r_code, r_body, r_lat = _probe_http(f"{target_url}/readyz", headers=r_headers, timeout=5.0)
        ready_latency_ms = r_lat
        if r_code == 200:
            model_ready = True
            try:
                data = json.loads(r_body)
                if isinstance(data, dict):
                    entries = data.get("data")
                    if isinstance(entries, list) and entries and isinstance(entries[0], dict) and entries[0].get("id"):
                        model_alias = str(entries[0]["id"])
                    elif data.get("model"):
                        model_alias = str(data["model"])
            except Exception:
                pass
        elif r_code == 503:
            error_msg = "Model upstream not ready or loading"
        elif r_code == 401 or r_code == 403:
            error_msg = "Gateway rejected authentication (invalid or missing client key)"
        else:
            error_msg = f"Gateway /readyz returned HTTP {r_code}: {r_body[:100]}"

        # Step 3: Optional Status metrics (only if ready and auth present)
        if model_ready and client_key:
            s_code, s_body, _ = _probe_http(f"{target_url}/status", headers={"Authorization": f"Bearer {client_key}"}, timeout=2.0)
            if s_code == 200:
                try:
                    s_data = json.loads(s_body)
                    if isinstance(s_data, dict):
                        counters = s_data.get("counters_since_restart")
                        if isinstance(counters, dict) and isinstance(counters.get("active"), int):
                            active_requests = counters["active"]
                        else:
                            active_requests = s_data.get("active_requests")
                        max_concurrency = s_data.get("max_concurrency")
                except Exception:
                    pass

    # Overall state evaluation:
    if model_ready:
        status = "ready"
    elif gateway_alive:
        status = "loading"
    else:
        status = "offline"

    # Step 4: Vast VM info if instance ID provided
    vm_info: Optional[dict[str, Any]] = None
    if vast_id and ID_PATTERN.match(str(vast_id)):
        try:
            import vast
            inst = vast.instance(str(vast_id))
            if inst:
                vm_info = {
                    "instance_id": str(inst.get("id")),
                    "status": inst.get("status"),
                    "gpu": inst.get("gpu"),
                    "vram_gb": inst.get("vram_gb"),
                    "price_per_hour": inst.get("price"),
                    "stopped_price_per_hour": inst.get("stopped_price"),
                    "host": inst.get("host"),
                    "port": inst.get("port"),
                }
        except Exception as e:
            log.debug("Vast lookup failed for %s: %s", vast_id, e)

    return {
        "status": status,
        "gateway_alive": gateway_alive,
        "model_ready": model_ready,
        "model_alias": model_alias,
        "gateway_url": target_url,
        "health_latency_ms": health_latency_ms,
        "ready_latency_ms": ready_latency_ms,
        "active_requests": active_requests,
        "max_concurrency": max_concurrency,
        "last_checked_epoch": now,
        "error": error_msg,
        "vm": vm_info,
    }


@router.post("/vm/power")
def control_vm(req: QwenVmActionRequest):
    """Controls the power state (start or stop) of the GPU host through Vast.

    Refuses any unknown action; no shell execution.
    """
    if not ID_PATTERN.match(req.instance_id):
        raise HTTPException(400, "Invalid instance ID format")
    if req.action not in ("start", "stop"):
        raise HTTPException(400, f"Unsupported action '{req.action}'. Allowed: 'start', 'stop'")

    try:
        import vast
        if req.action == "start":
            res = vast.start(req.instance_id)
        else:
            res = vast.stop(req.instance_id)
        return {"success": True, "action": req.action, "result": res}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(502, f"Failed to execute VM {req.action}: {e}")
