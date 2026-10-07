---
name: web-scraping
description: Browsing and scraping websites in the user's own Chrome through the playwright connector (logged-in sessions). Use for reading pages, collecting lists or tables, and filling simple forms the user asked for.
---

Tools are playwright__*. It drives the user's real Chrome through the Playwright extension: the first call opens a page
where the user picks which tab to share. If the extension is not installed, say so and stop.
Reading (no approval needed): playwright__browser_navigate {url}, playwright__browser_snapshot (the page as an
accessibility tree with refs: this is the main way to read a page, and it works with any model),
playwright__browser_take_screenshot (only useful with a vision model), playwright__browser_find, playwright__browser_tabs,
playwright__browser_wait_for, playwright__browser_navigate_back.
Acting (asks the user first): playwright__browser_click {ref}, playwright__browser_type, playwright__browser_fill_form,
playwright__browser_press_key, playwright__browser_select_option, playwright__browser_evaluate (run JavaScript on the page),
playwright__browser_handle_dialog.
Scraping recipe: navigate -> snapshot -> extract what was asked into a table or JSON -> follow "next" links one page at
a time (each click asks once; the user can allow it for the chat) -> stop at the limit the user gave, or 10 pages.
For long lists, browser_evaluate with a small read-only script (document.querySelectorAll(...).map(textContent)) is faster
than many snapshots; never use it to change the page or submit anything.
Save results with write_file in the workspace (CSV or JSON) and say where. Respect logins: never type passwords or payment
details, never buy, post, send or delete anything unless the user explicitly asked for that exact action.
