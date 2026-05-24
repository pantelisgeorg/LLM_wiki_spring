### Extractor: extract_patterns

This extractor governs ONLY the optional `## Patterns` subsection of the source summary — it does not gate concept-page emission, which is handled by `create_conceptmap`. Use it alongside (not instead of) the conceptmap extractor.

Look for recurring themes within this source — things it keeps coming back to, framings it leans on repeatedly, structural moves it makes more than once. These are different from the one-off claims: patterns are the source's *habits of thought*.

When you find a pattern:

- If it's a pattern the source *names*, it is also a concept worth its own page (let `create_conceptmap` produce it). The `## Patterns` subsection then references it via `[[wiki/concepts/<slug>.md]]`.
- If it's a pattern you notice but the source doesn't name, include it as a bullet in the source summary under an additional `## Patterns` subsection (only if you find 2+ such patterns worth noting). Do not manufacture patterns from 1 instance.

For the unnamed `## Patterns` bullets specifically, be conservative — a real recurring pattern will re-appear elsewhere; a spurious one will pollute the concept graph. This caution applies to the bullets only, not to general concept extraction.
