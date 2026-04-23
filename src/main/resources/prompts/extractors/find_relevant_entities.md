### Extractor: find_relevant_entities

Scan the source for named things that deserve their own wiki page. Candidates include:

- **People** — authors, speakers, researchers, practitioners mentioned by name.
- **Organizations** — companies, labs, research groups, standards bodies.
- **Tools / systems / models** — named software, products, APIs, trained models.
- **Datasets / benchmarks** — named data artifacts.
- **Places** — if the source's topic makes a specific location material (rare).

For each entity, produce a concept-poor but well-cited entity page: 2–4 lines stating what it is, who/what produced it, and why the source mentions it. Do not pad with invented context. Each entity page ends with `## Sources` listing `[[wiki/sources/<slug>.md]]`.

Skip entities that are only mentioned in passing with no claim attached. Quality over quantity — respect the 5-edit cap from the preamble.
