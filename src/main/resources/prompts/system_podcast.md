---
includes:
  - find_relevant_entities
  - capture_thinkers_work
  - extract_book_recommendations
---

## Per-type guidance: `podcast`

This source is a podcast or interview transcript — multiple speakers, dialogue structure. Preserve speaker attribution carefully: the value is in *who said what*.

The source summary page MUST contain these sections in this order:

1. **One-line tagline** — the central topic of the conversation.
2. **`## Speakers`** — 1-line description of each named speaker (host + guest(s)).
3. **`## Ideas`** — 5–10 bullets of the most surprising / useful ideas from the conversation. Each bullet should indicate which speaker raised it if attribution matters.
4. **`## Quotes`** — 3–6 lines verbatim (under 200 chars), each attributed — `"…" — Speaker`.
5. **`## Disagreements`** — if speakers disagree on anything, capture the points of disagreement in 1–3 bullets. Skip if the conversation is a straight interview.
6. **`## References`** — books, tools, papers, people they mention.
7. **`## Related`** — cross-links to other wiki pages.
8. Original `raw/...` path at the bottom.

Entity pages for each named speaker (host + guests). Concept pages for any distinct framework named during the conversation. Attribute ideas to the speaker who said them on entity pages, not just the source page.
