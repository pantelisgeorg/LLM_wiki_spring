### Extractor: find_relevant_entities

Scan the source for named things that deserve their own wiki page. Candidates include:

- **People** — authors, speakers, researchers, practitioners, **historical figures cited as authorities** (e.g. "Aristotle says in *Metaphysics*…", "according to Plato in *Theaetetus*…"), thinkers the source attributes claims to, or anyone whose work/quotes the source draws on. A figure cited multiple times as a source-of-record is a substantial entity, not a passing mention.
- **Works** — named books, papers, treatises, dialogues, articles the source quotes from or cites (e.g. Aristotle's *Metaphysics*, Plato's *Theaetetus*, Homer's *Iliad*). If a work is cited by name more than once, give it its own entity page.
- **Organizations** — companies, labs, research groups, schools of thought, institutions.
- **Tools / systems / models** — named software, products, APIs, trained models.
- **Datasets / benchmarks** — named data artifacts.
- **Places** — if the source's topic makes a specific location material (Miletus, Athens, …).

**Page depth is tiered by role — do NOT spend the same effort on every entity.** Length budget is per-entity, not per-page-globally:

- **Main subject(s) of the chunk** (Thales in a chunk about Thales): 1200–2000 chars. Opening sentence, 2–4 paragraphs covering thesis, claims, connections, structural role.
- **Cited authorities** (Aristotle attesting Thales, Plato quoting him, Theophrastus interpreting him): **300–700 chars is enough**. One sentence on who they are + 2–3 sentences on what they said about this chunk's subject. Use shorter pages so you can include MORE such entities without blowing the budget.
- **Mentioned but undeveloped** (a place named once, a figure cited in passing without a claim): skip OR a single sentence stub.

The point: a single chunk's output should be **many short entity pages plus one or two long ones**, not just one or two long pages. **The failure mode is missing entities, not under-developed entities.** When in doubt, include the entity with a 300-char page rather than skipping.

Every entity page ends with a `## Sources` section listing `[[wiki/sources/<slug>.md]]`. Cross-link to other pages via `[[wiki/entities/...]]` / `[[wiki/concepts/...]]` when natural.

**Pad nothing — use only material grounded in this source.** But also: do not skip Aristotle just because the chunk is "about Thales." If the chunk cites Aristotle's *Metaphysics* attributing the water-as-archē claim to Thales, **Aristotle gets a page** — short, but he gets one.

A chunk that introduces 4 figures should produce 4 entity pages (1 long + 3 short), not 1 page. Survey / historical sources yield many entities per chunk specifically because they cite many authorities — that's the whole point.
