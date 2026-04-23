---
includes:
  - find_relevant_entities
  - extract_patterns
---

## Per-type guidance: `web`

This source is a short-form web page, news item, docs page, or landing page. It's thinner than an article — don't over-extract. A web source might justify only the source summary itself plus 1–2 entity pages, which is fine.

The source summary page MUST contain these sections in this order:

1. **One-line tagline** — what this page is about, factually.
2. **`## Summary`** — 2–3 sentences of what the page asserts.
3. **`## Key points`** — 3–5 bullets. Skip this section if the page is too thin to fill 3.
4. **`## Entities`** — list any named people, tools, companies, products this page names. Cross-link `[[wiki/entities/…]]` to ones you're producing as edits.
5. **`## Related`** — cross-links to other wiki pages.
6. Original `raw/...` path at the bottom.

Do not manufacture depth. If the page is thin, the source summary should be thin. Under-producing is preferred to padding.
