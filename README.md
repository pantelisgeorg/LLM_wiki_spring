# llm-wiki

A local Spring Boot web app that implements the **LLM Wiki** pattern: instead of RAG-at-query-time, an LLM incrementally maintains a markdown knowledge base. Ingest a source → it updates entity pages, concept pages, an index, and a log. Ask a question → it picks pages from the index and answers with citations.

Originally spec'd from Karpathy's [`llm-wiki.md`](llm_wiki.md). See that doc for the "why"; this README covers the "how".

---

## Status

Working end-to-end:
- **Ingest** — markdown / text / PDF / URL; auto-chunks long sources at `##` boundaries (image path wired but Ministral is text-only)
- **Query** — index-driven page selection + cited answer
- **Lint** — gap / orphan / contradiction / stale-claim detection over index + log
- **Graph view** — force-directed visualization of `[[wiki/…]]` cross-links, vis-network
- **Backlinks panel** — every page shows which other pages cite it
- **Fuzzy tree search** — `Ctrl/Cmd+K` filters the sidebar by substring
- **Outline / TOC** — auto-extracted `##`/`###` headings pinned to the top of each page
- **Multi-topic launcher** — `bin/llm-wiki --topic NAME` runs one isolated wiki per topic on a stable port
- **Frontend** — 3-pane UI with SSE streaming, renders markdown, click-to-navigate `[[wiki/…]]` links

Tested against: LM Studio 0.3.x serving `qwen3.5-4b` on `localhost:1234` (CPU-only, 15 GB RAM). Ollama with `gemma4:e4b` also works (swap the starter — see Configuration).

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
    ├── entities/                 ← proper-noun pages
    ├── concepts/                 ← abstract-idea pages
    └── sources/                  ← one summary per ingested source
```

Point Obsidian at `~/llm-wiki/` to browse. The app owns `wiki/`; the user owns `raw/`. `git init` inside `~/llm-wiki/` and you get version control for free.

### Code layout

```
src/main/java/com/wiki/
├── LlmWikiApplication.java       ← Spring Boot main
├── WikiController.java           ← REST + SSE endpoints
├── service/
│   ├── RawSourceLoader.java      ← md/pdf/url/image → plain text
│   ├── Chunker.java              ← splits long text on ## / paragraph boundaries
│   ├── WikiStore.java            ← filesystem IO (only layer that writes disk)
│   ├── WikiSearch.java           ← "ask LLM which pages from the index"
│   ├── WikiAgent.java            ← orchestrates ingest/query/lint prompts
│   ├── LinkGraph.java            ← parses [[wiki/…]] refs → outgoing/incoming maps
│   └── PromptLoader.java         ← loads + caches classpath prompts
└── dto/
    ├── WikiEdit.java             ← { path, action: upsert|append, body }
    ├── IngestResult.java         ← { sourceSummary, edits[], indexEntry, logLine }
    ├── QueryAnswer.java          ← { markdown, citations[] }
    ├── LintReport.java           ← { issues: [{ type, page, description }] }
    └── LoadedSource.java         ← { title, text, sourcePath }

src/main/resources/
├── application.yml
├── prompts/
│   ├── ingest.txt                ← creates/updates pages from a new source
│   ├── query.txt                 ← cited answer from selected pages
│   ├── lint.txt                  ← integrity audit over index + log
│   └── search.txt                ← index → relevant-pages selector
├── seed/CLAUDE.md                ← copied into wiki-root on first run
└── static/
    ├── index.html                ← 3-pane layout + graph overlay
    └── app.js                    ← vanilla JS + EventSource + marked.js + vis-network

bin/
└── llm-wiki                      ← multi-topic launcher (port = hash(topic))
```

---

## Quickstart

### Prereqs

- Java 21+ (tested on Java 25)
- Maven 3.8+
- An OpenAI-compatible LLM server listening on `http://localhost:1234`. Default config assumes **LM Studio** with `mistralai/ministral-3-3b` loaded. To use Ollama instead, see Configuration.

### Run

```bash
# 1. Start LM Studio, load ministral-3-3b, click "Start Server" in Developer tab
curl -sf http://localhost:1234/v1/models       # should return JSON

# 2. Start the wiki server (default: ~/llm-wiki on port 8080)
cd ~/Desktop/llm-wiki
mvn spring-boot:run

# 3. Open the UI
open http://localhost:8080
```

First run seeds `~/llm-wiki/` with the directory skeleton, an empty `index.md` / `log.md`, and a `CLAUDE.md` schema file for humans and Claude Code.

### Multi-topic (one wiki per domain)

```bash
bin/llm-wiki --topic ml          # → ~/wikis/ml       on port 8091
bin/llm-wiki --topic cooking     # → ~/wikis/cooking  on port 8099
bin/llm-wiki --topic research    # → ~/wikis/research on port 8096
```

Port is a deterministic hash of the topic name, stable across runs. Override with `--port` or `--root`. Each instance is fully isolated: its own filesystem, its own index, its own graph. Same LM Studio backend serves all of them.

---

## Using it

The UI has one shared input at the top and three buttons.

### Ingest

Paste a URL, a local file path, or click **File…** and pick a file, then **Ingest**.

**Short sources** (≤10K chars) go through in one LLM call:
```
[status] loaded: title (N chars)
[status] calling LLM (this can take 30–90s on local models)
[status] applied K edits + 1 source summary
[result] {…}
```

**Long sources** (>10K chars) auto-chunk at `##` heading boundaries (8K chars target per chunk) and stream per-chunk progress:
```
[status] loaded: thesis.md (59000 chars)
[status] chunked into 11 parts (~5363 chars each)
[status] chunk 1/11: calling LLM
[status] chunk 1/11: applied 5 edits
…
[status] done: 27 edits across 11 chunks
```

Each chunk's title is tagged `(part N/M)` so the LLM sees distinct identities. Crucially: by chunk 2 the index already shows chunk 1's pages, so the LLM **extends** existing entity/concept pages rather than recreating them — the same compounding mechanic that makes multi-source ingest work, applied intra-document.

The ingest prompt caps each chunk at **5 entity/concept edits + 1 source summary** to keep each LLM call bounded in time and output tokens. Over-producing triggers truncation (see quirks #14 and #15).

Typical output for a medium source: 1 source summary + 3–5 entity/concept pages. For a chunked long source: one source summary *per chunk* + accumulating entity pages.

#### Resuming a chunked ingest

If LM Studio freezes mid-run (see quirk #16), restart LM Studio, reload the model, re-select the same file in the UI, type the chunk number to resume at in the **"start chunk"** header field, and click **Ingest**. Because `Chunker.chunk()` is deterministic over the same input + thresholds, chunk N's split boundaries match the original run.

Equivalent curl:
```bash
curl -N -X POST http://localhost:8080/api/ingest/upload \
     -F 'file=@thesis.md' -F 'startChunk=4'
```

### Query

Type a question → **Query**. Two LLM calls happen:
1. `WikiSearch` reads `index.md` and returns up to 8 paths relevant to the question.
2. `WikiAgent.query` reads those pages and composes an answer.

Answer appears in the preview pane with inline `[[wiki/…]]` citations and a **Citations** list at the bottom. Every citation is clickable.

### Lint

**Lint** — no input needed. Reads the authoritative page list + `index.md` + `log.md` (prior lint entries stripped to avoid self-reference) and reports:
- `orphan` — page not linked anywhere, or index entry pointing at a missing file
- `gap` — cross-link implies a page that doesn't exist
- `contradiction` — two pages disagree
- `stale` — claim older than its source would warrant

Lint currently does **not** read page bodies — that kept the prompt under LM Studio's timeout. Trade-off: body-level issues (bad inline `[[…]]` links, misclassified pages) aren't caught yet.

Each issue whose `page` field is a valid `wiki/…/*.md` path gets a **Delete** button in the preview. Click it to remove the file *and* any line in `wiki/index.md` that references the path. Reserved pages (`wiki/index.md`, `wiki/log.md`) are never deletable.

### Graph, backlinks, search, outline

- **Graph** — top-right *Graph* button opens a full-screen force-directed graph. Nodes colored by type (entity=blue, concept=purple, source=green), sized by link degree. Click any node to jump to its page. Esc closes.
- **Backlinks** — bottom of every page preview lists "Cited by N" with clickable links. Derived from `LinkGraph.backlinks()` which regex-scans all pages for `[[wiki/path.md]]` references.
- **Fuzzy search** — input above the tree sidebar, filters pages by substring as you type. `Ctrl/Cmd+K` focuses it.
- **Outline** — a pinned strip at the top of each page preview, auto-built from `##`/`###` headings. Click to scroll.
- **Clear** — header button that resets the right preview pane to the placeholder and strips the page hash from the URL.

### curl equivalents

```bash
curl -N -X POST http://localhost:8080/api/ingest \
     -H 'Content-Type: application/json' \
     -d '{"path":"/abs/path/to/file.md"}'

curl -N -X POST http://localhost:8080/api/query \
     -H 'Content-Type: application/json' \
     -d '{"question":"your question"}'

curl -N -X POST http://localhost:8080/api/lint

curl -s http://localhost:8080/api/wiki/tree
curl -s "http://localhost:8080/api/wiki/page?path=wiki/entities/obsidian.md"
curl -s "http://localhost:8080/api/wiki/backlinks?path=wiki/entities/obsidian.md"
curl -s http://localhost:8080/api/wiki/graph | python3 -m json.tool | head
curl -s -X DELETE "http://localhost:8080/api/wiki/page?path=wiki/entities/orphan.md"
```

All action endpoints are SSE; use `curl -N` to avoid buffering.

---

## Configuration

`src/main/resources/application.yml`:

```yaml
wiki:
  root: ${user.home}/llm-wiki
  search:
    max-pages: 8
  ingest:
    chunk-gap-ms: 3000              # sleep between chunks; lets LM Studio settle
    reset-every-n-chunks: 0         # >0 shells out reset-command every N chunks (see below)
    reset-command: "lms unload --all && lms load qwen3.5-4b --context-length 12288 --parallel 1 --gpu off -y"

spring:
  ai:
    retry:
      max-attempts: 1              # local servers can freeze under retry storms
    openai:
      base-url: http://localhost:1234
      api-key: lm-studio            # LM Studio ignores it, Spring AI requires non-null
      chat:
        options:
          model: qwen3.5-4b
          temperature: 0.2
          max-tokens: 7000            # must fit in (LM Studio context − prompt tokens)
  mvc:
    async:
      request-timeout: -1           # local CPU inference can take minutes
  servlet:
    multipart:
      max-file-size: 50MB
```

### Ingest resilience knobs

- `wiki.ingest.chunk-gap-ms` — sleep between chunked-ingest calls. Small gap (a few seconds) gives LM Studio a chance to release its slot between calls; set to `0` to disable.
- `wiki.ingest.reset-every-n-chunks` — if >0, `WikiController` shells out `reset-command` after every Nth completed chunk. In practice this only helps when the LM Studio daemon is still *responsive* — if the daemon has already wedged, `lms unload` hangs too (see quirk #16). Default `0` (disabled); useful only as an experiment.
- `wiki.ingest.reset-command` — whatever shell command should put a healthy LM Studio back in a known state. Defaults to an `lms unload && lms load …` line; edit the model key / context / parallel flag to match your setup. Runs via `bash -lc` so login PATH resolves `lms`.

### Swap to Ollama

1. In `pom.xml` change `spring-ai-openai-spring-boot-starter` → `spring-ai-ollama-spring-boot-starter`
2. In `application.yml`:
   ```yaml
   spring:
     ai:
       ollama:
         base-url: http://localhost:11434
         chat:
           options:
             model: gemma4:e4b        # or qwen3.5:4b for faster text-only
             temperature: 0.2
   ```

---

## Quirks worked around (so the code makes sense)

1. **Reserved paths.** `wiki/index.md` and `wiki/log.md` are refused as edit targets in `WikiStore.applyEdit` — the LLM overwrote them once and wiped state. Use `indexEntry` / `logLine` fields instead.
2. **Per-path edit dedupe.** LLM sometimes emits both `upsert` and `append` for the same file in one ingest. `WikiStore.applyEdits` keeps only the last edit per path (LinkedHashMap). Prevents garbled mixed content.
3. **Action aliases.** LLM might say `action: "create"` or `"write"` — `WikiStore.applyEdit` maps these to `upsert`. Unknown actions log a warning and default to upsert rather than throwing.
4. **Ghost index filter.** `WikiAgent.applyIngest` collects the set of actually-written paths and drops any `indexEntry` line pointing at a path not in that set (and not in the existing index).
5. **Auto-injected source index line.** LLMs forget to add the source itself to the index. `WikiAgent.ensureSourceIndexed` extracts the first `# heading` and first non-bullet paragraph from the source body and appends a `[Title](path) — hook` line.
6. **Section-routed index append.** `WikiStore.appendIndexEntry` parses the `wiki/…` path in the entry line and inserts under `## Entities`, `## Concepts`, or `## Sources` accordingly.
7. **Path-aware index dedupe.** Appending an entry whose path already appears (regardless of title/hook) is a no-op.
8. **Lenient JSON.** Local models emit JSON with unescaped newlines, trailing commas, single quotes, `//` comments, and occasionally extra fields the DTO doesn't know about (e.g. a bogus `"source"` on `WikiEdit`). `WikiAgent.LENIENT` is a `JsonMapper` with the corresponding `JsonReadFeature` flags enabled *and* `FAIL_ON_UNKNOWN_PROPERTIES` disabled, passed to every `BeanOutputConverter`.
9. **Lint self-reference.** Lint reads `log.md`. Past `lint | N issues` entries used to spawn fake "stale" findings. `WikiAgent.lint` filters those out of the log before prompting.
10. **SSE error shape.** Errors are emitted as a JSON string, not a `Map`, because SSE's `text/event-stream` converter can't serialise maps. Emitter also ends via `complete()` instead of `completeWithError()` to avoid Spring's default error-page handler clobbering the already-committed stream.
11. **Chunked ingest for long sources.** `Chunker.shouldChunk` trips at 10K chars; `DEFAULT_TARGET` is 8K. The controller then loops `agent.ingest(chunk, canonicalSourcePath)` sequentially. Chunks run *in order* so chunk N's prompt sees the accumulated index from chunks 1..N-1 — the LLM extends existing pages instead of duplicating. Chunk titles are tagged `(part N/M)` to keep source-summary slugs distinct. `WikiController.slugifyTitle` derives a kebab-case base slug from the original source title (stripping any `(part N/M)` marker) and the controller builds a canonical `wiki/sources/{base}-part{N}.md` for each chunk. `WikiAgent.normalizeSourcePath` then rewrites the LLM's chosen summary path + every `[[wiki/sources/<old>]]` reference in edit bodies to that canonical path *before* anything hits disk — so local-model slug drift (seen historically as `knowledge-management.md`, `knowledge-management-part4.md`, and `knowledge-management-part1-11.md` for the same source across chunks) can't produce broken cross-links. These thresholds were picked so each chunk's input stays under ~3K tokens, leaving ~7K for output on a 12288-token LM Studio context.
12. **Retry cap = 1.** Spring AI retries by default (up to 10×) on transient errors. Against a local model that can freeze under load, that turns every slow response into a multi-minute retry storm. `spring.ai.retry.max-attempts: 1` makes us fail fast.
13. **Lenient input caps.** `WikiAgent` truncates the source text at 40K chars and query-context pages at 12K chars each. The chunker already caps each chunk well below 40K, so this is a belt-and-suspenders safety net. Change these if you switch to a larger-context model.
14. **Hard edit cap in the ingest prompt.** Without it, a dense chunk makes the model emit 15+ edits and the response overflows `max-tokens` mid-string (`Unexpected end-of-input: expected closing quote`). `prompts/ingest.txt` caps at "at most 5 entity/concept edits per response, total output under 5000 tokens, each body ≤1500 chars." Gives up a little breadth per chunk in exchange for reliable parsing; the next chunk's index sees the earlier pages and tends to fill gaps.
15. **Truncated-JSON salvage.** When the LLM's output is still cut off despite the cap, `WikiAgent.salvageIngestResult` runs a bracket/string-aware scanner over the partial JSON and keeps every complete `{…}` inside `edits`, plus `sourceSummary` / `indexEntry` / `logLine` if they parse. A partially-successful chunk contributes its N valid edits instead of aborting the whole ingest.
16. **LM Studio scheduler stalls after ~3 back-to-back long generations.** Observed on 2026-04-19 with qwen3.5-4b: after 3–4 chunks of ~6K-token generations, a new POST is received (logged as `Received request: POST to /v1/chat/completions`) but is never dispatched to a slot — no `slot update_slots`, no prompt-processing progress, no error. UI freezes, `lms ps` hangs because its RPC blocks on the wedged daemon. `--parallel 1` doesn't prevent it. Workaround: kill LM Studio, relaunch, reload the model with `lms load qwen3.5-4b --context-length 12288 --parallel 1 --gpu off -y`, then resume via the **"start chunk"** field in the header (or `startChunk` form param). `WikiController.resumeIngest` uses the chunker's deterministic split so chunk numbers match between runs.

---

## Debugging

```bash
# server log (append `-f` to tail live)
tail -80 /tmp/llm-wiki-run.log

# quick model connectivity check
curl -sf http://localhost:1234/v1/models | head -c 500

# watch what the model returned (look for "LLM returned N chars in M ms")
grep -E "Ingest prompt|LLM returned|Applied|ghost" /tmp/llm-wiki-run.log

# current wiki state
ls ~/llm-wiki/wiki/{entities,concepts,sources}
cat ~/llm-wiki/wiki/index.md
cat ~/llm-wiki/wiki/log.md
```

**Common failures seen:**

| Symptom | Cause | Fix |
|---|---|---|
| `Connection refused` on `localhost:1234` | LM Studio server not started | Developer tab → **Start Server** |
| `408 -` in stack trace | LM Studio's server-side timeout on long generation or a wedged daemon (see quirk #16) | Kill + relaunch LM Studio, reload with `lms load qwen3.5-4b --context-length 12288 --parallel 1 --gpu off -y`, resume via "start chunk" field |
| `AsyncRequestTimeoutException` | Spring MVC killing the request before LLM finishes | Already handled via `spring.mvc.async.request-timeout: -1` |
| `JsonMappingException: Illegal unquoted character` | Model output has raw `\n` in a string value | Covered by `LENIENT.ALLOW_UNESCAPED_CONTROL_CHARS` |
| `JsonMappingException: Unexpected character ('/')` | Model put a `//` comment in JSON | Covered by `LENIENT.ALLOW_JAVA_COMMENTS` |
| `Unknown action: create` | LLM chose a synonym for `upsert` | Covered by alias map in `WikiStore.applyEdit` |
| `Unexpected end-of-input: expecting closing quote` | Model hit `max-tokens` mid-generation | Now auto-salvaged (quirk #15); if too many edits are lost, lower `Chunker.DEFAULT_TARGET` or tighten the edit cap in `prompts/ingest.txt` |
| `Unrecognized field "source"` | LLM added a field `WikiEdit` doesn't declare | Covered by `FAIL_ON_UNKNOWN_PROPERTIES=false` (quirk #8) |
| LM Studio UI frozen, new requests ignored, no error logged | Scheduler stall after a few long generations (quirk #16) | Kill LM Studio, relaunch, reload model, resume via "start chunk" |

---

## Stretch (not yet done)

- Call `git commit -m "ingest: <title>"` inside the wiki root after each ingest
- Vision-capable model for image ingest (Ministral is text-only — swap to `gemma4:e4b`)
- SQLite FTS5 or a vector store when `index.md` exceeds a few hundred entries
- Re-enable page-body input to lint for body-level checks (requires a model that can stomach a ~40KB prompt)
- Aggregate source summary for chunked ingests (one `sources/<slug>.md` instead of one per chunk)
- Single-instance wiki switcher (currently multi-topic requires multiple processes)
- Marp slide-export endpoint
- Expose ingest/query/lint as MCP tools so Claude Code / Codex can drive the same wiki
- Frontmatter on pages (`tags`, `source_count`, `last_updated`) for Dataview-style queries in Obsidian
- Live markdown editor in the preview pane (CodeMirror)

---

## Stopping the server

```bash
pkill -f "spring-boot:run"
```

Or `Ctrl-C` in the terminal running `mvn spring-boot:run`. Wiki state on disk is untouched either way.
