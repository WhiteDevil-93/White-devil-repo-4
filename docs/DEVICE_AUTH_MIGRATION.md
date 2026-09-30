# Migrating the relay from one shared password to per-device keys

**Read the whole page before you touch Caddy.** The dangerous part of this
migration is not the code, it is the order. Do step 5 before step 3 and the
phone stops working while you are away from the laptop, with no way to fix it
from the phone — because the thing you broke is the API the phone would have
used to fix it.

There is a recovery path for every step, and it is at the bottom. It needs SSH
to the VM. **If you cannot SSH to `wan-relay` right now, do not start.**

Nothing in this page has been run against the real VM or the real Caddy: the
Caddyfile is not in this repo and the checks below are written from the code and
from Caddy's documented behaviour. Every step ends in a check for that reason.

---

## 0. What is actually changing

Today: Caddy checks one HTTP basic-auth password (`relay_access.txt`) on the
public site. Every device holds the same password. FastAPI itself enforces
nothing — anything that gets past Caddy can call any `/api/*` route.

After: FastAPI decides, per `/api/*` request, via `GET /api/auth/forward`, which
Caddy calls with `forward_auth`. Each device holds its own keypair whose private
half never leaves its secure hardware (Android StrongBox, Windows Hello / TPM).
Revoking the laptop does not disturb the phone.

What is **not** changing in this migration:

- `require_device()` is still applied to **no route** (a test asserts it).
  Route-level enforcement is a separate decision, taken later, deliberately.
- The device-auth protocol itself is unchanged. Enrol a public key →
  `POST /challenge` → sign the nonce → `POST /token` → bearer token.
- Everything that is not `/api/*` — the `/app/*` web UI, `/laptop/term/*` (ttyd),
  `/laptop/files/*` — stays behind Caddy's password. Nothing in this repo
  establishes that ttyd or the file bridge have credentials of their own, so this
  runbook does not assume it. See §1 for why that matters.

Three settings control the migration, all readable at `GET /api/auth/config`:

| Setting | Values | Default | What it does |
|---|---|---|---|
| `forward_auth_mode` | `permissive`, `basic_or_token`, `strict` | `permissive` | What `/api/auth/forward` accepts |
| `require_enrol_code` | `true` / `false` | `false` | Whether `POST /api/auth/devices` needs a single-use code. **Outside `permissive` a code is required whatever this says** (`GET /api/auth/config` then reports `true`, and `require_enrol_code_configured` keeps the stored value) |
| basic credential | `HUB_BASIC_AUTH_USER` + `HUB_BASIC_AUTH_PASSWORD` | unset | The hub's own copy of the relay password, used only in `basic_or_token` |

`forward_auth_mode` in detail:

- **`permissive`** — a valid device token, **or any `Authorization: Basic`
  header at all**. This is only safe while Caddy is *still* running
  `basic_auth` in front of `/api/*`, because Caddy has already rejected a wrong
  password; anything arriving with a Basic header was vouched for by Caddy.
  **Never run `permissive` with Caddy's `basic_auth` gone from `/api/*`** —
  `curl -H 'Authorization: Basic x'` would be enough to get in.
- **`basic_or_token`** — a valid device token, or a Basic header the **hub**
  verifies itself against `HUB_BASIC_AUTH_USER` / `HUB_BASIC_AUTH_PASSWORD`.
  This is the mode to sit in while Caddy's `basic_auth` is gone from `/api/*`
  but some client (a browser, the Compose Desktop app) still has no device key.
  If the credential is not configured, this mode returns **503**, not 200 — it
  refuses rather than silently degrading to "any Basic header". Wrong passwords
  are locked out per client address (§5), because Caddy's slow bcrypt check no
  longer stands in front of them.
- **`strict`** — device token only. Basic is refused with no
  `WWW-Authenticate` header, so a browser will not sit in a re-prompt loop.

The hub refuses to set `basic_or_token` or `strict` while **zero** devices are
enrolled (409). That guard exists because it is the exact footgun this document
is about.

### Safety properties worth knowing before you start

1. **The device-auth bootstrap routes cannot live behind `forward_auth`.** A
   device has to reach `POST /challenge`, `POST /token` and `POST /devices`
   before it has a token to present. So those three (and only those three, and
   only as `POST`) are routed around `forward_auth` in §1, and defended instead
   by the rate limits and lockouts in §5 and by the enrolment code in step 3. The code
   is enforced in every mode but `permissive` whether or not `require_enrol_code` is set: without
   Caddy's password in front, an open enrolment route would let anyone add a device key.
2. **The routes that can change who gets in guard themselves.** `DELETE
   /api/auth/devices/{id}`, `POST /api/auth/enrol-code` and `POST
   /api/auth/config` run `require_admin`. Under `permissive` that is a no-op —
   Caddy's `basic_auth` is still in front. Under `basic_or_token` and `strict`
   they demand the same credential `forward_auth` would. A device that has just
   been revoked cannot use its old token to administer.
3. **The relay password can never mint an enrolment code once a device exists.**
   `POST /api/auth/enrol-code` needs a *device token*. Otherwise the code would
   not be a second factor at all: a leaked password could ask for its own code
   and then enrol. The operator's way to mint one is the CLI on the VM (step 3);
   shell access to the VM is the root of trust for the first device.
4. **A token is only as good as its device.** Every token lookup re-checks that
   the device is still enrolled, so editing `devices.json`, running
   `python -m auth revoke`, or restoring a stale `tokens.json` cannot resurrect
   a removed device.

---

## 1. The Caddyfile

> The Caddyfile lives on the VM (`/etc/caddy/Caddyfile`), not in this repo, so
> the "before" below is the configuration as described by
> `laptop-app/README.md` and `hub/app.py`. **Diff it against your real file
> before applying** — if your site block differs, keep your version and take
> only the `forward_auth` additions and the `basic_auth` scoping.
>
> `forward_auth` needs **Caddy 2.5 or newer** (`caddy version`). Before 2.8 the
> basic-auth directive is spelled `basicauth`; if `caddy validate` rejects
> `basic_auth`, use the spelling already in your file.

Two facts about Caddy decide the whole shape of this change:

- A **site-level `basic_auth` runs before any `handle` block** (it comes earlier in
  Caddy's directive order). So while it is unscoped it also answers for `/api/*`
  and rejects a device's `Authorization: Bearer …` before `forward_auth` ever
  sees it — device tokens cannot be tried through Caddy until it is scoped away
  from `/api/*`. Conversely, *deleting* it outright (as an earlier draft of this
  page did) also unprotects `/laptop/term/*`, `/laptop/files/*` and `/app/*`.
  The end state below **scopes** it instead.
- `forward_auth` copies the client's `Authorization` header to the hub and relays
  any non-2xx answer (a `401` with `WWW-Authenticate`, a `429` with
  `Retry-After`) straight back to the client, so browser prompts and "try again
  in N seconds" still work.

### Before

```caddyfile
84-12-112-249.sslip.io {
	encode zstd gzip

	basic_auth {
		relay $2a$14$REPLACE_WITH_YOUR_EXISTING_HASH
	}

	handle /laptop/term/* {
		reverse_proxy 127.0.0.1:7681
	}

	reverse_proxy 127.0.0.1:9000
}
```

### Step 4 state — `forward_auth` added, `basic_auth` untouched

```caddyfile
84-12-112-249.sslip.io {
	encode zstd gzip

	basic_auth {
		relay $2a$14$REPLACE_WITH_YOUR_EXISTING_HASH
	}

	handle /laptop/term/* {
		reverse_proxy 127.0.0.1:7681
	}

	# Bootstrap: a device has no token yet when it calls these. POST only, and
	# exactly these three — GET/DELETE /api/auth/devices, /config, /enrol-code and
	# /whoami must NOT be here, they go through forward_auth below.
	@authpub {
		method POST
		path /api/auth/challenge /api/auth/token /api/auth/devices
	}
	handle @authpub {
		reverse_proxy 127.0.0.1:9000
	}

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

`@authpub` is written first, and its paths are longer than `/api/*`, so it wins
under either of Caddy's ordering rules (order of appearance; longest path
first).

### Step 5 state — `basic_auth` scoped away from `/api/*`

Only two lines change from the block above:

```diff
-	basic_auth {
-		relay $2a$14$REPLACE_WITH_YOUR_EXISTING_HASH
-	}
+	@web not path /api/*
+	basic_auth @web {
+		relay $2a$14$REPLACE_WITH_YOUR_EXISTING_HASH
+	}
```

Now `/api/*` is decided by the hub alone, and `/app/*`, `/laptop/*` and the
catch-all keep the shared password exactly as before. If you *want* to drop the
password from the UI as well, that is a separate decision: put a deliberate
`handle` for it in and understand that `/laptop/term/*` is a shell.

---

## 2. Order of operations

Each step has a check. **Do not proceed past a check that did not pass.**
Every `curl` below runs from the laptop against the public site unless it says
"on the VM".

Set these once:

```bash
RELAY=https://84-12-112-249.sslip.io
AUTH='-u relay:THE_RELAY_PASSWORD'
```

### Step 0 — pre-flight

1. Open **two** SSH sessions to the VM from a machine whose SSH access does not
   depend on the relay. Leave both open until the migration is finished. A
   Caddy reload never drops an established SSH session.
2. Confirm your last-resort access *before* you need it (OCI Console → the
   instance → Console connection / Cloud Shell). Just check you can log in.
3. On the VM: `caddy version` (must be ≥ 2.5) and take the backups every
   recovery below leans on:
   ```bash
   sudo cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak
   cp -a ~/hub ~/hub.pre-device-auth
   ```
4. Do this with the phone physically at hand, on mobile data as well as Wi-Fi.

### Step 1 — deploy the code, change nothing

```bash
ssh wan-relay
cd ~/hub && git pull && .venv/bin/pip install -r requirements.txt
sudo systemctl restart forge-hub
systemctl is-active forge-hub
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
touch Caddy at all. Enrolment codes are still off, so any Android build enrols as
before. Android 9.12 and later also has an "Enrolment code" field in Settings, for
once the hub asks for one; earlier builds send none and cannot enrol a new phone then.

- **Phone:** Android app → device enrolment. It uses StrongBox (ES256).
- **Laptop:** Compose Desktop app → device enrolment. It uses Windows Hello
  (RS256, PKCS#1 v1.5 over SHA-256).

**Check:** two devices listed, and each one round-trips a challenge into a
token *from its own hardware* (a fingerprint/PIN prompt should appear on each):

```bash
# on the VM, as the hub's user (NOT with sudo — see below)
cd ~/hub && .venv/bin/python -m auth list
# expect two entries with a "last_seen" that is not None after each has signed in once
```

A device with `last_seen= None` has enrolled but has **never proved it can
sign**. That is not enrolled for the purposes of this migration. Go make it
sign before continuing.

> Run `python -m auth …` as the user that runs the service (`ubuntu`), never
> with `sudo`. State lives under `Path.home()`; as root the CLI reads and writes
> `/root/hub/auth_data`, sees no devices, and looks like it "lost" all of them.

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
     -d '{"name":"should-not-work","public_key_pem":"x"}'
# expect: 403   (the code is checked before the key is even parsed, so a
#                placeholder key still gets 403, not 400)

curl -s -o /dev/null -w '%{http_code}\n' $AUTH -X POST $RELAY/api/auth/enrol-code
# expect: 401   (the relay password alone cannot mint one)
```

To enrol a legitimate third device later, **on the VM, as the hub's user**:

```bash
cd ~/hub && .venv/bin/python -m auth mint-code
# XXXXX-XXXXX      valid 15 minutes, single use; case and dashes don't matter
```

and enter it in the device's enrolment flow (it is sent as `enrol_code`). A
device that already holds a token can mint one itself with `POST
/api/auth/enrol-code` and its `Authorization: Bearer`, but until step 5 Caddy's
`basic_auth` occupies that header, so use the CLI until then.

Five wrong codes from **anywhere** within ten minutes voids every live code and
pauses enrolment for five minutes — that is what stops guessing spread over many
addresses. Mint another afterwards. (The flip side: anyone who can reach
`POST /api/auth/devices` can pause *new* enrolments this way. Existing devices
are unaffected.)

### Step 4 — add `forward_auth` to Caddy, keeping `basic_auth`

Apply the **Step 4 state** from §1. Both are live. Mode is still `permissive`,
so `/api/auth/forward` returns 200 for a device token *and* for anything Caddy
already let through.

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy        # graceful; a bad config is rejected and the old one stays live
```

**Check:** nothing broke, and forward-auth is genuinely in the path.

```bash
curl -s -o /dev/null -w '%{http_code}\n' $AUTH $RELAY/api/colab/state   # 200
curl -s -o /dev/null -w '%{http_code}\n'       $RELAY/api/colab/state   # 401 (from Caddy)
journalctl -u forge-hub -n 20 --no-pager | grep -c '/api/auth/forward'  # > 0
```

Expect a device's `Bearer` token to be refused at the edge here (`401` from
Caddy) — the still-unscoped `basic_auth` answers first. That is not a bug, and it
means **the device-token path through Caddy is first exercised in step 5.**

**Now go use the phone for a few minutes.** If anything is going to break, it
breaks here, and here you can still roll back by restoring the `.bak`.

### Step 5 — give the hub the password, then scope it out of `/api/*`

Order inside this step matters too. The hub gets its own copy of the password
**before** Caddy stops checking it for `/api/*`, or there is a window with no
fallback.

Put the password where only root can read it. Do **not** put it in a `systemctl
edit` drop-in: those are ordinary world-readable files (check with `ls -l`) and
`systemctl show forge-hub` prints their `Environment=` lines to any user.

```bash
ssh wan-relay
sudo install -m 600 /dev/null /etc/forge-hub.env
sudoedit /etc/forge-hub.env
```

```ini
HUB_BASIC_AUTH_USER=relay
HUB_BASIC_AUTH_PASSWORD=THE_RELAY_PASSWORD
```

```bash
sudo systemctl edit forge-hub      # add the two lines below, nothing else
```

```ini
[Service]
EnvironmentFile=/etc/forge-hub.env
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

**Check that the *hub* is now verifying the password — on the VM, hitting the hub
directly.** Do not run this against the public URL: Caddy's `basic_auth` still
answers first there, so a wrong password is a `401` from Caddy whether or not the
hub checks anything.

```bash
curl -s -o /dev/null -w '%{http_code}\n' -u relay:wrong                 localhost:9000/api/auth/forward  # 401
curl -s -o /dev/null -w '%{http_code}\n' -u relay:THE_RELAY_PASSWORD    localhost:9000/api/auth/forward  # 200
```

Only after both pass, apply the **Step 5 state** from §1 (scope `basic_auth` to
`@web`):

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

**Check:** wrong password refused, right one accepted, and the terminal and UI
are still behind the password.

```bash
curl -s -o /dev/null -w '%{http_code}\n' -u relay:wrong $RELAY/api/colab/state  # 401
curl -s -o /dev/null -w '%{http_code}\n' $AUTH          $RELAY/api/colab/state  # 200 (now the hub's verdict)
curl -s -o /dev/null -w '%{http_code}\n'                $RELAY/laptop/term/     # 401  (still Caddy's password)
curl -s -o /dev/null -w '%{http_code}\n'                $RELAY/app/             # 401
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE      $RELAY/api/auth/devices/anything  # 401 (must NOT be open)
curl -s -o /dev/null -w '%{http_code}\n' $RELAY/api/auth/devices                          # 401 (list is not open)
```

There is no `curl` for the device-token path — it needs the hardware key. **Use
the phone and the desktop app for real** and confirm both work, in this order:
phone, then desktop, then the phone again after a few minutes (its token should
be reused, not re-prompted).

### Step 6 — `strict`, and only when you mean it

`strict` removes the password fallback entirely. **Every** client of `/api/*`
must hold a device token, including any browser tab you use the UI from, since
the UI pages call `/api/*` with the browser's cached Basic credential. Do not do
this on a day you are away from the laptop.

```bash
curl -s $AUTH -X POST $RELAY/api/auth/config \
     -H 'content-type: application/json' -d '{"forward_auth_mode":"strict"}'
```

The hub refuses this with 409 if no devices are enrolled. If anything cannot
authenticate afterwards, the one-line unlock in §3 undoes it.

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
sudo systemctl edit forge-hub     # add: Environment=HUB_FORWARD_AUTH_MODE=basic_or_token
sudo systemctl restart forge-hub
```

Use `basic_or_token` if the hub has a credential configured (step 5): the relay
password works again and device tokens still do. `permissive` accepts **any**
Basic header, so once Caddy's `basic_auth` is scoped away from `/api/*` it is
**wide open** — an emergency lever, never a resting state:

```bash
Environment=HUB_FORWARD_AUTH_MODE=permissive
```

Undo it by removing the `Environment=` line and restarting; the config file
value takes over again.

### Symptom → fix

| Symptom | Cause | Fix |
|---|---|---|
| Everything 401s right after flipping to `strict` | no client has a device token | env var → `basic_or_token`, restart, then enrol properly |
| Everything 503s with "no basic-auth credential" | `basic_or_token` set, `HUB_BASIC_AUTH_*` unset (the `EnvironmentFile=` line or file lost) | restore `/etc/forge-hub.env` and the `EnvironmentFile=` line, restart |
| Only the phone 401s | its device was revoked, or its token expired and it cannot re-sign | `python -m auth list` on the VM; re-enrol from the phone |
| One device gets 429 "failed attempts" | its (device, address) pair tripped the 5-bad-signatures lockout (15 min) | wait, or `sudo systemctl restart forge-hub` (lockouts are in memory; tokens and codes are on disk and survive) |
| Someone else's wrong-password guessing locked *your* address out of `basic_or_token` | shared address (carrier NAT); wrong passwords lock the address for Basic only, tokens are unaffected | wait 15 min, use the device token, or restart the hub |
| Enrolment 403 "code required" and you have no code | `require_enrol_code=true`, or the mode is not `permissive` | `cd ~/hub && .venv/bin/python -m auth mint-code` (as `ubuntu`) |
| Enrolment 429 right after minting a code | five wrong codes anywhere voided every live code and paused enrolment for five minutes | wait five minutes (or restart the hub), mint again |
| You need to revoke a stolen device and the API demands credentials | non-`permissive` mode makes `DELETE /api/auth/devices/<id>` require a credential you do not have on the VM | `cd ~/hub && .venv/bin/python -m auth revoke <id>` — needs no credential, works with the hub down, takes effect on the very next request |
| Caddy returns 502 on all `/api/*` | `forge-hub` is down, so `forward_auth` cannot answer | `journalctl -u forge-hub -n 100`; usually a missing dependency after a `git pull` |

### Full stop — put it back exactly as it was

```bash
ssh wan-relay
# 1. Caddy: restore the old Caddyfile (unscoped basic_auth, no forward_auth)
sudo cp /etc/caddy/Caddyfile.bak /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy
# 2. Hub: back to the additive defaults
rm -f ~/hub/auth_data/auth_config.json
sudo systemctl restart forge-hub
```

Deleting `auth_config.json` returns both settings to their defaults
(`permissive`, `require_enrol_code=false`). Enrolled devices, live tokens and
unspent codes are in `auth_data/` and are **not** affected.

> Take that `.bak` in step 0, before you touch the Caddyfile.

---

## 4. State on disk

All under `~/hub/auth_data/` on the VM, all written tmp-file + `os.replace` under
a lock (the pattern from `hub/agentic/store.py:150`), so a crash mid-write cannot
leave a truncated file where a valid one used to be. The files are mode `0600`
(a file written before this change keeps its old mode until it is next rewritten).

| File | Holds | Losing it means |
|---|---|---|
| `devices.json` | device ids, names, **public** keys | every device must re-enrol |
| `tokens.json` | SHA-256 **hashes** of live tokens + expiry | every device signs in again (a fingerprint prompt), nothing worse |
| `enrol_codes/<hash>.json` | one file per unspent code, named by the SHA-256 of the code | outstanding codes stop working; mint new ones |
| `auth_config.json` | the two settings above | back to permissive defaults |

Enrolment codes are one file each so the CLI (a separate process) and the running
hub can share them without any locking protocol: minting creates the file, spending
unlinks it, and the OS lets exactly one caller win.

None of these contain a secret you could present as a credential: public keys
are public, and tokens and codes are stored hashed. A corrupt or truncated file
fails **closed** — it is treated as empty rather than crashing the hub. A token
whose device is no longer in `devices.json` is dead, whatever `tokens.json` says.

Challenges are deliberately **not** persisted. They live 120 seconds, are bound
to the address that asked for them (so nobody else can replace or redeem your
nonce), and a restart simply makes a client ask for a new one.

## 5. Rate limits and lockouts

State is in memory, per process, and cleared by a restart (which is also the
escape hatch — §3). It is bounded: rotating keys cannot grow it without limit.

**Call-rate limits** (sliding window). Two buckets on every request, because they
stop different attacks: the per-address bucket stops one host cycling through
every device id; the per-(device, address) bucket stops one stolen id being
hammered *from one place*. It is keyed on the pair, not the device alone —
otherwise anyone who knew an id could spend its allowance from their own address
and lock the real device out.

| Endpoint | Per (device, address) | Per address |
|---|---|---|
| `POST /api/auth/challenge` | 10 / 60s | 30 / 60s |
| `POST /api/auth/token` | 10 / 60s | 30 / 60s |
| `POST /api/auth/enrol-code` | — | 10 / 60s |

**Lockouts** (count *failures*; a call limit only slows a guesser down):

| What fails | Scope | Threshold | Locked for |
|---|---|---|---|
| bad signature / no challenge / bad base64 at `/token` | (device, address) | 5 in 10 min | 15 min |
| wrong enrolment code | address | 5 in 10 min | 15 min |
| wrong enrolment code | anywhere (also voids every live code) | 5 in 10 min | 5 min |
| wrong relay password, `basic_or_token` only | address, Basic only | 20 in 10 min | 15 min |

A success at `/token` clears that pair's failures. A missing enrolment code is not
a guess and is not counted. A device token is never affected by the password
lockout. Every 429 carries `Retry-After`.

The address is the **last** entry of `X-Forwarded-For` — the one Caddy itself
appended; earlier entries are client-supplied. Stock Caddy replaces the header, so
there is only one. This assumes nothing but Caddy can reach uvicorn on
`127.0.0.1:9000`; if you ever expose the port directly the header becomes
attacker-controlled, and if you later put Caddy behind a CDN with `trusted_proxies`
the last entry becomes the CDN and every client shares one bucket.

Tune the numbers in `hub/auth.py` (`RATE_LIMITS`, `LOCK_*`) and restart.

## 6. Known gaps

- **`permissive` waves through any Basic header.** It is only correct while Caddy's
  `basic_auth` still covers `/api/*` (steps 1–4). It is the default so the code is
  additive, not because it is a good place to rest.
- **The enrolment code is opt-in only while the mode is `permissive`.** Until step 3, in
  `permissive`, `POST /api/auth/devices` works without one and is not throttled, exactly
  as before. In `basic_or_token` and `strict` it always needs one.
- **First device over HTTP.** With zero devices enrolled there is no device token to
  ask for, so `POST /api/auth/enrol-code` falls back to whatever guards the hub. The
  CLI (`python -m auth mint-code`) is the strict alternative.
- **The three bootstrap routes are public once `basic_auth` is scoped away.** They
  are protected by codes, signatures and lockouts, not by the password. Anyone who
  reaches them can trip the global wrong-code pause (five minutes, new enrolments
  only).
- **`GET /api/auth/config` and `GET /api/auth/whoami` have no hub-level guard in any mode.**
  They are safe because §1 routes them through `forward_auth`; clients probe `/config` to learn
  whether to ask for a code, and it reports the mode and how many devices are enrolled.
  `GET /api/auth/devices` (names, ids, last-seen) now guards itself like revoke does, so
  widening `@authpub` no longer exposes the device list; it is still a no-op under `permissive`.
- **Password guessing in `basic_or_token` is cheaper than at Caddy.** Caddy checks
  bcrypt (about a second a guess); the hub compares a SHA-256. The per-address
  lockout caps one address, but guessing spread over many addresses is not capped.
  Use a long random relay password, and keep the window in `basic_or_token` short.
- **One process is assumed** (single uvicorn worker, as in `forge-hub.service`).
  Codes and tokens are on disk, but throttles are per process.
- Token TTL is 12 h; revocation is immediate.
