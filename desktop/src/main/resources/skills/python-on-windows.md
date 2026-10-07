---
name: python-on-windows
description: Which Python to use on this laptop and how to run project tests.
---

- Default `python` on PATH is C:\Python314\python.exe (3.14). `py` default is a different binary under AppData\Local\Python\pythoncore-3.14-64. PYTHONPATH is unset.
- If a project has .venv\Scripts\python.exe, use that. Skip the WindowsApps stub (python3 there is a Store shortcut, not Python).
- Other installs: miniconda (C:\Users\anon3\miniconda3), and 3.12/3.11/3.10 under AppData\Local\Programs\Python.
- Windows heredocs and quoting mangle backslashes; write scripts to a file and run the file instead of passing code inline.
