# Migrating the relay from one shared password to per-device keys

**Read the whole page before you touch Caddy.** The dangerous part of this
migration is not the code, it is the order. Do step 5 before step 3 and the
phone stops working while you are away from the laptop, with no way to fix it
from the phone — because the thing you broke is the API the phone would have
used to fix it.

There is a recovery path for every step, and it is at the bottom. It needs SSH
to the VM. **If you cannot SSH to `wan-relay` right now, do not start.**

---

## 0. What is actually changing

Today: Caddy checks one HTTP basic-auth password (`relay_access.txt`) on the
public site. Every device holds the same password. FastAPI itself enforces
nothing — anything that gets past Caddy can call any `/api/*` route.

After: FastAPI decides, per request, via `GET /api/auth/forward`, which Caddy
calls with `forward_auth`. Each device holds its own keypair whose private half
never leaves its secure hardware (Android StrongBox, Windows Hello / TPM).
Revoking the laptop does not disturb the phone.

What is **not** changing in this migration:

- `require_device()` is still applied to **no route**. Route-level enforcement
  is a separate decision, taken later, deliberately.
- The device-auth protocol itself (`hub/auth.py`) is unchanged. Enrol a public
  key → `POST /challenge` → sign the nonce → `POST /token` → bearer token.
- `/laptop/term/*` and `/laptop/files/*` keep their own separate credentials.
  Nothing here touches them.

Three settings control the migration, all readable at `GET /api/auth/config`:

| Setting | Values | Default | What it does |
|---|---|---|---|
| `forward_auth_mode` | `permissive`, `basic_or_token`, `strict` | `permissive` | What `/api/auth/forward` accepts |
| `require_enrol_code` | `true` / `false` | `false` | Whether `POST /api/auth/devices` needs a single-use code |
| basic credential | `HUB_BASIC_AUTH_USER` + `HUB_BASIC_AUTH_PASSWORD` | unset | The hub's own copy of the relay password, used only in `basic_or_token` |

`forward_auth_mode` in detail:

- **`permissive`** — a valid device token, **or any `Authorization: Basic`
  header at all**. This is only safe while Caddy is *still* running
  `basic_auth` in front, because Caddy has already rejected a wrong password;
  anything arriving with a Basic header was vouched for by Caddy. Never run
  `permissive` with Caddy's `basic_auth` removed.
- **`basic_or_token`** — a valid device token, or a Basic header the **hub**
  verifies itself against `HUB_BASIC_AUTH_USER` / `HUB_BASIC_AUTH_PASSWORD`.
  This is the mode to sit in while Caddy's `basic_auth` is gone but some client
  (a browser, the Compose Desktop app) still has no device key. If the
  credential is not configured, this mode returns **503**, not 200 — it refuses
  rather than silently degrading to "any Basic header".
- **`strict`** — device token only. Basic is refused with no
  `WWW-Authenticate` header, so a browser will not sit in a re-prompt loop.

The hub refuses to set `basic_or_token` or `strict` while **zero** devices are
enrolled (409). That guard exists because it is the exact footgun this document
is about.

### Two safety properties worth knowing before you start

1. **`/api/auth/*` cannot live behind `forward_auth`.** A device has to reach
   `/challenge` and `/token` before it has a token to present. So those routes
   stay open to anything Caddy passes, and are defended instead by the rate
   limits in §5 and by the enrolment code in step 3.
2. **The three routes that can change who gets in guard themselves.**
   `DELETE /api/auth/devices/{id}`, `POST /api/auth/enrol-code` and
   `POST /api/auth/config` run `require_admin`. Under `permissive` that is a
   no-op — Caddy's `basic_auth` is still in front. Under `basic_or_token` and
   `strict` they demand the same credential `forward_auth` would, so removing
   `basic_auth` from Caddy does not hand revocation and config to the internet.
   A device that has just been revoked cannot use its old token to administer.

---

## 1. The Caddyfile diff

> The Caddyfile lives on the VM (`/etc/caddy/Caddyfile`), not in this repo, so
> the "before" below is the configuration as described by
> `laptop-app/README.md` and `hub/app.py`. **Diff it against your real file
> before applying** — if your site block differs, keep your version and take
> only the `forward_auth` addition and the `basic_auth` removal.

### Before

```caddyfile
84-12-112-249.sslip.io {
	encode zstd gzip

	# ttyd and the file bridge keep their own credentials — leave untouched.
	handle /laptop/term/* {
		reverse_proxy 127.0.0.1:7681
	}

	basic_auth {
		relay $2a$14$REPLACE_WITH_YOUR_EXISTING_HASH
	}

	reverse_proxy 127.0.0.1:9000
}
```

### After

```caddyfile
84-12-112-249.sslip.io {
	encode zstd gzip

	handle /laptop/term/* {
		reverse_proxy 127.0.0.1:7681
	}

	# The device-auth endpoints must NOT be behind forward_auth: you cannot
	# present a device token until you have one, and this is where you get one.
	# They are rate-limited in FastAPI (hub/auth.py RATE_LIMITS) precisely
	# because they are the one unauthenticated surface, and the three that can
	# change who gets in (revoke / enrol-code / config) guard themselves via
	# require_admin once the mode is no longer `permissive`.
	handle /api/auth/* {
		reverse_proxy 127.0.0.1:9000
	}

	# Everything else under /api is decided by FastAPI.
	handle /api/* {
		forward_auth 127.0.0.1:9000 {
			uri /api/auth/forward
			copy_headers X-Device-Id X-Device-Name X-Auth-Method
		}
		reverse_proxy 127.0.0.1:9000
	}

	handle {
		reverse_proxy 127.0.0.1:9000
	}
}
```

The `basic_auth` block is **deleted** only at step 5, not when you first add
`forward_auth`. Steps 3 and 4 run with **both** present.

`forward_auth` sends a `GET` with the original request's headers and expects
2xx to allow, anything else to deny. `copy_headers` passes the authenticated
device through to the hub so logs can say which device did what.

### Note on the static UI

The diff above leaves `/app/*` (the web UI) outside `forward_auth`, guarded by
`basic_auth` until step 5 and then by nothing. If the UI must stay behind a
password after step 5, keep a second `basic_auth` block scoped to `handle
/app/*` with its own hash. Decide this **before** step 5 — do not discover it
afterwards.

---

## 2. Order of operations

Each step has a check. **Do not proceed past a check that did not pass.**
Every `curl` below runs from the laptop, against the public site.

Set these once:

```bash
RELAY=https://84-12-112-249.sslip.io
AUTH='-u relay:THE_RELAY_PASSWORD'
```

### Step 1 — deploy the code, change nothing

```bash
ssh wan-relay
cd ~/hub && git pull && .venv/bin/pip install -r requirements.txt
sudo systemctl restart forge-hub
```

`python-multipart` and `cryptography` are both in `requirements.txt`. Without
`python-multipart`, `hub/ltx.py` fails to import and `app.py::_mount()` swallows
it silently — the LTX screen goes missing with no error. Check both mounted:

```bash
journalctl -u forge-hub -n 50 --no-pager | grep -E 'mounted|not loaded'
```

**Check:** everything still works exactly as before, because nothing is
enforced yet.

```bash
curl -s $AUTH $RELAY/api/auth/config
# expect: "forward_auth_mode":"permissive","require_enrol_code":false,"devices_enrolled":N
```

### Step 2 — enrol both devices, and prove both can sign

This is the step people rush. **Both** devices must produce a token before you
touch Caddy at all.

- **Phone:** Android app → device enrolment. It uses StrongBox (ES256).
- **Laptop:** Compose Desktop app → device enrolment. It uses Windows Hello
  (RS256, PKCS#1 v1.5 over SHA-256).

**Check:** two devices listed, and each one round-trips a challenge into a
token *from its own hardware* (a fingerprint/PIN prompt should appear on each):

```bash
curl -s $AUTH $RELAY/api/auth/devices
# expect two entries with non-null "last_seen" after each has signed in once
```

A device with `"last_seen": null` has enrolled but has **never proved it can
sign**. That is not enrolled for the purposes of this migration. Go make it
sign before continuing.

### Step 3 — turn on enrolment codes

Now that both real devices are in, close the door behind them: a leaked relay
password alone should not be able to add a third.

```bash
curl -s $AUTH -X POST $RELAY/api/auth/config \
     -H 'content-type: application/json' -d '{"require_enrol_code":true}'
```

**Check (negative — this is the one that matters):**

```bash
curl -s -o /dev/null -w '%{http_code}\n' $AUTH -X POST $RELAY/api/auth/devices \
     -H 'content-type: application/json' \
     -d '{"name":"should-not-work","public_key_pem":"-----BEGIN PUBLIC KEY-----\nMFkw...\n-----END PUBLIC KEY-----\n"}'
# expect: 403
```

To enrol a legitimate third device later: `POST /api/auth/enrol-code` returns a
code good for 15 minutes and **one** use, which you pass as `enrol_code` in the
enrolment body.

### Step 4 — add `forward_auth` to Caddy, keeping `basic_auth`

Apply the Caddyfile diff **except** the `basic_auth` deletion. Both are live.
Mode is still `permissive`, so `/api/auth/forward` returns 200 for a device
token *and* for anything Caddy already let through.

```bash
sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy
```

**Check:** nothing broke, and forward-auth is genuinely in the path.

```bash
curl -s -o /dev/null -w '%{http_code}\n' $AUTH $RELAY/api/colab/state   # 200
curl -s -o /dev/null -w '%{http_code}\n'       $RELAY/api/colab/state   # 401
journalctl -u forge-hub -n 20 --no-pager | grep -c '/api/auth/forward'  # > 0
```

**Now go use the phone for a few minutes.** If anything is going to break, it
breaks here, and here you can still roll back by reloading the old Caddyfile.

### Step 5 — give the hub the password, then remove it from Caddy

Order inside this step matters too. The hub gets its own copy of the password
**before** Caddy stops checking it, or there is a window with no fallback.

```bash
ssh wan-relay
sudo systemctl edit forge-hub
```

```ini
[Service]
Environment=HUB_BASIC_AUTH_USER=relay
Environment=HUB_BASIC_AUTH_PASSWORD=THE_RELAY_PASSWORD
```

```bash
sudo systemctl restart forge-hub
curl -s $AUTH $RELAY/api/auth/config | grep basic_credential_configured
# expect: "basic_credential_configured":true
```

Only once that says `true`:

```bash
curl -s $AUTH -X POST $RELAY/api/auth/config \
     -H 'content-type: application/json' -d '{"forward_auth_mode":"basic_or_token"}'
```

**Check, still with Caddy's `basic_auth` in place:**

```bash
curl -s -o /dev/null -w '%{http_code}\n' $AUTH $RELAY/api/auth/forward        # 200
curl -s -o /dev/null -w '%{http_code}\n' -u relay:wrong $RELAY/api/auth/forward # 401
```

The second one proves the *hub* is now checking the password, not just Caddy.
Only after both checks pass, delete the `basic_auth` block from the Caddyfile:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy
```

**Check:** phone works, desktop app works, a wrong password is refused.

```bash
curl -s -o /dev/null -w '%{http_code}\n' -u relay:wrong $RELAY/api/colab/state  # 401
curl -s -o /dev/null -w '%{http_code}\n' $AUTH          $RELAY/api/colab/state  # 200
```

### Step 6 — `strict`, and only when you mean it

`strict` removes the password fallback entirely. **Every** client must hold a
device token, including any browser tab you use the UI from. Do not do this on
a day you are away from the laptop.

```bash
curl -s $AUTH -X POST $RELAY/api/auth/config \
     -H 'content-type: application/json' -d '{"forward_auth_mode":"strict"}'
```

The hub refuses this with 409 if no devices are enrolled.

### Step 7 (later, separate decision) — `require_device()` on routes

Not part of this migration. `forward_auth` already gates every `/api/*` request
at the edge; adding `require_device()` to routes is defence in depth for the
day something reaches uvicorn without going through Caddy. It is the operator's
switch to throw, after steps 1–6 have been stable for a while.

---

## 3. Recovery — you are locked out of the API, remotely

Every one of these needs SSH to `wan-relay` and nothing else. None of them need
the phone, the laptop app, or a working API.

### The one-line unlock

The environment beats the config file on purpose, for exactly this:

```bash
ssh wan-relay
sudo systemctl edit forge-hub     # add: Environment=HUB_FORWARD_AUTH_MODE=permissive
sudo systemctl restart forge-hub
```

`permissive` accepts any Basic header, so if Caddy's `basic_auth` is already
gone this is **wide open** — it is an unlock, not a resting state. Use
`basic_or_token` instead if the hub has a credential configured:

```bash
Environment=HUB_FORWARD_AUTH_MODE=basic_or_token
```

Undo it by removing the `Environment=` line and restarting; the config file
value takes over again.

### Symptom → fix

| Symptom | Cause | Fix |
|---|---|---|
| Everything 401s right after flipping to `strict` | no client has a device token | env var → `permissive`, restart, then enrol properly |
| Everything 503s with "no basic-auth credential" | `basic_or_token` set, `HUB_BASIC_AUTH_*` unset or lost on a restart | set the two `Environment=` lines, restart |
| Only the phone 401s | its device was revoked, or its token expired and it cannot re-sign | `curl -s localhost:9000/api/auth/devices` on the VM; re-enrol from the phone |
| 429 everywhere, no one can sign in | rate limiter tripped (a client hot-looping `/challenge`) | `sudo systemctl restart forge-hub` — buckets are in memory and clear on restart; tokens are not lost, they persist |
| Enrolment 403s with "code required" and you cannot get a code | `require_enrol_code=true` and no trusted session | `Environment=HUB_REQUIRE_ENROL_CODE=0`, restart, enrol, remove the line |
| Caddy returns 502 on all `/api/*` | `forge-hub` is down, so `forward_auth` cannot answer | `journalctl -u forge-hub -n 100`; usually a missing dependency after a `git pull` |

### Full stop — put it back exactly as it was

```bash
ssh wan-relay
# 1. Caddy: restore basic_auth, drop the forward_auth/handle blocks
sudo cp /etc/caddy/Caddyfile.bak /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy
# 2. Hub: back to the additive defaults
rm -f ~/hub/auth_data/auth_config.json
sudo systemctl restart forge-hub
```

Deleting `auth_config.json` returns both settings to their defaults
(`permissive`, `require_enrol_code=false`). Enrolled devices and live tokens are
in `devices.json` and `tokens.json` and are **not** affected.

> Take that `.bak` before step 4: `sudo cp /etc/caddy/Caddyfile{,.bak}`.

### Emergency: revoke a device you no longer trust

Straight against the hub on the VM, bypassing Caddy entirely:

```bash
ssh wan-relay
curl -s localhost:9000/api/auth/devices
curl -s -X DELETE localhost:9000/api/auth/devices/<id>
```

Revocation is immediate and now survives a restart — the device's live tokens
are purged from `tokens.json`, not just from memory.

---

## 4. State on disk

All under `~/hub/auth_data/` on the VM, all written tmp-file + `os.replace`
under a lock (the pattern from `hub/agentic/store.py:150`), so a crash
mid-write cannot leave a truncated file where a valid one used to be.

| File | Holds | Losing it means |
|---|---|---|
| `devices.json` | device ids, names, **public** keys | every device must re-enrol |
| `tokens.json` | SHA-256 **hashes** of live tokens + expiry | every device signs in again (a fingerprint prompt), nothing worse |
| `enrol_codes.json` | SHA-256 hashes of unspent enrolment codes | outstanding codes stop working; issue new ones |
| `auth_config.json` | the two settings above | back to permissive defaults |

None of these contain a secret you could present as a credential: public keys
are public, and tokens and codes are stored hashed. A corrupt or truncated file
fails **closed** — it is treated as empty rather than crashing the hub.

Challenges are deliberately **not** persisted. They live 120 seconds and a
restart simply makes a client ask for a new one.

## 5. Rate limits

Sliding window, in memory, cleared by a restart. Two buckets are checked on
every request, because they stop different attacks: the per-device bucket stops
one stolen device id being hammered, the per-IP bucket stops one host cycling
through every id. Failed signature attempts count — counting only successes
would leave the grind unbounded.

| Endpoint | Per device | Per IP |
|---|---|---|
| `POST /api/auth/challenge` | 10 / 60s | 30 / 60s |
| `POST /api/auth/token` | 10 / 60s | 30 / 60s |
| `POST /api/auth/enrol-code` | — | 10 / 60s |

A 429 always carries `Retry-After`. The per-IP bucket keys on the first hop of
`X-Forwarded-For`, which is correct here because nothing but Caddy can reach
uvicorn on `127.0.0.1:9000` — if you ever expose the port directly, that
assumption breaks and the header becomes attacker-controlled.

Tune them in `hub/auth.py` (`RATE_LIMITS`) and restart.
