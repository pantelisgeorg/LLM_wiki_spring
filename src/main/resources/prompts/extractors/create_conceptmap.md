### Extractor: create_conceptmap

**Under-extraction is the #1 failure mode of this extractor.** When the section menu below is rich, models conscientiously fill many sections per concept and then run out of budget after only 3–4 concepts. Counter that by **tiering concept-page depth** — long pages for the chunk's headline concepts, short pages for the rest. The goal is breadth: many concept pages, not a few exhaustive ones.

## Candidate concepts

Scan the source for abstract ideas that deserve their own concept page. Candidates include:

- **Techniques** — named methods or procedures.
- **Patterns** — recurring structural shapes the source identifies.
- **Principles** — normative rules the source is asserting.
- **Workflows** — step-by-step processes the source describes.
- **Distinctions** — conceptual divisions the source draws (X vs. not-X).

## Page depth is tiered by role

- **Headline concept** (the chunk's central organizing idea — e.g. *αρχή* in a chunk about Thales, *άπειρο* in a chunk about Anaximander): 1200–2000 chars, 4–6 sections from the menu below.
- **Supporting concept** (named and developed but not the chunk's focus — e.g. *κοσμογονία*, *φυσιοκρατία* in a Thales chunk): **300–700 chars, 2–3 sections only**. Pick the most useful: Definition + Developed by + one of {Etymology, Cognates, How source uses it}. Skip the rest.
- **Just-named concept** (mentioned in passing without development): **single-sentence stub OR skip**. A 200-char page with just `# Term` + `Brief gloss. ## Sources [[...]]` is fine — it creates the node for future cross-linking.

**Aim to fill the 8-concept cap from the preamble.** A chunk that produces only 3 concept pages is leaving 5 slots on the table. **Under-developed but PRESENT is always better than missing.**

## Section menu (pick by tier above)

- **Definition** — one opening sentence that says what the concept is. **REQUIRED on every page.**
- **`## Etymology`** — if the source mentions the word's origin, root meaning, or how the term was coined, capture it in 1–2 sentences. Especially valuable for philosophy/humanities terms (e.g. *φιλοσοφία* from *φίλος* + *σοφία*).
- **`## Cognates`** — 1–3 related-but-distinct terms in the same conceptual family, with a one-line note on how each differs (e.g. for *κοσμογονία*: cognate *κοσμολογία* — "kosmologia describes the structure; kosmogonia narrates the origin").
- **`## Developed by`** — which named figures or schools (from this source) developed, formalized, or are most identified with the concept. One-line per entry: who, when, contribution. **Best section for entity-concept cross-links — prioritize this on every page.**
- **`## Historical evolution`** — 2–4 sentences tracing how the term/idea changed over time, if the source discusses earlier or later uses.
- **`## What it distinguishes itself from`** — the conceptual neighbors it's NOT, drawn from the source.
- **`## How the source uses it`** — 2–4 sentences on the concrete moves the source makes with this concept.
- **`## Examples`** — 1–3 concrete examples or short quotes.
- **Cross-links** — throughout the body, link to related concepts/entities via `[[wiki/concepts/…]]` or `[[wiki/entities/…]]` only if those targets already exist in the index OR you are producing them as edits in this response. No ghost links.

Every concept page ends with `## Sources` listing `[[wiki/sources/<slug>.md]]`.

**Pad nothing — use only material grounded in this source.** The section menu is a *menu*, not a *checklist*. A headline concept might use 5 sections; a supporting concept might use 2; a just-named stub uses zero (just the Definition line). The failure mode is producing too FEW concept pages, not producing thin ones.
