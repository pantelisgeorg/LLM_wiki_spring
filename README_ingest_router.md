# Ingest router upgrade

Routed, per-source-type ingest with composable extractors and a quality-gate pass. Replaces the single monolithic `ingest.txt` prompt. Inspired by the [Fabric patterns](https://github.com/danielmiessler/Fabric/tree/main/data/patterns) repo.

See the main [README.md](README.md) for the app's overall architecture. This file only covers the ingest pipeline changes.

---

## Why

Before: every source — PDF paper, blog article, web page, user note — ran through the same 74-line `ingest.txt`. The LLM had to simultaneously detect *what* kind of thing it was reading and *what* to extract from it. Outputs were inconsistent across source types.

After: ingest is split into four phases — **classify → dispatch → extract → gate** — each with its own small, tunable prompt. Per-type extraction rules live in their own files. Cross-cutting extractors (entity scanning, concept mapping, etc.) compose into each type. A quality-gate LLM pass reviews the candidate edits before they hit disk.

No user-facing workflow change. Nothing in the UI or the REST API moved. The wiki on disk is structurally the same.

---

## Flow

```
source (file / URL / bytes)
  └─► RawSourceLoader.load*       →  LoadedSource { title, text, sourcePath }
       └─► WikiController.ingest
            ├─► WikiAgent.classify(source)                   # 1 LLM call per source
            ├─► for each chunk:
            │     └─► WikiAgent.ingest(chunk, path, type)
            │          ├─ assembles: ingest.txt + system_<type>.md + extractors/*.md
            │          ├─ LLM call → IngestResult JSON
            │          ├─ qualityGate(result, type)         # 1 LLM call per chunk
            │          │    assembles: gate.txt + gates/*.md + candidate edits
            │          │    → LLM call → revised IngestResult
            │          └─ WikiStore.applyEdits(revised)
            └─ done
```

**Call budget** for a 5-chunk PDF classified as `paper`: 1 classify + 5 extract + 5 gate = 11 LLM calls. On `gpt-4o-mini` this is cheap. For a future local 4B model, both the classifier and the gate are individually toggleable — see [Tuning for local vs cloud](#tuning-for-local-vs-cloud).

---

## File layout

```
src/main/resources/prompts/
├── ingest.txt                        ← type-agnostic preamble (rules, JSON schema, index format)
├── classify.txt                      ← source-type classifier
├── gate.txt                          ← quality-gate preamble
├── system_paper.md                   ← per-type extraction guidance
├── system_paper_simple.md
├── system_article.md
├── system_web.md
├── system_youtube.md                 ← transcript loader is a Phase-2 prereq (see below)
├── system_podcast.md                 ← transcript loader is a Phase-2 prereq (see below)
├── system_book_chapter.md
├── system_concept_note.md
├── system_generic.md                 ← fallback when classifier is unsure or disabled
├── extractors/
│   ├── find_relevant_entities.md     ← cross-cutting snippet — surface people/tools/orgs
│   ├── create_conceptmap.md          ← surface abstract concepts + relations
│   ├── extract_patterns.md           ← recurring themes across a source
│   ├── extract_predictions.md        ← testable/falsifiable claims
│   ├── extract_book_recommendations.md  ← references / mentions of other works
│   └── capture_thinkers_work.md      ← author/speaker-focused extraction
└── gates/
    ├── check_falsifiability.md       ← reviews candidate edits for unfalsifiable claims
    ├── find_logical_fallacies.md     ← flags and tightens fallacious reasoning
    └── fact_check.md                 ← flags specifics the source didn't support
```

Each `system_<type>.md` starts with YAML frontmatter listing which `extractors/` snippets compose in:

```yaml
---
includes:
  - find_relevant_entities
  - create_conceptmap
  - extract_predictions
---
```

`WikiAgent.composeSystemPrompt(type)` parses that block, appends each listed extractor after the body, and caches the result per type for process lifetime.

---

## Configuration

In `src/main/resources/application.yml`:

```yaml
wiki:
  ingest:
    classifier:
      enabled: true
      known-types:
        - paper
        - paper_simple
        - article
        - web
        - youtube
        - podcast
        - book_chapter
        - concept_note
        - generic
    quality-gate:
      enabled: true
      type-gates:
        paper:        [check_falsifiability, fact_check, find_logical_fallacies]
        paper_simple: [fact_check]
        article:      [find_logical_fallacies, fact_check]
        web:          [fact_check]
        youtube:      [fact_check]
        podcast:      [fact_check]
        book_chapter: [find_logical_fallacies]
        concept_note: []
        generic:      [fact_check]
```

- `classifier.enabled: false` → `classify()` short-circuits to `generic`, no LLM call. `system_generic.md` runs for every source.
- `classifier.known-types` is injected into the classifier prompt, so the prompt and the whitelist stay in sync. Removing a type from this list *and* from `type-gates` cleanly retires it — any old source already classified under that type will cleanly fall back to `generic` on re-ingest.
- `quality-gate.enabled: false` → `qualityGate()` returns the candidate unchanged, no LLM call. Skip this first when moving to a slow local model.
- `quality-gate.type-gates.<type>: []` → gate call is skipped for that type (zero tokens wasted) but still shows up in logs.

Binding is handled by [IngestProperties.java](src/main/java/com/wiki/service/IngestProperties.java) via `@ConfigurationProperties(prefix = "wiki.ingest")`.

---

## How to extend

### Add a new source type

1. Add the type name to `wiki.ingest.classifier.known-types` in `application.yml`.
2. Create `src/main/resources/prompts/system_<type>.md` with YAML frontmatter and type-specific extraction guidance. Copy `system_generic.md` as a starting point.
3. Add an entry under `wiki.ingest.quality-gate.type-gates` naming which gates should run on this type (can be `[]`).
4. Optionally update `classify.txt` with a definition and decision rule so the classifier knows when to pick it.

No Java changes needed. Restart the app; the new type is live.

### Add a new extractor

1. Create `src/main/resources/prompts/extractors/<name>.md` — a short snippet (10–20 lines), no preamble, no output schema (both are inherited).
2. Add the name to one or more `system_<type>.md` files' `includes:` frontmatter list.

### Add a new quality gate

1. Create `src/main/resources/prompts/gates/<name>.md` — describe what failure looks like and how to remediate (tighten vs. drop).
2. Add the name to `type-gates.<type>: [...]` in `application.yml` for every type it should run on.

---

## Tuning for local vs cloud

The current default is cloud `gpt-4o-mini` (128K ctx, 6000 max-tokens). All token budgets target comfort there.

When swapping back to a local 4B model after the planned 32 GB RAM upgrade:

1. **First flip `quality-gate.enabled: false`.** That removes one LLM call per chunk. A 5-chunk ingest drops from 11 calls to 6.
2. **If local latency is still rough, flip `classifier.enabled: false` too.** Ingest routes everything to `system_generic.md`. Costs type-specific quality but is the leanest mode.
3. **Measure composed prompt size** on a representative ingest — look for the `"Ingest prompt: N chars"` log line. Target ≤ 6000 tokens input (≈ 24000 chars). If the `paper` composition (4 extractors) blows through it, trim an extractor from `system_paper.md`'s `includes:` list.
4. Gate calls have their own budget: aggregate JSON + gate preamble + 3 gate snippets. Should stay well under 4000 input tokens. If not, shorten the slowest gate snippet.

---

## Verification

Manual smoke tests, in order of cheapness:

1. **Classifier sanity** — set both flags `false`, ingest an arxiv PDF, confirm behavior matches pre-upgrade. Re-enable classifier only; expect `type=paper` in the log and a paper-flavored source summary page.
2. **Per-type smoke test** — ingest one sample each for paper, paper_simple, article, web, book_chapter, concept_note, generic. For each:
   - Controller logs `type: <expected>` before any LLM call.
   - `wiki/sources/<slug>.md` has the type's section scaffold (e.g., paper → `## Thesis` / `## Methods`; article → `## Ideas` / `## Quotes`).
   - `wiki/entities/` and `wiki/concepts/` fill in per the extractors composed by that type.
3. **Gate effectiveness** — ingest a source you've salted with a deliberate unfalsifiable claim ("this method is always better"). Gate ON: expect it tightened or dropped. Gate OFF: expect it to survive. The delta proves the gate is doing real work.
4. **Gate failure safety** — mock a malformed gate response and confirm the agent logs `Quality-gate call failed … keeping original aggregate` and the candidate edits still apply. The gate must never make output *worse* than gate-off.
5. **Fallback path** — rename `system_paper.md` out of the classpath for one run, re-ingest a paper, confirm `loadOrDefault` falls back to `system_generic.md` cleanly.
6. **Regression** — run one `/api/query` and one `/api/lint` after the new ingests. Neither should touch any of the new prompts; both should still parse the wiki.

---

## Phase 2: YouTube / podcast transcript loader (not yet implemented)

`system_youtube.md` and `system_podcast.md` exist but are useless until the source text is an actual transcript. `RawSourceLoader.loadFromUrl()` currently uses jsoup — a YouTube URL fetches the HTML shell, not captions.

Preferred fix: shell out to `yt-dlp --write-auto-sub --skip-download --sub-format vtt`, parse VTT → plain text. Covers YouTube + Substack audio + ~1000 other sites. Requires `yt-dlp` as a prereq.

Until that lands, the classifier should treat YouTube URLs as `web`. If you notice the classifier picking `youtube` on an HTML-shell ingest, edit the decision rules in `classify.txt` to require timestamped lines.

---

## Critical files

- [src/main/java/com/wiki/service/WikiAgent.java](src/main/java/com/wiki/service/WikiAgent.java) — `classify()`, `composeSystemPrompt()`, `parseFrontmatter()`, `qualityGate()`, `ingest(source, canonicalPath, type)`
- [src/main/java/com/wiki/service/PromptLoader.java](src/main/java/com/wiki/service/PromptLoader.java) — `loadOrDefault()`
- [src/main/java/com/wiki/service/IngestProperties.java](src/main/java/com/wiki/service/IngestProperties.java) — config binding
- [src/main/java/com/wiki/WikiController.java](src/main/java/com/wiki/WikiController.java) — wires `classify()` once, threads `type` into each chunk's `ingest()` call
- [src/main/resources/application.yml](src/main/resources/application.yml) — `wiki.ingest.classifier` + `wiki.ingest.quality-gate` blocks
- [src/main/resources/prompts/](src/main/resources/prompts/) — all prompt files
