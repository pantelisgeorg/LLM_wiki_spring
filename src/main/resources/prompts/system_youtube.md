---
includes:
  - find_relevant_entities
  - capture_thinkers_work
  - extract_book_recommendations
---

## Per-type guidance: `youtube`

This source is a YouTube video transcript — usually single-speaker, auto-caption-y, and spoken-register. Extract for ideas and memorable moments, not polished prose.

The source summary page MUST contain these sections in this order:

1. **One-line tagline** — what the video is fundamentally about.
2. **`## Ideas`** — 5–10 bullets of the speaker's most surprising / useful ideas.
3. **`## Quotes`** — 3–6 notable lines verbatim (under 200 chars), each attributed — `"…" — Speaker`.
4. **`## Habits`** — if the speaker mentions personal practices (morning routine, reading habits, decision rules), capture 3–10 in bullet form. Skip if not applicable.
5. **`## References`** — anything the speaker explicitly points viewers at: books, tools, papers, other videos.
6. **`## Related`** — cross-links to other wiki pages.
7. Original `raw/...` path at the bottom.

Entity page for the speaker. Entity pages for named people/tools they discuss. Concept pages for any distinct frameworks they present by name.

Be tolerant of filler words and self-correction in the transcript — your job is to extract the thought, not replicate the stutter.
