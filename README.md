# llm-wiki

A local Spring Boot web app that implements the **LLM Wiki** pattern: instead of RAG-at-query-time, an LLM incrementally maintains a markdown knowledge base. Ingest a source → it updates entity pages, concept pages, an index, and a log. Ask a question → it picks pages from the index and answers with citations.

Originally spec'd from Karpathy's [`llm-wiki.md`](llm_wiki.md). See that doc for the "why"; this README covers the "how".

---

## Status

Working end-to-end:
- **Ingest** — markdown / text / PDF / URL; auto-chunks long sources at `##` boundaries
- **Ask** — index-driven page selection + cited answer (was "Query")
- **Search** — semantic search across all wiki pages via OpenAI embeddings (was "QMD"; qmd dependency removed)
- **Fetch** — read files by exact path or glob (e.g. `wiki/entities/*.md`)
- **Lint** — gap / orphan / contradiction / stale-claim detection over index + log
- **Consolidate** — proposes cross-links between semantically-related entity and concept pages; recovers when the LLM forgets to format the link properly
- **Graph view** — force-directed visualization of `[[wiki/…]]` cross-links (entities + concepts only; sources hidden)
- **Reset** — wipe `wiki/` (preserve `raw/`) with two-confirm safety
- **Backlinks panel** — every page shows which other pages cite it
- **Fuzzy tree search** — `Ctrl/Cmd+K` filters the sidebar by substring
- **Outline / TOC** — auto-extracted `##`/`###` headings pinned to the top of each page
- **Multi-topic launcher** — `bin/llm-wiki --topic NAME` runs one isolated wiki per topic on a stable port
- **Multilingual** — Greek, Cyrillic, CJK content fully supported (all path parsers use Unicode-aware regex)

Default config uses **OpenAI** (`gpt-4o-mini` for chat, `text-embedding-3-small` for embeddings). The original LM-Studio-only path still works if you swap the config — see Configuration.

---

## Architecture

Three layers, mirroring `llm_wiki.md`:

```
~/llm-wiki/                       ← the wiki root, a normal git repo
├── CLAUDE.md                     ← schema + conventions for the LLM (seeded on first run)
├── raw/                          ← user-curated sources, immutable to the LLM
│   ├── articles/                 ← .md / .txt
│   ├── pdfs/
│   ├── web/                      ← URLs fetched & converted
│   └── assets/                   ← images
└── wiki/                         ← LLM-owned, all markdown
    ├── index.md                  ← catalog — retrieval entry point
    ├── log.md                    ← chronological ingest / query / lint log
    ├── entities/                 ← proper-noun pages (people, works, places, organizations)
    ├── concepts/                 ← abstract-idea pages
    └── sources/                  ← one summary per ingested source (one per chunk for chunked ingest)
```

Point Obsidian at `~/llm-wiki/` to browse. The app owns `wiki/`; the user owns `raw/`. `git init` inside `~/llm-wiki/` and you get version control for free.

### Code layout

```
src/main/java/com/wiki/
├── LlmWikiApplication.java       ← Spring Boot main
├── WikiController.java           ← REST + SSE endpoints
├── QmdController.java            ← /api/qmd/* — kept the path name for UI compatibility,
│                                   now backed by EmbeddingService + filesystem, no daemon
├── service/
│   ├── RawSourceLoader.java      ← md/pdf/url/image → plain text
│   ├── Chunker.java              ← splits long text on ## / paragraph boundaries
│   ├── WikiStore.java            ← filesystem IO (only layer that writes disk); Unicode-aware paths
│   ├── WikiSearch.java           ← "ask LLM which pages from the index"
│   ├── WikiAgent.java            ← orchestrates ingest/query/lint/consolidate prompts
│   ├── LinkGraph.java            ← parses [[wiki/…]] refs → outgoing/incoming maps
│   ├── EmbeddingService.java     ← in-memory OpenAI embeddings: search, neighbor lookup
│   └── PromptLoader.java         ← loads + caches classpath prompts
└── dto/
    ├── WikiEdit.java             ← { path, action: upsert|append, body }
    ├── IngestResult.java         ← { sourceSummary, edits[], indexEntry, logLine }
    ├── QueryAnswer.java          ← { markdown, citations[] }
    ├── LintReport.java           ← { issues: [{ type, page, description }] }
    ├── ConsolidateReport.java    ← { proposedEdits[], perPage[], applied, ... }
    └── LoadedSource.java         ← { title, text, sourcePath }

src/main/resources/
├── application.yml
├── prompts/
│   ├── classify.txt              ← picks a source type (paper / article / podcast / etc.)
│   ├── ingest.txt                ← creates/updates pages from a new source
│   ├── query.txt                 ← cited answer from selected pages (Ask button)
│   ├── search.txt                ← index → relevant-pages selector
│   ├── lint.txt                  ← integrity audit over index + log
│   ├── consolidate.txt           ← cross-link proposals between semantically-near pages
│   ├── gate.txt                  ← optional post-ingest quality pass
│   ├── extractors/               ← per-type composable add-ons (find_relevant_entities,
│   │                                create_conceptmap, extract_patterns, ...)
│   ├── gates/                    ← per-type quality checks (fact_check, find_logical_fallacies,
│   │                                check_falsifiability)
│   └── system_<type>.md          ← per-type system prompt (article, paper, podcast, ...)
├── seed/CLAUDE.md                ← copied into wiki-root on first run
└── static/
    ├── index.html                ← 3-pane layout + graph overlay
    └── app.js                    ← vanilla JS + EventSource + marked.js + vis-network

bin/
└── llm-wiki                      ← multi-topic launcher (port = hash(topic))
```

The semantic-search and consolidation layers used to run via an external `qmd` daemon (Node, local BM25 + EmbeddingGemma + LLM rerank). That was replaced with in-process OpenAI embeddings in [EmbeddingService.java](src/main/java/com/wiki/service/EmbeddingService.java) — faster, multilingual, no daemon to manage. The `/api/qmd/*` URL paths were kept so the frontend didn't need to change.

---

## Quickstart

### Prereqs

- Java 21+ (tested on Java 25)
- Maven 3.8+
- An **OpenAI API key** with access to `gpt-4o-mini` (chat) and `text-embedding-3-small` (embeddings). Cost for typical use is pennies; embedding a 30-page wiki cold is roughly $0.0001.

### On a fresh machine (end-to-end)

```bash
# 1. Install Java 21+ and Maven
#    Debian/Ubuntu: sudo apt install openjdk-21-jdk maven
#    macOS:         brew install openjdk@21 maven

# 2. Clone, set your API key, boot
git clone <this-repo> llm-wiki && cd llm-wiki
export OPENAI_API_KEY=sk-...
mvn spring-boot:run

# 3. Open the UI
xdg-open http://localhost:8080   # macOS: open
```

First boot seeds `~/llm-wiki/` with the directory skeleton, empty `index.md` / `log.md`, and a `CLAUDE.md` schema file for humans and Claude Code. On startup you should see:
```
Initial embedding load: no changes (0 vectors, 0 removed)
Started LlmWikiApplication in 2.1 seconds
```
The `~/llm-wiki/` folder is persisted across runs — `git init` inside it if you want version control.

### Multi-topic (one wiki per domain)

```bash
bin/llm-wiki --topic ml          # → ~/wikis/ml       on port 8091
bin/llm-wiki --topic cooking     # → ~/wikis/cooking  on port 8099
bin/llm-wiki --topic research    # → ~/wikis/research on port 8096
```

Port is a deterministic hash of the topic name, stable across runs. Override with `--port` or `--root`. Each instance is fully isolated: its own filesystem, its own index, its own embedding cache, its own graph. Same OpenAI key serves all of them.

---

## Using it

The UI has one shared input at the top and a row of action buttons: **Ingest · Ask · Search · Fetch · Lint · Consolidate · Graph · Clear · Reset**. Hover any button for a one-line description.

### Ingest

Paste a URL, a local file path, or click **File…** and pick a file, then **Ingest**.

The pipeline:
1. Load → classify the source type (article / paper / podcast / …)
2. Compose a per-type system prompt from `system_<type>.md` + its included extractors
3. Chunk the source if it's over 10K chars
4. For each chunk: LLM call → quality-gate pass → write entity/concept/source pages
5. After the last chunk: refresh embeddings so Search/Consolidate see the new pages

**Short sources** (≤10K chars) go through in one LLM call:
```
[status] loaded: title (N chars)
[status] classifying source type
[status] calling LLM (this can take 30–90s on local models, ~5–15s on gpt-4o-mini)
[status] applied K edits + 1 source summary
[status] embeddings: embedded K+1 new, removed 0, total N
[result] {…}
```

**Long sources** auto-chunk at `##` heading boundaries (8K chars target per chunk) and stream per-chunk progress. By chunk 2 the index already shows chunk 1's pages, so the LLM extends existing pages rather than recreating them — the same compounding mechanic that makes multi-source ingest work, applied intra-document.

Per-chunk caps in [ingest.txt](src/main/resources/prompts/ingest.txt): **8 entities + 8 concepts + 1 source summary**, tiered page length (300–700 chars for cited authorities, 1200–2500 chars for chunk subjects). Caps + tiering let gpt-4o-mini emit many short pages plus a few long ones, instead of one or two long ones.

#### Resuming a chunked ingest

If a chunk fails mid-run, re-select the same file, type the chunk number to resume at in the **"start chunk"** header field, and click **Ingest**. The chunker is deterministic over the same input + thresholds, so chunk N's split boundaries match the original run.

```bash
curl -N -X POST http://localhost:8080/api/ingest/upload \
     -F 'file=@thesis.md' -F 'startChunk=4'
```

### Ask

Type a question → **Ask**. Two LLM calls:
1. `WikiSearch` reads `index.md` and returns up to 8 paths relevant to the question.
2. `WikiAgent.query` reads those pages and composes a cited answer.

Answer renders in the preview pane with inline `[[wiki/…]]` citations and a **Citations** list at the bottom. Every citation is clickable.

### Search

Type a query → **Search**. Embeds the query via OpenAI, cosine-compares against every wiki page's in-memory vector, returns the top 8 ranked by score. Multilingual — Greek query against Greek wiki works.

### Fetch

Routes by input shape:
- contains `*` or `?` → glob (e.g. `wiki/entities/*.md`)
- starts with `#` → docid lookup (deprecated; predates the embedding rewrite)
- otherwise → exact path read (e.g. `wiki/entities/aristotle.md`)

Reads directly off disk, no LLM, no embedding work.

### Lint

**Lint** — no input needed. Reads the authoritative page list + `index.md` + `log.md` (prior lint entries stripped to avoid self-reference) and reports:
- `orphan` — page not linked anywhere, or index entry pointing at a missing file
- `gap` — cross-link implies a page that doesn't exist
- `contradiction` — two pages disagree
- `stale` — claim older than its source would warrant

Each issue whose `page` field is a valid `wiki/…/*.md` path gets a **Delete** button in the preview. Click it to remove the file *and* prune any matching line in `wiki/index.md`. Reserved pages (`wiki/index.md`, `wiki/log.md`) are never deletable.

### Consolidate

Runs over every entity AND concept page. For each anchor:
1. Find semantically-nearest entity/concept neighbors via in-memory cosine (above `wiki.consolidate.cosine-floor`, default 0.20).
2. Ask the LLM to write a short paragraph explaining how the anchor and each neighbor relate, ending with a `[[wiki/(concepts|entities)/X.md]]` link.
3. Validate: body must contain at least one `[[wiki/...]]` link pointing at a listed neighbor. If the LLM omits the link but the body clearly mentions a neighbor (matched by basename), **auto-append the link** — recovery layer.
4. Append each surviving edit to the anchor page.

The two-step preview/apply flow is intentional: preview shows what would change so you can sanity-check, Apply re-runs the LLM and writes. The button to apply appears at the top of the preview pane as a sticky bar.

### Graph, backlinks, fuzzy search, outline

- **Graph** — top-right *Graph* button opens a full-screen force-directed graph. Nodes colored by type (entity=blue, concept=purple), sized by link degree. Sources are hidden by default to reduce clutter (`?includeSources=true` to bring them back). Click any node to jump to its page. Esc closes.
- **Backlinks** — bottom of every page preview lists "Cited by N" with clickable links. Derived from `LinkGraph.backlinks()` which Unicode-regex-scans all pages for `[[wiki/path.md]]` references.
- **Pages filter** — input above the tree sidebar, filters pages by substring as you type. `Ctrl/Cmd+K` focuses it. Pure client-side, no backend.
- **Outline** — a pinned strip at the top of each page preview, auto-built from `##`/`###` headings. Click to scroll.
- **Clear** — header button that resets the right preview pane to the placeholder and strips the page hash from the URL.
- **Reset** — wipes the entire `wiki/` tree (all pages + `index.md` + `log.md`) and re-seeds the empty skeleton. `raw/` and `CLAUDE.md` are preserved. Two confirms required. Clears the in-memory embedding cache.

### curl equivalents

```bash
curl -N -X POST http://localhost:8080/api/ingest \
     -H 'Content-Type: application/json' \
     -d '{"path":"/abs/path/to/file.md"}'

curl -N -X POST http://localhost:8080/api/query \
     -H 'Content-Type: application/json' \
     -d '{"question":"your question"}'

curl -N -X POST http://localhost:8080/api/lint
curl -N -X POST http://localhost:8080/api/consolidate \
     -H 'Content-Type: application/json' -d '{"apply": false}'    # preview
curl -N -X POST http://localhost:8080/api/consolidate \
     -H 'Content-Type: application/json' -d '{"apply": true}'     # write

curl -s http://localhost:8080/api/wiki/tree
curl -s "http://localhost:8080/api/wiki/page?path=wiki/entities/thales.md"
curl -s "http://localhost:8080/api/wiki/backlinks?path=wiki/entities/thales.md"
curl -s http://localhost:8080/api/wiki/graph | python3 -m json.tool | head
curl -s "http://localhost:8080/api/wiki/graph?includeSources=true"
curl -s -X DELETE "http://localhost:8080/api/wiki/page?path=wiki/entities/orphan.md"
curl -s -X POST "http://localhost:8080/api/wiki/reset?confirm=yes"

# Search / Fetch (formerly /api/qmd/*; now backed by EmbeddingService + filesystem)
curl -s http://localhost:8080/api/qmd/status
curl -s -X POST http://localhost:8080/api/qmd/query \
     -H 'Content-Type: application/json' \
     -d '{"query":"αρχή του κόσμου","limit":5}'
curl -s "http://localhost:8080/api/qmd/get?file=wiki/entities/thales.md"
curl -s -X POST http://localhost:8080/api/qmd/multi-get \
     -H 'Content-Type: application/json' \
     -d '{"pattern":"wiki/entities/*.md"}'
```

All `/api/ingest|query|lint|consolidate` endpoints are SSE; use `curl -N` to avoid buffering. The `/api/qmd/*` and `/api/wiki/*` endpoints are plain JSON.

---

## Configuration

`src/main/resources/application.yml`:

```yaml
server:
  port: 8080

wiki:
  root: ${user.home}/llm-wiki
  search:
    max-pages: 8
  consolidate:
    enabled: true
    cosine-floor: 0.20          # text-embedding-3-small clusters in 0.3–0.7 range for related;
                                # 0.20 is a permissive safety net
    neighbors-per-page: 3
  ingest:
    chunk-gap-ms: 3000
    classifier:
      enabled: true
      known-types: [paper, paper_simple, article, web, youtube, podcast, book_chapter, concept_note]
    quality-gate:
      enabled: true
      type-gates:
        paper: [check_falsifiability, fact_check, find_logical_fallacies]
        article: [find_logical_fallacies, fact_check]
        # ...one entry per known type...

spring:
  ai:
    retry:
      max-attempts: 1
    openai:
      base-url: https://api.openai.com
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.2
          max-tokens: 12000     # gpt-4o-mini supports 16384; 12000 leaves headroom over the
                                # ingest prompt's combined entity+concept budget
      embedding:
        options:
          model: text-embedding-3-small   # 1536-dim, multilingual, ~$0.02 per million tokens
  mvc:
    async:
      request-timeout: -1
  servlet:
    multipart:
      max-file-size: 50MB
```

### Swap to LM Studio (local CPU/GPU)

If you want to keep everything local, point Spring AI at LM Studio instead:

```yaml
spring:
  ai:
    openai:
      base-url: http://localhost:1234
      api-key: lm-studio          # ignored by LM Studio, required by Spring AI
      chat:
        options:
          model: qwen3.5-4b       # or whatever you have loaded
          max-tokens: 7000        # local context windows are smaller; tune to fit
```

You'll also want a local embedding endpoint. LM Studio has an embeddings tab — load any sentence-transformer and the OpenAI-compatible `/v1/embeddings` route works.

### Swap to Ollama

1. In `pom.xml` swap `spring-ai-openai-spring-boot-starter` → `spring-ai-ollama-spring-boot-starter`
2. In `application.yml`:
   ```yaml
   spring:
     ai:
       ollama:
         base-url: http://localhost:11434
         chat:
           options:
             model: gemma2:9b
             temperature: 0.2
         embedding:
           options:
             model: nomic-embed-text
   ```

---

## Quirks worked around (so the code makes sense)

1. **Reserved paths.** `wiki/index.md` and `wiki/log.md` are refused as edit targets — the LLM overwrote them once and wiped state. Use `indexEntry` / `logLine` fields instead.
2. **Per-path edit dispatch.** `WikiStore.applyEdits` groups edits by path: multiple `append`s to the same anchor all run (in order); multiple `upsert`s last-write-wins; a mix drops preceding appends. Crucial for Consolidate, which emits multiple appends per anchor — naive dedup-by-path would silently drop ~2/3 of cross-links.
3. **Action aliases.** LLM might say `action: "create"` or `"write"` — these map to `upsert`. Unknown actions log a warning and default to upsert.
4. **Ghost index filter.** `WikiAgent.applyIngest` collects the set of actually-written paths and drops any `indexEntry` line pointing at a path not in that set (and not in the existing index).
5. **Auto-injected source index line.** LLMs sometimes forget to add the source itself to the index. `WikiAgent.ensureSourceIndexed` derives the canonical path from the chunk title and appends a `[Title](path) — hook` line if missing.
6. **Section-routed index append.** `WikiStore.appendIndexEntry` parses the `wiki/…` path in the entry line and inserts under `## Entities`, `## Concepts`, or `## Sources` accordingly.
7. **Path-aware index dedupe.** Appending an entry whose path already appears is a no-op.
8. **Lenient JSON.** Models emit JSON with unescaped newlines, trailing commas, single quotes, `//` comments, and occasionally extra fields the DTO doesn't know about. `WikiAgent.LENIENT` is a `JsonMapper` with the corresponding `JsonReadFeature` flags enabled *and* `FAIL_ON_UNKNOWN_PROPERTIES` disabled.
9. **Lint self-reference.** Lint reads `log.md`. Past `lint | N issues` entries used to spawn fake "stale" findings. `WikiAgent.lint` filters those out of the log before prompting.
10. **SSE error shape.** Errors are emitted as a JSON string, not a `Map`, because SSE's `text/event-stream` converter can't serialise maps. Emitter also ends via `complete()` instead of `completeWithError()` to avoid Spring's default error-page handler clobbering the already-committed stream.
11. **Chunked ingest for long sources.** `Chunker.shouldChunk` trips at 10K chars; `DEFAULT_TARGET` is 8K. The controller loops `agent.ingest(chunk, canonicalSourcePath)` sequentially. Chunks run *in order* so chunk N's prompt sees the accumulated index from chunks 1..N-1. `WikiController.slugifyTitle` derives a kebab-case (Unicode-aware!) base slug from the source title, and `WikiAgent.normalizeSourcePath` rewrites the LLM's chosen summary path so slug drift can't produce broken cross-links.
12. **Retry cap = 1.** Spring AI retries 10× by default on transient errors; against any LLM that can freeze, that turns slow responses into multi-minute retry storms. `spring.ai.retry.max-attempts: 1` fails fast.
13. **Lenient input caps.** `WikiAgent` truncates the source text at 40K chars and query-context pages at 12K chars each. Belt-and-suspenders below the chunker's per-chunk cap.
14. **Truncated-JSON salvage.** When the LLM's output is cut off despite the cap, `WikiAgent.salvageIngestResult` runs a bracket/string-aware scanner over the partial JSON and keeps every complete `{…}` inside `edits`. A partially-successful chunk contributes its N valid edits instead of aborting.
15. **Unicode-aware path parsing throughout.** Java's `\w` and `[a-z0-9]` regexes are ASCII-only by default. The original code used both in 6+ places that parsed `[[wiki/...]]` references and normalized slugs. Result: Greek/Cyrillic/CJK paths were silently invisible — Consolidate proposed zero links, the graph had no edges, dedup collapsed all non-Latin concepts into one canonical page. Every path-touching regex now uses `\p{L}\p{N}` and every normalizer uses `[^\p{L}\p{N}]+`.
16. **Source-summary `/` collapses into a directory.** The LLM may title a chunk `Title (part 3/5)`. `WikiStore.normalizeWikiPath` used to split dir/basename via `lastIndexOf('/')`, which picked the `/` inside "3/5" → wrote a literal subdirectory. Fixed by anchoring on the second `/` (after `wiki/<subdir>/`) and sanitizing everything after.
17. **Entity dedup by case/accent.** `αριστοτέλης.md`, `Αριστοτέλης.md`, `Αριστοτέλης.md` (NFC vs NFD) collide. `WikiStore.buildDupIndex` builds a normalized-slug+H1 lookup per write batch and redirects new edits to the canonical existing page (forced to `append`).
18. **Consolidate auto-recovery.** gpt-4o-mini frequently writes a perfect connecting paragraph between two pages but omits the required `[[wiki/concepts/X.md]]` link. `WikiAgent.recoverNeighborFromBody` scans the body for any neighbor's basename (Greek or English) and auto-appends the correct link, so the edit isn't dropped.
19. **Language drift.** Without explicit instruction, the LLM responds in the language of the prompt (English) regardless of source language. `prompts/ingest.txt` includes a "respond in the language of the source" directive at the top.
20. **Per-category extraction caps.** A combined "max 10 entity-or-concept edits" cap lets a concept-heavy chunk crowd out entities. The prompt now uses split caps: **up to 8 entities AND up to 8 concepts per chunk** (independent).

---

## Debugging

```bash
# server log (mvn spring-boot:run logs to stdout — tee to a file for offline inspection)
mvn spring-boot:run 2>&1 | tee /tmp/wiki.log

# what the model returned (look for "LLM returned N chars in M ms")
grep -E "LLM returned|Quality-gate|Applied|ghost|Consolidate|applyEdits" /tmp/wiki.log

# current wiki state
ls ~/llm-wiki/wiki/{entities,concepts,sources}
cat ~/llm-wiki/wiki/index.md

# embedding service status (also shown in the header badge)
curl -s http://localhost:8080/api/qmd/status | python3 -m json.tool
```

**Common failures:**

| Symptom | Cause | Fix |
|---|---|---|
| `401 Unauthorized` from OpenAI | `OPENAI_API_KEY` not exported or invalid | `export OPENAI_API_KEY=sk-...` in the shell that runs `mvn spring-boot:run` |
| `AsyncRequestTimeoutException` | Spring MVC killing the request | Already handled via `spring.mvc.async.request-timeout: -1` |
| `JsonMappingException: Illegal unquoted character` | Model output has raw `\n` in a string value | Covered by `LENIENT.ALLOW_UNESCAPED_CONTROL_CHARS` |
| `Unrecognized field "source"` | LLM added a field `WikiEdit` doesn't declare | Covered by `FAIL_ON_UNKNOWN_PROPERTIES=false` (quirk #8) |
| `Unknown action: create` | LLM chose a synonym for `upsert` | Covered by alias map in `WikiStore.applyEdit` |
| `Unexpected end-of-input: expecting closing quote` | Model hit `max-tokens` mid-generation | Auto-salvaged (quirk #14); if too many edits are lost, lower `Chunker.DEFAULT_TARGET` or raise `max-tokens` |
| Consolidate proposes 0 / Graph has no edges | Almost always something in the chain of quirks 15–18 | Tail the log for `Consolidate drop` warnings — the body of the warning shows what the LLM produced and why it was rejected |

---

## Stretch (not yet done)

- Call `git commit -m "ingest: <title>"` inside the wiki root after each ingest
- Vision-capable model for image ingest
- Persist embedding cache to disk so cold startup doesn't re-embed everything (currently <2s for ~100 pages, but bigger wikis would benefit)
- Re-enable page-body input to lint for body-level checks
- Aggregate source summary for chunked ingests (one `sources/<slug>.md` instead of one per chunk)
- Single-instance wiki switcher (currently multi-topic requires multiple processes)
- Marp slide-export endpoint
- Expose ingest/query/lint as MCP tools so Claude Code / Codex can drive the same wiki
- Frontmatter on pages (`tags`, `source_count`, `last_updated`) for Dataview-style queries in Obsidian
- Live markdown editor in the preview pane (CodeMirror)

---

## Stopping the server

Just `Ctrl-C` the terminal running `mvn spring-boot:run`. Or:

```bash
pkill -f "spring-boot:run"
```
