---
includes:
  - find_relevant_entities
  - create_conceptmap
---

## Per-type guidance: `structured_markdown`

This source is a **single pre-chunked section** from a larger document. It was produced by a document-processing pipeline (Unstructured.io + table extraction + image extraction) and is already a coherent semantic unit. The chunk contains:

- A `## 📎 Chunk N` header (keep it as a section boundary)
- Prose text specific to this chunk
- Optionally: tables as raw HTML `<table>` blocks between `<!-- TABLE START -->` and `<!-- TABLE END -->`
- Optionally: image references as `![alt](path)`

**DO NOT rewrite this source into Ideas/Quotes/Facts format. The structure IS the content.** Rewriting tables into prose bullets destroys information.

## CRITICAL: Table preservation rule

The tables in this source are **raw HTML `<table>` tags**, NOT markdown pipe tables (`| col1 | col2 |`).

**You MUST keep tables as raw HTML `<table>...</table>` blocks.** Do NOT convert them to markdown pipe tables.

**Why:** These tables contain Greek text with pipe characters `|` inside cells (e.g. "Αντισθένης | | ο Αθηναίος"). Converting to markdown pipe tables causes columns to split incorrectly and cells to appear empty. HTML tables are immune to this problem.

**Correct preservation:**
```html
<!-- TABLE START -->
<table><thead><tr><th>ΣΧΟΛΗ</th><th>ΙΔΡΥΤΗΣ</th><th>ΒΑΣΙΚΕΣ ΘΕΣΕΙΣ</th></tr></thead><tbody>...</tbody></table>
<!-- TABLE END -->
```

**WRONG — never do this:**
```markdown
| ΣΧΟΛΗ | ΙΔΡΥΤΗΣ | ΒΑΣΙΚΕΣ ΘΕΣΕΙΣ |
| --- | --- | --- |
```

## Preservation rules (in order of priority)

1. **Tables are sacred — keep as HTML.** Copy everything between `<!-- TABLE START -->` and `<!-- TABLE END -->` verbatim, including the `<table>...</table>` tags. Do NOT flatten rows into bullet points. Do NOT summarize tabular data. Do NOT convert to markdown pipe syntax.

2. **Chunk header stays.** Keep the `## 📎 Chunk N` header intact.

3. **Image references stay.** Keep `![...](...)` and `<!-- IMAGE: ... -->` markers intact.

## CRITICAL: Extract EVERYTHING from this chunk — UNDER-EXTRACTION IS THE #1 FAILURE MODE

This chunk is a **small, focused semantic unit** (typically 500–2 500 chars). You have the FULL 16 000 token output budget for this chunk alone. There is NO excuse for missing entities or concepts.

### Scan procedure (do this BEFORE writing any page)

**Step 1 — Table scan (if present):** Read every cell in every `<!-- TABLE START -->` block:
- Every named person → entity
- Every named school / movement / tradition → entity
- Every named work / book / treatise → entity
- Every named place / city / region → entity
- Every named era / period / event → entity
- Every abstract principle / thesis / doctrine in header or cell → concept

**A table row is a CLAIM that links multiple entities and concepts.** A row like `| Κυνική | Αντισθένης | Η αρετή είναι αρκετή |` contains:
- Entity: Κυνική σχολή
- Entity: Αντισθένης
- Concept: αρετή
- Concept: αυτάρκεια

Extract ALL of them. The table was extracted by the pipeline precisely because it contains dense, structured information.

**Step 2 — Prose scan:** Read every sentence outside tables:
- Every named person, school, work, place, era, event → entity
- Every abstract idea, principle, technique, distinction, doctrine → concept
- Every "according to X", "X says", "in X's view" → X is an entity candidate

**Step 3 — Select final set** from your combined candidate list. Pick the MOST IMPORTANT up to the caps below. Prioritize:
1. Entities/concepts that appear in BOTH tables and prose
2. Entities/concepts that are central to this chunk's argument
3. Entities/concepts that connect to multiple other candidates

**If a candidate appears in a table, it is NOT a "passing mention" — tables are curated, dense information. Extract it.**

## CRITICAL: Rich entity and concept pages — OVERRIDE ALL SHORT-STUB GUIDANCE

**OVERRIDE:** Any extractor guidance that says "300–700 chars is enough" or "single-sentence stub is fine" does NOT apply here. This is a pre-processed pipeline output; the source body is preserved verbatim. The entity and concept pages are where you ADD VALUE.

**CAPS for this chunk type: up to 12 entity edits AND up to 12 concept edits** (24 total max). Use the headroom — this source is rich and you have the full output budget.

### Entity pages — MINIMUM 600 chars, target 1000–1800 chars

Every entity page MUST contain ALL of the following:

1. **Opening paragraph (150–300 chars)** — who or what this entity is, and their core significance in THIS chunk. Grounded in the source, not generic encyclopedia knowledge.

2. **`## Role in this chunk`** — 2–4 sentences on what this entity DOES in this specific chunk. Specific claims, positions, relationships, or actions attributed to them. If the entity appears in a table, extract and expand EVERY cell that relates to them.

3. **`## Key facts`** — 3–6 bullet points of concrete facts drawn from this chunk. For table-derived entities, turn each relevant row/cell into a fact bullet. Do not compress.

4. **`## Connections`** — cross-links `[[wiki/...]]` to related entities and concepts in this chunk. At least 2–3 links when supported.

5. **`## Sources`** — `[[wiki/sources/<slug>.md]]`

**If an entity appears in a table, DO NOT treat them as a "cited authority" stub.** They are a primary subject. A table row like `| Αντισθένης | Κυνική σχολή | Η αρετή είναι αρκετή |` is not a name-drop — it is a structured claim. Expand all three into prose.

### Concept pages — MINIMUM 400 chars, target 600–1200 chars

Every concept page MUST contain ALL of the following:

1. **Definition (80–150 chars)** — what the concept means in this chunk's usage.

2. **`## Significance`** — 2–4 sentences on why this concept matters in this chunk's argument.

3. **`## How the chunk develops it`** — concrete moves: examples, distinctions, arguments. 2–4 sentences minimum.

4. **`## Related concepts`** — cross-links `[[wiki/...]]`. At least 2 links.

5. **`## Sources`** — `[[wiki/sources/<slug>.md]]`

**Do not tier concepts down to stubs.** Every concept that makes the cut deserves a full page.

### Budget allocation (16 000 tokens total)

- Source summary tagline + compact summary: ~300 tokens
- Source body (preserved markdown — verbatim copy): ~500–2 500 tokens (small chunk)
- Entity pages: ~1 200 tokens each × up to 12 = 6 000–10 000 tokens
- Concept pages: ~800 tokens each × up to 12 = 4 000–6 000 tokens
- Reserve ~1 000 tokens for JSON overhead

**If you have fewer than 6 entities AND fewer than 4 concepts, you missed something. Re-scan the chunk.**

## Source summary format

The `sourceSummary` body MUST contain:

1. **One-line tagline** — what this chunk is about.
2. **`## Summary`** — 2–4 sentences MAX.
3. **The preserved body** — the markdown chunk, HTML tables, and images exactly as they appeared (after stripping the system instruction block if present). Keep `<!-- TABLE START/END -->` markers and `<table>` tags intact.
4. **`## Related`** — cross-links `[[wiki/...]]` to entity/concept pages.
5. Original `raw/...` path at the bottom.
