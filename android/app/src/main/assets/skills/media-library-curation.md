---
name: media-library-curation
description: Searching, grouping and naming renders and clips in the media library.
---

- GET /api/media/library lists clips; the hub groups all non-pack/chain/keeper clips under "Tests & experiments" (kind test). Many of those are real LTX renders, so the grouping label does not mean throwaway.
- Do NOT rename files on the hub: names like smoke_* and runner tags are parsed by hub code (media.py, colab.py). The app prettifies them for display only.
- To find a clip: search by prompt words, then confirm by looking at the thumbnail/contact sheet, not by name alone.
- Saving a clip locally: use the Renders/Gallery Save action; verify the size afterwards.
- Never delete clips from the hub without last-copy-check and an explicit yes.
