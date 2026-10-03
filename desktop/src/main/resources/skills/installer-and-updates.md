---
name: installer-and-updates
description: Building and installing the Forge Hub laptop app MSI, and removing old WhiteDevil installs.
---

- Build with ./gradlew.bat packageMsi (desktop). The output is build\compose\binaries\main\msi\WhiteDevil-1.0.0.msi.
- Install with C:\Users\anon3\install-forgehub.ps1 in plain PowerShell (no ! prefix). It needs elevation and runs /x then /i because the MSI keeps one ProductCode, so a plain reinstall is ignored.
- It installs to C:\Program Files\WhiteDevil Desktop. The old Electron app lived in a different folder; do not delete user data folders (%LOCALAPPDATA%\WhiteDevil holds settings, history, memory cache and skills).
- Verify after install: the jar size changed, the app starts, and crash.log did not grow.
