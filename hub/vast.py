"""Vast.ai screen API: rent, start, stop and destroy GPU instances through console.vast.ai.

The API key lives only on the relay in ~/.vast_api_key. Unlike Thunder, Vast can stop an instance
(the GPU is released, the disk keeps billing) and start it again if the machine is free.
"""
import json as jsonlib
import re
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

router = APIRouter(prefix="/api/vast")
BASE = "https://console.vast.ai/api/v0"
V1 = "https://console.vast.ai/api/v1"
KEY = Path.home() / ".vast_api_key"
SSH_KEY = Path.home() / ".ssh" / "thunder_instance"
IMAGE = "pytorch/pytorch:2.7.0-cuda12.8-cudnn9-runtime"
MIN_CUDA = 12.8
ID = re.compile(r"^\d{1,12}$")
_offers = {"at": 0, "key": None, "data": []}


def call(method, path, json=None, timeout=30):
    try:
        key = KEY.read_text().strip()
    except FileNotFoundError:
        raise HTTPException(500, "No Vast API key on the relay (~/.vast_api_key).")
    req = urllib.request.Request(path if path.startswith("http") else BASE + path, method=method,
                                 data=jsonlib.dumps(json).encode() if json is not None else None,
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json",
                                          "User-Agent": "forge-hub"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            code, text = r.status, r.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        code, text = e.code, e.read().decode(errors="replace")
    except (urllib.error.URLError, TimeoutError, OSError):
        raise HTTPException(503, "Vast isn't answering right now.")
    if code == 401 or code == 403:
        raise HTTPException(401, "Vast rejected the API key; make a new one under Account > Keys.")
    if code >= 500:
        raise HTTPException(503, f"Vast is having trouble (HTTP {code}).")
    try:
        data = jsonlib.loads(text)
    except ValueError:
        data = {"text": text}
    if code >= 400:
        msg = data.get("msg") or data.get("error") or text if isinstance(data, dict) else text
        raise HTTPException(code, str(msg)[:400])
    return data


def ssh_addr(i):
    """Direct port if the host has one, else Vast's SSH proxy."""
    p = (i.get("ports") or {}).get("22/tcp")
    if p and i.get("public_ipaddr"):
        return i["public_ipaddr"].strip(), int(p[0]["HostPort"])
    if i.get("ssh_host") and i.get("ssh_port"):
        return i["ssh_host"], int(i["ssh_port"])
    return None, None


def slim(i):
    host, port = ssh_addr(i)
    disk = float(i.get("disk_space") or 0)
    storage = float(i.get("storage_cost") or 0)
    return {"id": str(i["id"]), "label": i.get("label") or "", "gpu": i.get("gpu_name"), "num_gpus": i.get("num_gpus") or 1,
            "vram_gb": round((i.get("gpu_ram") or 0) / 1024), "status": i.get("actual_status") or "loading",
            "intended": i.get("intended_status"), "status_msg": (i.get("status_msg") or "")[:200],
            "price": round(float(i.get("dph_total") or 0), 3),
            "stopped_price": round(disk * storage / 730, 3) if storage else None,
            "disk_gb": round(disk), "location": i.get("geolocation"), "image": i.get("image_uuid"),
            "host": host, "port": port, "started": i.get("start_date"), "cuda": i.get("cuda_max_good"),
            "gpu_util": i.get("gpu_util"), "disk_used_gb": i.get("disk_usage")}


def instances():
    d = call("GET", V1 + "/instances/")
    raw = d.get("instances") if isinstance(d, dict) else d
    if not isinstance(raw, list):
        raise HTTPException(502, "Vast sent an instance list this Hub cannot read.")
    return [slim(i) for i in raw if isinstance(i, dict) and i.get("id") is not None]


def instance(iid):
    d = call("GET", f"/instances/{iid}/")
    i = d.get("instances") if isinstance(d, dict) else None
    return slim(i) if isinstance(i, dict) else None


@router.get("/state")
def state():
    user = call("GET", "/users/current/")
    if not isinstance(user, dict):
        raise HTTPException(502, "Vast sent an account payload this Hub cannot read.")
    try:
        credit = round(float(user.get("credit") or 0), 2)
    except (TypeError, ValueError):
        credit = 0.0
    return {"credit": credit, "instances": instances(), "image": IMAGE}


def offer_price(o, disk_gb):
    return round(float(o.get("dph_base") or 0) + disk_gb * float(o.get("storage_cost") or 0) / 730, 3)


def search(min_vram=40, gpu=None, max_price=None, disk_gb=150, num_gpus=1, limit=40, offer_id=None):
    q = {"rentable": {"eq": True}, "rented": {"eq": False}, "verified": {"eq": True}, "type": "on-demand",
         "num_gpus": {"eq": num_gpus}, "gpu_ram": {"gte": int(min_vram * 1000)}, "disk_space": {"gte": disk_gb},
         "reliability2": {"gte": 0.97}, "inet_down": {"gte": 300}, "cuda_max_good": {"gte": MIN_CUDA},
         "order": [["dph_total", "asc"]], "limit": limit}
    if gpu:
        q["gpu_name"] = {"eq": gpu}
    if offer_id:
        q = {"ask_contract_id": {"eq": int(offer_id)}}
    body = call("POST", "/bundles/", q, timeout=60)
    found = body.get("offers") if isinstance(body, dict) else None
    if not isinstance(found, list):
        raise HTTPException(502, "Vast sent an offer list this Hub cannot read.")
    out = []
    for o in (x for x in found if isinstance(x, dict) and x.get("id") is not None):
        price = offer_price(o, disk_gb)
        if max_price and price > max_price:
            continue
        out.append({"id": str(o["id"]), "gpu": o.get("gpu_name"), "num_gpus": o.get("num_gpus"),
                    "vram_gb": round((o.get("gpu_ram") or 0) / 1024), "price": price,
                    "stopped_price": round(disk_gb * float(o.get("storage_cost") or 0) / 730, 3),
                    "disk_max_gb": round(o.get("disk_space") or 0), "location": o.get("geolocation"),
                    "reliability": round(float(o.get("reliability2") or 0) * 100, 1),
                    "down_mbps": round(o.get("inet_down") or 0), "cpu_cores": round(o.get("cpu_cores_effective") or 0),
                    "ram_gb": round((o.get("cpu_ram") or 0) / 1024), "cuda": o.get("cuda_max_good"),
                    "rentable": bool(o.get("rentable")) and not o.get("rented")})
    return out


@router.get("/offers")
def offers(min_vram: float = 40, gpu: Optional[str] = None, max_price: Optional[float] = None,
           disk_gb: int = 150, num_gpus: int = 1):
    key = (min_vram, gpu, max_price, disk_gb, num_gpus)
    if _offers["key"] == key and time.time() - _offers["at"] < 60:
        return _offers["data"]
    data = search(min_vram, gpu or None, max_price, disk_gb, num_gpus)
    _offers.update(at=time.time(), key=key, data=data)
    return data


def ensure_ssh_key():
    if not SSH_KEY.is_file():
        raise HTTPException(500, f"The relay has no SSH key at {SSH_KEY}; Vast machines cannot be rented "
                                 f"without it.")
    try:
        r = subprocess.run(["ssh-keygen", "-y", "-f", str(SSH_KEY)], capture_output=True, text=True,
                           stdin=subprocess.DEVNULL, timeout=20)
    except subprocess.TimeoutExpired:
        raise HTTPException(500, "ssh-keygen did not finish in 20s (is the relay key passphrase-protected?)")
    except OSError as e:
        raise HTTPException(500, f"could not run ssh-keygen: {e}")
    if r.returncode != 0:
        raise HTTPException(500, "Could not read the relay SSH key: " + (r.stderr or "").strip()[:200])
    pub = r.stdout.strip()
    parts = pub.split()
    if len(parts) < 2:
        raise HTTPException(500, f"ssh-keygen produced no usable public key from {SSH_KEY}.")
    d = call("GET", "/ssh/")
    keys = d.get("ssh_keys", []) if isinstance(d, dict) else (d if isinstance(d, list) else [])
    body = parts[1]
    if not any(body in (k.get("public_key") or k.get("ssh_key") or "") for k in keys):
        call("POST", "/ssh/", {"ssh_key": pub})
    return pub


class Rent(BaseModel):
    offer_id: str
    disk_gb: int = 150
    label: Optional[str] = None
    image: Optional[str] = None


def rent(r: Rent):
    if not ID.match(r.offer_id):
        raise HTTPException(400, "Bad offer id")
    pub = ensure_ssh_key()
    res = call("PUT", f"/asks/{r.offer_id}/", {
        "client_id": "me", "image": r.image or IMAGE, "disk": int(r.disk_gb), "runtype": "ssh_direct ssh_proxy",
        "label": (r.label or "forge")[:60], "onstart": "mkdir -p /workspace", "ssh_key": pub}, timeout=60)
    if not isinstance(res, dict) or not res.get("success") or not res.get("new_contract"):
        msg = res.get("msg") if isinstance(res, dict) else None
        raise HTTPException(409, msg or "Vast didn't accept the rental; the offer may be gone.")
    return {"id": str(res["new_contract"])}


@router.post("/instances")
def create(r: Rent):
    return rent(r)


def check(i):
    if not ID.match(i):
        raise HTTPException(400, "Bad id")
    return i


@router.post("/instances/{iid}/start")
def start(iid: str):
    return call("PUT", f"/instances/{check(iid)}/", {"state": "running"})


@router.post("/instances/{iid}/stop")
def stop(iid: str):
    return call("PUT", f"/instances/{check(iid)}/", {"state": "stopped"})


@router.post("/instances/{iid}/delete")
def delete(iid: str):
    return call("DELETE", f"/instances/{check(iid)}/")


class Label(BaseModel):
    label: str


@router.post("/instances/{iid}/label")
def label(iid: str, l: Label):
    return call("PUT", f"/instances/{check(iid)}/", {"label": l.label[:60]})
