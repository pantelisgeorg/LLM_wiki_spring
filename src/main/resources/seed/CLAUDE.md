# LLM Wiki — Schema & Workflow

This directory is a personal research wiki maintained incrementally by an LLM. It is also a normal git repo, and can be opened in Obsidian.

## Layout

```
raw/                user-curated sources (immutable to the LLM)
  articles/         markdown or plain-text articles
  pdfs/             pdf originals
  web/              URLs fetched & converted to .md
  assets/           images

wiki/               LLM-owned. All markdown.
  index.md          catalog — retrieval entry point
  log.md            append-only event log (ingest | query | lint)
  entities/         one page per proper-noun thing (person, tool, paper, …)
  concepts/         one page per abstract idea (technique, pattern, …)
  sources/          one summary page per ingested raw source
```

## Page conventions

- Cross-links use `[[wiki/relative/path.md]]`.
- Every entity / concept page ends with `## Sources` listing `[[wiki/sources/<slug>.md]]`.
- Source summary pages contain: one-line tagline, 3–8 key claims, related cross-links, original `raw/...` path.
- Prefer updating an existing page over creating a duplicate. Slug collisions are resolved by merging into the existing page.

## `index.md`

Grouped under `## Entities`, `## Concepts`, `## Sources`. Each entry is a single line:

```
- [title](wiki/relative/path.md) — one-line hook
```

This file is the retrieval index. Keep entries short and information-dense so search can be done by grep or a single LLM call.

## `log.md`

Append-only. One heading per event, parseable by `grep '^## \['`:

```
## [YYYY-MM-DD] ingest | <title>
## [YYYY-MM-DD] query  | <question>
## [YYYY-MM-DD] lint   | <N issues>
```

## Workflows

1. **ingest** — given a new source, update/create entity & concept pages, write a source summary, add an index entry, append a log line.
2. **query** — given a question, pick pages from the index, compose a cited answer.
3. **lint** — scan index + log for orphans, contradictions, gaps, stale claims.

## Ground rules

- Never fabricate. If a page's claim is not substantiated by a listed source, either cite the gap or remove the claim.
- Preserve existing content when upserting — merge, don't overwrite wholesale.
- Keep prose dense. Prefer bullets and tables over paragraphs.
