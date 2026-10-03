---
name: setup-bot-recipes
description: Using the Setup bot to install recipes (ComfyUI, Wan, LTX, LoRAs) onto Thunder, Vast or Colab. Use for any 'install X on the box' request.
---

- Endpoints: /api/setup/catalog (what can be installed), /api/setup/preview (what a run would do), /api/setup/chat, /api/setup/runs, /api/setup/secret (store a token; never print it).
- Always preview first and show the user the steps and target before "Confirm and run". Installs download tens of GB and cost box time.
- Model files must live OUTSIDE the ComfyUI checkout (/workspace/models) and be symlinked in, so a rebuild never deletes weights. Never rm -rf a checkout to fix an install.
- Shell scripts that came from Windows may carry CRLF; a failure like $'\r': command not found means strip the carriage returns (sed -i 's/\r$//') and keep a backup.
- A validation loop that prints ALL_VALID over an empty glob is a false pass; always print the file count too.
- Verify the end state yourself (files present, sizes plausible, a test generation) before saying done.
