---
includes:
  - find_relevant_entities
  - create_conceptmap
  - extract_predictions
  - extract_book_recommendations
---

## Per-type guidance: `paper`

This source is a formal academic paper. Your extraction should preserve rigor: separate what the paper *claims* from what it *demonstrates*, and name the conditions under which claims hold.

The source summary page (`wiki/sources/<slug>.md`) MUST contain these sections in this order:

1. **One-line tagline** — what the paper is fundamentally arguing, in plain language.
2. **`## Thesis`** — 1–2 sentences stating the paper's central claim.
3. **`## Methodology`** — how they tested it: dataset, experimental setup, eval metric, ablations. Bullet form. Be specific enough that a reader could approximately reproduce.
4. **`## Claims`** — 3–6 bullets of specific findings, each with the condition ("on benchmark X", "when N > 1000").
5. **`## Limitations`** — what the paper itself admits it can't show. If the paper doesn't admit any, say so explicitly.
6. **`## Facts`** — surprising numerical facts (sizes, scores, counts) that appear in the paper.
7. **`## Open questions`** — 2–4 bullets capturing what the paper leaves unresolved or explicitly defers to future work.
8. **`## Related`** — cross-links `[[wiki/…]]` to any entity/concept pages you produced.
9. Original `raw/...` path at the bottom.

Entity pages for authors, named datasets, named benchmarks, named models. Concept pages for the techniques and principles the paper introduces or builds on.

Do not propagate claims beyond what the paper's own evidence supports. If a claim is speculative ("we hypothesize that…"), mark it as such in the body.
