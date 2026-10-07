---
name: desktop-control
description: Operating apps on the user's Windows desktop through the kimi-cu connector (look, click, type, keys). Use when the task needs an app on this PC rather than a file or the relay.
---

Tools are kimi-cu__*. Always LOOK before you act, and look again after each action.
1. kimi-cu__list_apps to find the app; kimi-cu__activate_window to bring it forward; kimi-cu__launch_app only if it is not running.
2. kimi-cu__get_app_state with {"app": "...", "mode": "text"} (or "ax" for the element tree with indexes). Use mode
   "image" only if the chosen model can see images; otherwise you get a note instead of a picture.
3. Act on an element INDEX from the latest snapshot (pass its snapshot_id) rather than pixel coordinates when you can:
   kimi-cu__click, kimi-cu__type_text, kimi-cu__set_value, kimi-cu__press_key ("Enter", "Control_L+a"), kimi-cu__scroll.
4. After every action, get_app_state again and check the result before the next step. One action at a time.
5. Finish with kimi-cu__turn_ended.
Clicks, typing, keys, launching apps and drags ask the user first. If they deny, do not retry: say what you wanted to do.
Never type passwords, payment details or 2FA codes; never close or delete things the user did not name; stop and ask
when a dialog asks to confirm something irreversible.
