# Device-auth migration runbook

Moves the relay (`https://84-12-112-249.sslip.io`, VM `wan-relay`) from **one shared
Caddy basic-auth password** to **per-device keys enforced by the hub**, without ever
removing the path you are currently using to reach it.

Read the whole document before touching anything. The dangerous mistake is not a typo,
it is the *order*: removing basic auth from Caddy before the hub can authenticate the
phone leaves the phone with no way in, and you may be away from the laptop when you find
out.

## What is being changed

| Layer | Today | After |
|---|---|---|
| Caddy, `/api/*` | `basic_auth` (shared password) | `forward_auth` → hub's `/api/auth/verify` |
| Caddy, everything else (`/app/*`, `/laptop/term/*`, `comfy.` site) | `basic_auth` | **unchanged** |
| Hub | enforces nothing | decides per request: device token, or (permissive) the legacy password |
| Enrolment | anything past Caddy | needs a single-use code minted on the VM |

`HUB_AUTH_MODE` is the switch:

- `permissive` (default): `/verify` accepts a device token **or** the legacy password.
- `device-only`: tokens only. Basic auth is refused by the hub.

The Caddyfile changes **once** (Stage A). Going to device-only is an environment
variable and a service restart. **It is not a Caddy change**, which is what makes it
recoverable over SSH in seconds.

## Facts this runbook depends on

- The Caddyfile is **not in this repository**; it lives on the VM. The "before" below is
  reconstructed from the repo (hub on `127.0.0.1:9000`, ttyd under `/laptop/term/`, basic
  user `wan`, a `comfy.` subdomain). **Diff against your real file and adapt**; do not paste
  blindly. Keep your existing site address, hash and any other blocks.
- `forward_auth` needs Caddy **2.5+**. Check first: `caddy version`.
- The hub runs as `ubuntu` from `/home/ubuntu/hub` (`forge-hub.service`). Its auth state is
  in `/home/ubuntu/hub/auth_data/`. **Run the CLI as `ubuntu`**, never with `sudo`, or it
  will read/write `/root/hub/auth_data` and appear to "lose" every device.
- SSH to the VM (port 22) does not pass through Caddy. Nothing here touches sshd, the OCI
  security list, or the firewall. **Do not add that to this change.**

## 0. Pre-flight (do not skip)

1. Open **two** SSH sessions to the VM, from a machine whose SSH access does not depend on
   the relay. Leave both open until the migration is finished. A reload can never drop an
   established SSH session.
2. Confirm your last-resort access works *before* you need it: OCI Console → the instance →
   Console connection / Cloud Shell. Just check you can log in.
3. Back up, on the VM:
   ```bash
   sudo cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.pre-device-auth
   cp -a ~/hub ~/hub.pre-device-auth        # code + data, small
   caddy version                            # must be >= 2.5
   ```
4. Write down (paper is fine) the exact restore commands from **Recovery** below.
5. Do this with the phone physically at hand, on **mobile data as well as Wi-Fi**, so you
   can test from outside.

## 1. Deploy the hub code — no behaviour change for the phone

```bash
cd ~/hub && git pull            # or however the hub is normally deployed
.venv/bin/pip install -r requirements.txt     # adds bcrypt
sudo systemctl restart forge-hub
systemctl is-active forge-hub
```

Nothing enforces anything yet (`require_device` is on no route; Caddy still does basic
auth). One thing does change immediately: **`POST /api/auth/devices` now requires an
`enrol_code`.** Any client that enrols without one gets a 422/403 until step 3. The
Android build from `536cc02` ("Enrol the phone with a hardware-backed device key") is one
such client; see *Known gaps*.

**Gate:** `curl -s localhost:9000/api/auth/whoami` returns JSON.

## 2. Give the hub the legacy password, and prove it works *before* Caddy depends on it

The hub cannot see Caddy's password. In `permissive` mode `/verify` checks the basic-auth
credential itself, against bcrypt hashes in `~/hub/auth_data/basic_users` (`user:hash`
per line, `#` comments allowed).

Take the hash from your Caddyfile's `basic_auth` block. Both the plain `$2a$14$…` form and
the older base64 form (`JDJh…`) are accepted as-is:

```bash
install -m 600 /dev/null ~/hub/auth_data/basic_users
echo 'wan:<hash from Caddyfile>' >> ~/hub/auth_data/basic_users
# if two passwords are in use (relay_access.txt), add every user line
.venv/bin/python -m auth status          # expect: mode: permissive, users: N
```

No restart is needed; the file is re-read when it changes.

**Gate (all three must pass, run on the VM, hitting the hub directly, bypassing Caddy):**
```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:9000/api/auth/verify                          # 401
curl -s -o /dev/null -w '%{http_code}\n' -u wan:WRONG localhost:9000/api/auth/verify             # 401
curl -s -o /dev/null -w '%{http_code}\n' -u wan:REAL_PASSWORD localhost:9000/api/auth/verify     # 200
```
**If the third is not 200, STOP.** Moving to step 4 with this broken locks every
basic-auth client out of `/api/*`. Most likely causes: wrong hash pasted, or running
`python -m auth` as root, so the file is in the wrong home.

## 3. Enrol both devices (Caddy still guards everything)

Still behind Caddy basic auth, and now behind a code too:

```bash
cd ~/hub && .venv/bin/python -m auth mint-code
# XXXXX-XXXXX      valid 10 minutes, single use
```

Enter that code in the device's enrol flow (the client sends it as `enrol_code`). Mint a
fresh code per device. Codes are case/dash-insensitive. Five wrong codes from anywhere
voids every live code; just mint another.

```bash
.venv/bin/python -m auth list            # both devices present
```

Prove each device can actually sign in **through the public URL** (Caddy still doing
basic auth, hub reached exactly as today): use the device's own enrol/sign-in flow, then

```bash
.venv/bin/python -m auth list            # last_seen is set for BOTH devices
ls -l ~/hub/auth_data/tokens.json        # exists, mode 600
```

**Gate:** each device has a `last_seen`, meaning it completed a challenge → token
exchange. **Do not continue with only one device enrolled.** If you lose that device your
only way back in is the VM shell.

Tokens persist across `systemctl restart forge-hub`. Confirm it once, since every recovery
path below relies on it: restart the service, then check in the app that it is still
signed in (no new fingerprint prompt) before moving on.

## 4. Stage A — Caddy: `forward_auth` on `/api/*`, still permissive

Behaviour for the phone is unchanged: it still sends its basic-auth header, and the hub
now accepts it. What changes is *who checks it*.

Edit `/etc/caddy/Caddyfile`. Diff against the reconstructed original (adapt to yours):

```diff
 84-12-112-249.sslip.io {
-	basic_auth {
-		wan JDJhJDE0JC4uLg
-	}
+	# Browser/ttyd/static paths keep Caddy basic auth exactly as before.
+	@web not path /api/*
+	basic_auth @web {
+		wan JDJhJDE0JC4uLg
+	}
+
+	# Bootstrap: a device has no token yet when it calls these. They are guarded inside the
+	# hub by single-use enrolment codes, signature checks and per-IP/per-device throttling.
+	# Keep this list exactly this short and POST-only. GET/DELETE /api/auth/devices
+	# (list/revoke) must NOT be here.
+	@authpub {
+		method POST
+		path /api/auth/challenge /api/auth/token /api/auth/devices
+	}
+	handle @authpub {
+		reverse_proxy 127.0.0.1:9000
+	}
+
+	handle /api/* {
+		forward_auth 127.0.0.1:9000 {
+			uri /api/auth/verify
+			copy_headers X-Device-Id X-Auth-Method
+		}
+		reverse_proxy 127.0.0.1:9000
+	}

 	handle /laptop/term/* {
 		reverse_proxy <ttyd upstream, unchanged>
 	}
 	reverse_proxy 127.0.0.1:9000        # unchanged catch-all
 }
```

Why each part:

- `basic_auth @web`: a site-level `basic_auth` runs *before* any `handle` and would keep
  blocking `/api/*` regardless of `forward_auth`. It has to be scoped away from `/api/*`.
- `@authpub` first: `handle` blocks are matched in order of appearance, and the bootstrap
  routes must not hit `forward_auth` (no token exists to present).
- `forward_auth` copies the client's `Authorization` header to `/verify` and relays any
  non-2xx response (401 with `WWW-Authenticate`, or 429) straight back to the client, so
  browser sign-in prompts and "Too many attempts" messages still work.

Apply it **safely**:

```bash
sudo caddy fmt --overwrite /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile      # must print "Valid configuration"
sudo systemctl reload caddy                            # graceful; a bad config is rejected and the old one stays live
```

Use `reload`, **not** `restart`. If validation fails, nothing has changed. Do not
proceed until `validate` is clean.

**Gate: test from outside, all of these, on mobile data too:**
```bash
H=https://84-12-112-249.sslip.io
curl -s -o /dev/null -w '%{http_code}\n' $H/api/status                                  # 401  (no creds)
curl -s -o /dev/null -w '%{http_code}\n' -u wan:WRONG $H/api/status                     # 401
curl -s -o /dev/null -w '%{http_code}\n' -u wan:REAL_PASSWORD $H/api/status             # 200  <- the phone's path
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <token>" $H/api/status   # 200
curl -s -o /dev/null -w '%{http_code}\n' $H/app/                                        # 401  (web still on Caddy basic)
curl -s -o /dev/null -w '%{http_code}\n' -u wan:REAL_PASSWORD $H/app/                   # 200
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE $H/api/auth/devices/anything         # 401  (must NOT be open)
```
Then **use the phone app** for real: open it, load something that hits `/api/*`.

**If any line is wrong: restore immediately** (Recovery §A). Do not debug live.

Soak here for as long as you like. This is a stable state: everything works and password
guessing is now throttled. Watch which clients still use the password:

```bash
journalctl -u forge-hub -f | grep 'legacy basic'      # one line/min/client while in use
```

## 5. Stage B — flip to `device-only`

**Preconditions, all of them:**

1. Both devices enrolled and each verified through the public URL with a token (§3).
2. **Every client that calls `/api/*` sends `Authorization: Bearer <token>`.** This
   includes the hub's own web UIs (`/app/venice`, `/app/term`, …) loaded in the phone's
   WebView or the laptop's browser: they currently authenticate with the browser's cached
   basic-auth credential and **will get 401 in device-only mode** unless they were changed
   to send the token. Anything that cannot set a header (WebSocket, `EventSource`, `<img>`
   pointing at `/api/...`) breaks too. Watch the `legacy basic` log while using **every**
   screen you care about; it must stay silent.
3. Recovery §B is written down and you have both SSH sessions open.
4. The laptop/desktop is at hand to test from a *second* device.

Flip:

```bash
sudo systemctl edit forge-hub        # add:
#   [Service]
#   Environment=HUB_AUTH_MODE=device-only
sudo systemctl restart forge-hub
.venv/bin/python -m auth status      # expect: mode: device-only
```

**Gate (immediately):**
```bash
curl -s -o /dev/null -w '%{http_code}\n' -u wan:REAL_PASSWORD $H/api/status              # 401  (password refused now)
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer <token>" $H/api/status # 200
```
Then open the phone app. **If the phone cannot get in, revert now** (Recovery §B, one
command and a restart, and tokens survive, so nobody is signed out by the revert).

Tokens last 12 h. The client must re-run challenge → token on 401; test that by waiting
out one expiry (or restarting after `TOKEN_TTL_S` in a test) *before* declaring done.

Afterwards: the shared password still guards `/app/*`, ttyd and `comfy.`. Rotate it if it
was ever exposed; that is a separate Caddy-only change.

## Recovery

Every scenario below is recoverable from an SSH session on the VM. That is the whole
reason SSH is left out of scope and kept open.

### A. Caddy misbehaving after Stage A (everything 401/502, or phone cannot connect)

```bash
sudo cp /etc/caddy/Caddyfile.pre-device-auth /etc/caddy/Caddyfile
sudo systemctl reload caddy
```
This puts back the shared-password setup exactly as it was. It does not depend on the
hub, on `basic_users`, or on any device.

### B. Device-only and a device cannot get in

```bash
sudo systemctl edit forge-hub     # change the line to:  Environment=HUB_AUTH_MODE=permissive
                                  # (or delete the drop-in: sudo rm /etc/systemd/system/forge-hub.service.d/override.conf)
sudo systemctl daemon-reload && sudo systemctl restart forge-hub
```
The password works again immediately; device tokens keep working too (they are persisted).
No Caddy change is involved.

### C. Locked out by throttling ("Too many failed attempts; retry in N seconds")

Lockouts are per client IP (and per device+IP for signatures), 15 min, in memory. Either
wait, or: `sudo systemctl restart forge-hub`. Other clients are unaffected; if the operator
is on mobile data behind carrier NAT, another subscriber's guessing can lock a shared IP.
Restart clears it. Enrolment codes already minted survive a restart; sign-in tokens too.

### D. Hub down / restarting

Forward auth depends on the hub answering, and so did every `/api/*` request already.
`sudo systemctl status forge-hub`, `journalctl -u forge-hub -n 50`. If the hub cannot
start *because* of this change, roll back the code: `mv ~/hub ~/hub.failed && cp -a ~/hub.pre-device-auth ~/hub`
(keeps the failed tree for inspection), restart the service, then §A.

### E. Every device lost / wiped / revoked by mistake

The VM shell is the root of trust. On the VM, as `ubuntu`:
```bash
cd ~/hub && .venv/bin/python -m auth list
.venv/bin/python -m auth revoke <id>      # works with the hub stopped
.venv/bin/python -m auth mint-code        # then re-enrol from the device
```
To regain access to `/api/*` without any device, do §B (permissive) first.

### F. The password in `basic_users` is wrong or the hash format is rejected

Symptom: in permissive mode every basic-auth request is 401. Fastest fix is §A (Caddy
back to doing basic auth itself). Slower fix: correct `basic_users` (no restart needed).

### G. Cannot SSH at all

OCI Console → Instance → Console connection (serial) or Cloud Shell. If you only have
that, do §A and §B from there. This migration never changes sshd, security lists or
firewall rules, so if SSH is gone the cause is not this change.

## Full rollback

```bash
sudo cp /etc/caddy/Caddyfile.pre-device-auth /etc/caddy/Caddyfile && sudo systemctl reload caddy
sudo systemctl revert forge-hub     # drops the HUB_AUTH_MODE override
sudo systemctl restart forge-hub
```
Enrolled devices and `auth_data/` can stay; nothing reads them once Caddy does basic auth.

## Known gaps

- **Android client vs enrolment codes.** `POST /api/auth/devices` now needs `enrol_code`.
  The phone-side enrol flow (commit `536cc02`) must send it, and the UI must let the user
  type it. This change does not touch the Android app.
- The `/app/*` web UIs, ttyd and `comfy.` still rely on the shared password. The device
  keys protect `/api/*` only.
- The three POST bootstrap routes (`challenge`, `token`, enrol) are reachable without the
  password by design once Stage A is live, protected by enrolment codes, signatures and
  throttling. Everything else under `/api/*`, including `whoami`, list and revoke, sits
  behind `forward_auth`.
- Throttle state and lockouts are in memory: a restart clears them. Enrolment codes and tokens are on disk.
- One process is assumed (single uvicorn worker, as in `forge-hub.service`). Running several workers would give each its own throttles.
- Token TTL is 12 h; revocation is immediate (a revoked or removed device's token stops
  working on the next request, even if the hub was down when it was removed).
