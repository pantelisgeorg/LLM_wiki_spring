# Consolidation pass

Cross-linking across concept pages, driven by qmd's semantic relatedness map. A standalone endpoint separate from ingest / query / lint.

See [README.md](README.md) for the overall app architecture and [README_ingest_router.md](README_ingest_router.md) for the prior router upgrade. This file covers the new `/api/consolidate` pipeline only.

---

## Why

The ingest flow only sees one source at a time plus a short title-only index, so concept-to-concept `[[wiki/…]]` links only form when the ingest LLM happens to notice a match from the title. Concepts developed across two different sources rarely cross-reference each other. The graph ends up looking like several small stars orbiting each source instead of a real map of ideas.

qmd already holds a global relatedness map — embeddings for every page in `~/.cache/qmd/index.sqlite` — but it only surfaces that map as transient search results. Nothing writes the map into the wiki as explicit links.

This feature closes that gap: walk every concept page, ask qmd for semantically-near concept neighbors, send each anchor's body + its top-N neighbor bodies to the LLM in one call, get back 0–3 `append`-style `WikiEdit`s that add one sentence of justification plus the `[[wiki/…]]` link. Preview-by-default; `apply: true` is explicit. Concepts-only scope (entities/sources stay as hubs).

---

## Flow

```
POST /api/consolidate  {"apply": false}           ← preview-by-default
  └─► WikiController.consolidate
        ├─► WikiStore.listPages() → filter to wiki/concepts/*.md
        ├─► LinkGraph.build() once
        └─► for each concept page P:
              ├─► WikiAgent.findNeighbors(P)
              │     ├─ qmd.query([{type:vec, query: sanitized seed from P's body}], limit=15)
              │     ├─ filter: drop self, drop non-concepts, drop pairs already linked (either direction)
              │     ├─ filter: drop results with cosine < cosine-floor
              │     └─ return top neighbors-per-page candidates
              └─► if ≥1 eligible neighbor:
                    WikiAgent.consolidatePage(P, neighbors)
                      ├─ compose consolidate.txt prompt with anchor + neighbors
                      ├─ single LLM call
                      ├─ parse ConsolidateResult { edits: [WikiEdit], rationale }
                      └─ post-parse guards: ghost-link, action=append only, must link to a
                         neighbor path, cap at neighbors-per-page
        ├─► if apply=true: WikiStore.applyEdits(all proposed), append log.md entry
        └─► SSE "result" event with ConsolidateReport
```

**Call budget** per run: 1 qmd query + 1 LLM call per eligible concept page. For the current 149-concept wiki, ~149 qmd queries + up to ~149 LLM calls. Cost is cents on gpt-4o-mini. Wall-time is dominated by qmd's CPU reranker — ~50s per page on this machine — so a full run takes ~2 hours.

---

## File layout

```
src/main/resources/
├── application.yml                              ← wiki.consolidate.* block
└── prompts/
    └── consolidate.txt                          ← single-anchor + neighbors → JSON edits

src/main/java/com/wiki/
├── WikiController.java                          ← POST /api/consolidate (SSE)
├── service/
│   ├── ConsolidateProperties.java              ← @ConfigurationProperties
│   ├── WikiAgent.java                          ← findNeighbors + consolidatePage
│   ├── QmdClient.java                          ← reused as-is (query via vec sub-query)
│   ├── LinkGraph.java                          ← reused as-is (idempotency check)
│   └── WikiStore.java                          ← reused as-is (applyEdits, listPages)
└── dto/
    ├── ConsolidateResult.java                  ← { edits: [WikiEdit], rationale: String }
    └── ConsolidateReport.java                  ← endpoint response, includes perPage breakdown
```

---

## Configuration

In [application.yml](src/main/resources/application.yml):

```yaml
wiki:
  consolidate:
    enabled: true
    cosine-floor: 0.40
    neighbors-per-page: 3
    candidates-from-qmd: 15
```

- **`enabled: false`** → endpoint returns 409 with a message. No silent no-ops.
- **`cosine-floor: 0.40`** — the minimum cosine similarity a qmd result must have to become an LLM candidate. Calibrated for qmd's EmbeddingGemma on this corpus: self-matches score ~0.93, genuine siblings score 0.40–0.45, noise sits below 0.35. If you see the LLM linking weak pairs, raise to 0.45; if too few candidates reach the LLM, lower to 0.35.
- **`neighbors-per-page: 3`** — hard cap on how many candidates the LLM sees per anchor and therefore on how many edits it can emit. Three is conservative by design. Raising it thickens the graph but risks noise.
- **`candidates-from-qmd: 15`** — how many results qmd is asked for before the cosine filter trims them. Drop to 8 if the reranker wall-time becomes painful; you rarely care about the 9th-best candidate.

---

## Conservative defaults (baked into the prompt + post-parse guards)

From the `feedback_consolidation_conservative.md` memory:

- **Empty output is a valid, preferred answer.** The prompt explicitly tells the LLM that 0 edits is better than a marginal edit.
- **`action: "append"` only.** Any `upsert` returned by the LLM is dropped post-parse. Consolidation never rewrites a page.
- **Every edit must contain a `[[wiki/concepts/…]]` link to one of the neighbors fed in.** Ghost links are dropped silently with a WARN log.
- **Per-page cap of 3 edits.** Enforced both in the prompt and post-parse. Under-linking is always recoverable by re-running; over-linking is not.
- **Preview-by-default.** `apply: false` (or missing) computes proposals and returns them without touching disk. `apply: true` is explicit.

If the LLM ever returns more edits than allowed, or edits pointing outside the neighbor set, or non-append actions, the post-parse in [WikiAgent.consolidatePage](src/main/java/com/wiki/service/WikiAgent.java) drops them and the rationale field gains a `[post-filter: N → M]` tag for observability.

---

## Using it from the terminal

**Preview** — stream status live, save full response for inspection:

```bash
curl -sN -X POST http://localhost:8080/api/consolidate \
  -H 'Content-Type: application/json' \
  -d '{"apply": false}' \
  > /tmp/consolidate.out &

tail -f /tmp/consolidate.out | grep --line-buffered -E '^data:' | grep -v '^data:{'
```

**Inspect the proposed edits** once the run has logged `done:`:

```bash
grep -A1 '^event:result' /tmp/consolidate.out | tail -1 | sed 's/^data://' \
  | jq -r '.perPage[] | select(.edits|length>0) | "=== \(.path) ===\n" + (.edits | map(.body) | join("\n---\n"))'
```

Each block shows one anchor page and the cross-link paragraphs that would be appended. Eyeball a handful. If they're substantive and on-topic, proceed; if they're filler ("related to the broader topic of…") you have a tuning problem — raise `cosine-floor` or tighten the prompt before applying.

**Apply** — re-runs the pipeline fresh, commits to disk:

```bash
curl -sN -X POST http://localhost:8080/api/consolidate \
  -H 'Content-Type: application/json' \
  -d '{"apply": true}' \
  > /tmp/consolidate-apply.out &

tail -f /tmp/consolidate-apply.out | grep --line-buffered -E '^data:'
```

Important: apply doesn't replay the preview's JSON — it re-runs qmd + LLM from scratch. The LLM is at temperature 0.2 so output is usually very close to preview, but occasionally a marginal pair will flip. In practice this matches the conservative posture: if the LLM wavers, you get the more cautious answer either run.

**Background long-running apply** — don't tie up a terminal for 2 hours:

```bash
nohup curl -sN -X POST http://localhost:8080/api/consolidate \
  -H 'Content-Type: application/json' \
  -d '{"apply": true}' \
  > ~/llm-wiki/consolidate-$(date +%Y%m%d).out 2>&1 &
disown
```

Come back later with `tail -f ~/llm-wiki/consolidate-*.out`.

---

## Using it from the toolbar

Toolbar has a **Consolidate** button between Lint and Graph. Two-phase flow, both phases re-run the LLM end-to-end:

1. **Click Consolidate.** Confirms, then posts `{"apply": false}` and opens the same SSE stream as curl. Status events stream into the middle output pane ("page 47/149: 3 candidates, calling LLM…"). Final `result` event renders into the right pane: header with counts, per-page list of proposed edits, with a sticky top bar hosting an **Apply all N edits** button. All toolbar buttons are disabled for the duration.

2. **Click Apply all N edits.** Second confirm warns that apply regenerates rather than replays the preview (count may drift). Posts `{"apply": true}` and streams the same way. The final result renders as "Consolidate applied" with no further Apply button (since `applied=true`).

Important distinctions vs curl:

- **Apply is not idempotent against preview.** Both phases call qmd + the LLM fresh. The prose you see in preview is illustrative, not a diff-to-be-merged. Temperature is low so drift is small — usually ±1–5 edits out of ~230.
- **The browser tab holds the SSE.** Closing the tab does not stop the server-side pipeline — it'll finish and write. If you need to abort, restart the app.
- **Locks the whole toolbar** for the duration. Ingest/query/lint/qmd/fetch all disabled until the stream closes. A 2-hour run on the full wiki will feel it.
- **Same conservative guards apply.** The apply path runs through `WikiStore.applyEdits`, which additionally enforces path normalization + concept-page dedupe redirects (see [README.md](README.md) for the guard rules).

The curl flow above is still the right choice for 2-hour overnight applies (browser tab would need to stay open); the button is right for preview-and-eyeball on a reasonable-sized wiki.

---

## What to expect after applying

**Graph gets visibly denser** — measure with:

```bash
# before
curl -s http://localhost:8090/api/wiki/graph | jq '.edges | length'
# … apply …
# after
curl -s http://localhost:8090/api/wiki/graph | jq '.edges | length'
```

Expect roughly 200–250 new concept↔concept edges on the current 149-concept wiki. Cluster tightening is visible in the force layout; sibling concepts (RAG flavors, KM-definition variants, etc.) snap together. Orphan concept nodes mostly drop out of the lint report.

**Concept page bodies get substantive tail paragraphs.** This is the real win — not the graph, but the reading experience. Every processed concept page ends with 1–3 short paragraphs explaining how it relates to its closest neighbors, with the `[[wiki/concepts/…]]` link at the end of each paragraph. When you click into a page, you now follow named relations ("is a subset of", "provides the foundation for", "aligns closely with") instead of guessing.

**What does NOT change:**
- Entity pages get no new outgoing edges (concepts-only scope).
- Source pages are untouched.
- Overall topology stays a wiki — sources remain hubs, concepts remain the spokes. The concept region just gets denser internally.

---

## Cadence

Consolidation is not meant to run after every ingest. The natural rhythm:

- **After a "reading sprint"** — you've ingested several new sources over a week → kick off a consolidation overnight. By morning the graph has absorbed the new concepts.
- **Not after single-source ingests** — 2 hours of runtime to incorporate 2 new concept pages is not worth it.
- **Not continuously** — the re-run finds nothing new and burns 2 hours doing it.

Ingest remains unchanged: fast, per-source, touches only pages related to the source. Consolidation is the periodic batch pass.

---

## Tuning knobs in order of impact

If preview output looks noisy (weak pairs linked, filler prose), raise the floor first:

1. **`cosine-floor: 0.40 → 0.45`** — drops marginal qmd candidates before they reach the LLM.
2. **Tighten `consolidate.txt`** — emphasize "empty output is better than marginal output."
3. **`neighbors-per-page: 3 → 2`** — forces the LLM to pick fewer pairs even when multiple clear the floor.

If preview output is too sparse (nothing or barely anything linked):

1. **`cosine-floor: 0.40 → 0.35`** — lets more candidates through.
2. **`candidates-from-qmd: 15 → 25`** — wider net before filtering.

If the 2-hour wall time becomes a pain point (not today, but might at 300+ concepts):

1. **`candidates-from-qmd: 15 → 8`** — roughly halves the reranker work per page.
2. Add a `sinceDays` filter so only recently-ingested concept pages become anchors (not built yet; ~10 lines if needed).
3. Add a per-page content-hash cache so unchanged pages are skipped (not built yet; larger change).

---

## Known non-features

- **`sinceDays` filter.** Not implemented; consolidation processes every concept page unconditionally. A small addition (~10 lines) if wall-time incremental updates become valuable.
- **Delta cache by content hash.** Not implemented. Every run re-embeds via qmd and re-calls the LLM on every concept page. At 149 concepts that's 2 hours; at 500 concepts it'd be 7 hours. If that cadence hurts, the cache is the fix.
- **Entity and source cross-links.** Out of scope by design. Entity nodes stay hubs; source pages stay source-scoped.
- **Bidirectional rewrites.** Consolidation appends from the anchor side only. The old concept doesn't get a paragraph naming the new concept; the backlinks panel surfaces that relationship instead. If you ever want true bidirectional prose rewrites, run an occasional full pass in both directions (not automated today).

---

## Critical files

- [src/main/java/com/wiki/service/WikiAgent.java](src/main/java/com/wiki/service/WikiAgent.java) — `findNeighbors()`, `consolidatePage()`, `seedTextFor()`, `Neighbor` record
- [src/main/java/com/wiki/WikiController.java](src/main/java/com/wiki/WikiController.java) — `POST /api/consolidate`, `runConsolidation()`
- [src/main/java/com/wiki/service/ConsolidateProperties.java](src/main/java/com/wiki/service/ConsolidateProperties.java) — config binding
- [src/main/java/com/wiki/dto/ConsolidateResult.java](src/main/java/com/wiki/dto/ConsolidateResult.java), [ConsolidateReport.java](src/main/java/com/wiki/dto/ConsolidateReport.java) — response shapes
- [src/main/resources/prompts/consolidate.txt](src/main/resources/prompts/consolidate.txt) — the prompt
- [src/main/resources/application.yml](src/main/resources/application.yml) — `wiki.consolidate.*` block
- [src/main/resources/static/index.html](src/main/resources/static/index.html) — Consolidate toolbar button (`#btn-consolidate`)
- [src/main/resources/static/app.js](src/main/resources/static/app.js) — `btn-consolidate.onclick` preview trigger, `renderConsolidateReport()` for the preview/applied render + sticky Apply button
