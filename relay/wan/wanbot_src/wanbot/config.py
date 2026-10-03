import copy
import os
import re
import secrets

import yaml

DEFAULTS = {
    "server": {"host": "0.0.0.0", "port": 8000, "token": None, "cors_origins": ["*"]},
    "paths": {"work_dir": "./work", "outputs_dir": None, "inbox_dir": None},
    "openrouter": {
        "api_key": None,
        "model": "cognitivecomputations/dolphin-mistral-24b-venice-edition",
        "temperature": 0.7,
    },
    "defaults": {"seed": 42, "steps": 50, "guide_scale": 5.0, "shift": 5.0},
    "default_model": None,
    "models": {},
}

_ENV = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}")


def _expand(v):
    if isinstance(v, str):
        if not _ENV.search(v):
            return v
        out = _ENV.sub(lambda m: os.environ.get(m.group(1), m.group(2) if m.group(2) is not None else ""), v)
        return out if out != "" else None
    if isinstance(v, dict):
        return {k: _expand(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_expand(x) for x in v]
    return v


def _merge(a, b):
    out = copy.deepcopy(a)
    for k, v in (b or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out


def load_config(path=None):
    cfg = copy.deepcopy(DEFAULTS)
    base_dir = os.getcwd()
    if path:
        path = os.path.abspath(path)
        base_dir = os.path.dirname(path)
        with open(path, "r", encoding="utf-8") as f:
            cfg = _merge(cfg, yaml.safe_load(f) or {})
    cfg = _expand(cfg)

    env = os.environ
    if env.get("WANBOT_TOKEN"):
        cfg["server"]["token"] = env["WANBOT_TOKEN"]
    if env.get("OPENROUTER_API_KEY") and not cfg["openrouter"].get("api_key"):
        cfg["openrouter"]["api_key"] = env["OPENROUTER_API_KEY"]
    for key, var in (("work_dir", "WANBOT_WORK_DIR"), ("outputs_dir", "WANBOT_OUTPUTS_DIR"), ("inbox_dir", "WANBOT_INBOX_DIR")):
        if env.get(var):
            cfg["paths"][key] = env[var]
    if env.get("WANBOT_PORT"):
        cfg["server"]["port"] = int(env["WANBOT_PORT"])

    cfg["_token_generated"] = False
    if not cfg["server"].get("token"):
        cfg["server"]["token"] = secrets.token_urlsafe(24)
        cfg["_token_generated"] = True

    for key in ("work_dir", "outputs_dir", "inbox_dir"):
        p = cfg["paths"].get(key)
        if p:
            p = os.path.abspath(os.path.join(base_dir, os.path.expanduser(p)))
            cfg["paths"][key] = p
            os.makedirs(p, exist_ok=True)

    if not cfg["models"]:
        raise ValueError("No models defined in config. Add at least one entry under 'models:'.")
    if not cfg.get("default_model") or cfg["default_model"] not in cfg["models"]:
        cfg["default_model"] = next(iter(cfg["models"]))
    cfg["_base_dir"] = base_dir
    return cfg
