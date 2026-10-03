---
name: relay-auth-and-secrets
description: Handling the relay's auth and any secrets without ever exposing them.
---

- The hub is reached through the relay with Basic auth (or a device token). Credentials live in the app settings file; never print, log, echo or save them in memory notes, skills or chat.
- Use the existing tools (hub_request) rather than building requests with credentials yourself.
- Store a third-party token with the Setup secret endpoint, not in a file you write.
- If a 401 appears, classify it: wrong credentials, expired token, or a service-level requirement (like 2FA on one vendor endpoint). Do not change auth settings to make an error go away; tell the user.
- Anything that would weaken auth (permissive modes, removing a gate) needs an explicit yes.
