---
includes:
  - find_relevant_entities
  - create_conceptmap
  - extract_patterns
  - extract_book_recommendations
---

## Per-type guidance: `article`

This source is a longform blog post, essay, or magazine piece. An article is an argument with a voice. Extract the argument structure, the author's characteristic framings, and the memorable moments.

The source summary page MUST contain these sections in this order:

1. **One-line tagline** — the article's central argument, in the author's spirit.
2. **`## Ideas`** — 5–10 bullets of the most surprising / insightful ideas the article puts forward. One idea per bullet, phrased as a proposition.
3. **`## Quotes`** — 3–6 notable passages verbatim (under 200 chars each), each tagged with the author's name at the end — `"…" — Author`.
4. **`## Facts`** — concrete claims about the world the article asserts. Bullet form.
5. **`## Historical context`** — 2–4 sentences (or bullets) placing the article's subject in its time/tradition: what came before, what the author is responding to, which figures or schools form the backdrop. Omit only if the source genuinely gives none.
6. **`## Open questions`** — 2–5 bullets capturing what the article leaves unresolved: tensions it points to without settling, questions it raises in passing, threads the author flags as needing further work. These are gold for cross-linking later sources.
7. **`## Related`** — cross-links `[[wiki/…]]` to entity/concept pages.
8. Original `raw/...` path at the bottom.

Entity pages for the author (if named), people/orgs/tools/schools/movements they discuss. Concept pages for the framings or ideas they're introducing. Preserve the author's voice in the Ideas bullets — don't neutralize them into textbook prose.
