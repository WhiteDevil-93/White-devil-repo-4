---
name: gradle-kotlin-build
description: Building and testing the Kotlin Compose desktop app (wd-ui) on Windows.
---

- Work tree: A:\New folder (4)\wd-ui (branch feat/desktop-forge-hub-ui). Run gradlew.bat from the desktop folder; running from the wrong directory gives exit 127.
- Tests: ./gradlew.bat test. A passing UP-TO-DATE hides re-runs of env-gated tests; use cleanTest when you need them to run.
- Count skipped tests; a jump in skipped means something stopped running.
- Compose pitfalls: never put a LazyColumn inside a DropdownMenu (intrinsic measurement crashes); UI tests need mainClock.autoAdvance=false.
- Close dev windows before rebuilding underneath them (NoClassDefFoundError otherwise).
- Package: ./gradlew.bat packageMsi, then the installer script C:\Users\anon3\install-forgehub.ps1 (uninstalls then installs to C:\Program Files\WhiteDevil Desktop, because the MSI reuses the same ProductCode).
