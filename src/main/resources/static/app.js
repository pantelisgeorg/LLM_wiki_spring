const $ = id => document.getElementById(id);
const out = $("output");
const preview = $("preview");
const input = $("input");
const fileInput = $("file");
const treeSearch = $("tree-search");

let allPages = [];
let currentPage = null;

async function refreshTree() {
  const body = $("tree-body");
  try {
    const res = await fetch("/api/wiki/tree");
    allPages = await res.json();
    renderTree(treeSearch.value);
  } catch (e) {
    body.innerHTML = `<span class="error">tree failed: ${e}</span>`;
  }
}

function renderTree(filter) {
  const body = $("tree-body");
  body.innerHTML = "";
  const q = (filter || "").toLowerCase().trim();
  const groups = {"entities": [], "concepts": [], "sources": [], "other": []};
  for (const p of allPages) {
    if (p === "wiki/index.md" || p === "wiki/log.md") continue;
    if (q && !p.toLowerCase().includes(q)) continue;
    if (p.startsWith("wiki/entities/")) groups.entities.push(p);
    else if (p.startsWith("wiki/concepts/")) groups.concepts.push(p);
    else if (p.startsWith("wiki/sources/")) groups.sources.push(p);
    else groups.other.push(p);
  }
  for (const [section, paths] of Object.entries(groups)) {
    if (!paths.length) continue;
    const h = document.createElement("div");
    h.className = "section";
    h.textContent = section;
    body.appendChild(h);
    const ul = document.createElement("ul");
    for (const p of paths) {
      const li = document.createElement("li");
      const file = p.split("/").pop().replace(/\.md$/, "");
      li.textContent = file;
      li.title = p;
      if (p === currentPage) li.classList.add("active");
      li.onclick = () => openPage(p);
      ul.appendChild(li);
    }
    body.appendChild(ul);
  }
  // Static meta pages link
  const meta = document.createElement("ul");
  for (const p of ["wiki/index.md", "wiki/log.md"]) {
    if (q && !p.includes(q)) continue;
    const li = document.createElement("li");
    li.textContent = p.split("/").pop();
    li.onclick = () => openPage(p);
    if (p === currentPage) li.classList.add("active");
    meta.appendChild(li);
  }
  if (meta.children.length) {
    const h = document.createElement("div");
    h.className = "section"; h.textContent = "meta";
    body.appendChild(h); body.appendChild(meta);
  }
}

treeSearch.oninput = () => renderTree(treeSearch.value);

// Ctrl/Cmd+K focuses the tree search
document.addEventListener("keydown", e => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
    e.preventDefault();
    treeSearch.focus(); treeSearch.select();
  }
  if (e.key === "Escape") closeGraph();
});

async function openPage(path, opts = {}) {
  currentPage = path;
  if (!opts.fromHash) {
    const target = "#" + path;
    if (location.hash !== target) history.pushState({path}, "", target);
  }
  document.querySelectorAll("#tree li.active").forEach(n => n.classList.remove("active"));
  try {
    const res = await fetch(`/api/wiki/page?path=${encodeURIComponent(path)}`);
    if (!res.ok) { preview.innerHTML = `<span class="error">missing: ${path}</span>`; return; }
    const json = await res.json();
    renderPage(path, json.markdown);
    // mark active in sidebar
    document.querySelectorAll("#tree li").forEach(li => {
      if (li.title === path) li.classList.add("active");
    });
    // fetch backlinks async
    fetchBacklinks(path);
  } catch (e) {
    preview.innerHTML = `<span class="error">${e}</span>`;
  }
}

window.addEventListener("popstate", () => {
  const path = decodeURIComponent(location.hash.replace(/^#/, ""));
  if (path) openPage(path, {fromHash: true});
});

function renderPage(path, md) {
  const toc = buildToc(md);
  preview.innerHTML = `<div class="status">${path}</div>${toc}${marked.parse(preprocessWikilinks(md))}<div id="backlinks-slot"></div>`;
  wireWikiLinks(preview);
}

/** Convert Obsidian-style `[[wiki/foo.md]]` into standard `[foo](wiki/foo.md)` so marked renders anchors. */
function preprocessWikilinks(md) {
  return md.replace(/\[\[(wiki\/[\w\-./]+\.md)\]\]/g, (_, p) => {
    const label = p.split('/').pop().replace(/\.md$/, '');
    return `[${label}](${p})`;
  });
}

/** Rewire any `<a href="wiki/…">` in the given container to call openPage() instead of navigating. */
function wireWikiLinks(container) {
  container.querySelectorAll("a[href]").forEach(a => {
    const href = a.getAttribute("href");
    if (href && href.startsWith("wiki/")) {
      a.onclick = e => { e.preventDefault(); openPage(href); };
    }
  });
}

function buildToc(md) {
  const headings = [];
  for (const line of md.split(/\r?\n/)) {
    const m = line.match(/^(#{2,3})\s+(.+?)\s*$/);
    if (m) headings.push({level: m[1].length, text: m[2]});
  }
  if (headings.length < 2) return "";
  let html = `<div class="toc"><div class="toc-title">On this page</div>`;
  for (const h of headings) {
    const id = "toc-" + h.text.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
    html += `<a href="#" data-toc="${id}" class="h${h.level}">${h.text}</a>`;
  }
  html += `</div>`;
  // Post-process marked.js output to add matching ids to h2/h3 and wire click handlers.
  setTimeout(() => {
    preview.querySelectorAll("h2, h3").forEach(h => {
      const id = "toc-" + h.textContent.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
      h.id = id;
    });
    preview.querySelectorAll("a[data-toc]").forEach(a => {
      a.onclick = e => {
        e.preventDefault();
        const el = preview.querySelector("#" + CSS.escape(a.dataset.toc));
        if (el) el.scrollIntoView({behavior: "smooth", block: "start"});
      };
    });
  }, 0);
  return html;
}

async function fetchBacklinks(path) {
  const slot = $("backlinks-slot");
  if (!slot) return;
  try {
    const res = await fetch(`/api/wiki/backlinks?path=${encodeURIComponent(path)}`);
    const links = await res.json();
    if (!links.length) {
      slot.innerHTML = `<div class="backlinks empty">No backlinks — this page is not cited anywhere yet.</div>`;
      return;
    }
    let html = `<div class="backlinks"><div class="backlinks-title">Cited by ${links.length}</div>`;
    for (const l of links) {
      const label = l.replace(/^wiki\//, "").replace(/\.md$/, "");
      html += `<a href="#" data-path="${l}">${label}</a>`;
    }
    html += `</div>`;
    slot.innerHTML = html;
    slot.querySelectorAll("a[data-path]").forEach(a => {
      a.onclick = e => { e.preventDefault(); openPage(a.dataset.path); };
    });
  } catch (e) {
    slot.innerHTML = `<div class="backlinks empty">backlinks failed: ${e}</div>`;
  }
}

function renderLintReport(issues) {
  preview.innerHTML = "";
  const h = document.createElement("h1"); h.textContent = "Lint report"; preview.appendChild(h);
  if (!issues.length) {
    const p = document.createElement("p"); p.innerHTML = "<em>No issues found.</em>";
    preview.appendChild(p); return;
  }
  const ul = document.createElement("ul");
  ul.style.listStyle = "none"; ul.style.paddingLeft = "0";
  for (const i of issues) {
    const li = document.createElement("li");
    li.style.padding = "8px 0"; li.style.borderBottom = "1px solid var(--border)";
    const typeSpan = document.createElement("strong"); typeSpan.textContent = i.type;
    li.appendChild(typeSpan); li.appendChild(document.createTextNode(" · "));
    if (i.page && i.page.startsWith("wiki/") && i.page.endsWith(".md")) {
      const a = document.createElement("a"); a.href = "#"; a.textContent = i.page;
      a.onclick = e => { e.preventDefault(); openPage(i.page); };
      li.appendChild(a);
      const deletable = i.page !== "wiki/index.md" && i.page !== "wiki/log.md";
      if (deletable) {
        const btn = document.createElement("button");
        btn.textContent = "Delete";
        btn.title = "Remove this page and any index entry pointing at it";
        btn.style.marginLeft = "8px"; btn.style.padding = "2px 8px";
        btn.style.background = "var(--panel)"; btn.style.color = "var(--err)";
        btn.style.border = "1px solid var(--border)"; btn.style.borderRadius = "3px";
        btn.style.cursor = "pointer"; btn.style.fontSize = "12px";
        btn.onclick = () => deleteLintPage(i.page, li);
        li.appendChild(btn);
      }
    } else {
      const code = document.createElement("code"); code.textContent = i.page || "-";
      li.appendChild(code);
    }
    li.appendChild(document.createElement("br"));
    const desc = document.createElement("span"); desc.className = "status"; desc.textContent = i.description;
    li.appendChild(desc);
    ul.appendChild(li);
  }
  preview.appendChild(ul);
}

async function deleteLintPage(path, liEl) {
  if (!confirm(`Delete ${path}?\nThis removes the file and prunes any matching index entry.`)) return;
  try {
    const res = await fetch(`/api/wiki/page?path=${encodeURIComponent(path)}`, {method: "DELETE"});
    const body = await res.json();
    if (!res.ok) { alert("Delete failed: " + (body.error || res.status)); return; }
    liEl.style.opacity = "0.5";
    liEl.querySelector("button").disabled = true;
    const note = document.createElement("span");
    note.className = "status";
    note.textContent = `  ✓ removed (file: ${body.fileRemoved}, index lines: ${body.indexLinesRemoved})`;
    liEl.appendChild(note);
    refreshTree();
  } catch (e) {
    alert("Delete failed: " + e);
  }
}

/* ---------- SSE actions ---------- */

function appendEvent(tag, text, cls = "") {
  const div = document.createElement("div");
  div.className = "event" + (cls ? " " + cls : "");
  div.innerHTML = `<span class="tag">[${tag}]</span>${text}`;
  out.appendChild(div);
  out.scrollTop = out.scrollHeight;
}

function runSse(url, body) {
  if (inflight) return;
  out.innerHTML = "";
  appendEvent("start", url);
  lockActions();
  fetch(url, {
    method: "POST",
    headers: {"Content-Type": "application/json", "Accept": "text/event-stream"},
    body: body ? JSON.stringify(body) : null
  }).then(readStream).catch(e => appendEvent("error", e, "error"))
    .finally(unlockActions);
}

function runSseUpload(file, startChunk) {
  if (inflight) return;
  out.innerHTML = "";
  appendEvent("start", "uploading " + file.name + (startChunk > 1 ? ` (from chunk ${startChunk})` : ""));
  const fd = new FormData();
  fd.append("file", file);
  if (startChunk && startChunk > 1) fd.append("startChunk", String(startChunk));
  lockActions();
  fetch("/api/ingest/upload", {method: "POST", body: fd, headers: {"Accept": "text/event-stream"}})
    .then(readStream).catch(e => appendEvent("error", e, "error"))
    .finally(unlockActions);
}

let inflight = false;
function lockActions() {
  if (inflight) return;
  inflight = true;
  for (const id of ["btn-ingest", "btn-query", "btn-qmd", "btn-fetch", "btn-lint", "btn-consolidate"]) {
    const b = $(id); if (b) b.disabled = true;
  }
}
function unlockActions() {
  inflight = false;
  for (const id of ["btn-ingest", "btn-query", "btn-qmd", "btn-fetch", "btn-lint", "btn-consolidate"]) {
    const b = $(id); if (b) b.disabled = false;
  }
}

async function readStream(res) {
  if (!res.ok) { appendEvent("error", "HTTP " + res.status, "error"); return; }
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = "";
  while (true) {
    const {done, value} = await reader.read();
    if (done) break;
    buf += dec.decode(value, {stream: true});
    let idx;
    while ((idx = buf.indexOf("\n\n")) >= 0) {
      const chunk = buf.slice(0, idx); buf = buf.slice(idx + 2);
      handleEvent(chunk);
    }
  }
  refreshTree();
}

function handleEvent(chunk) {
  let event = "message", data = "";
  for (const line of chunk.split("\n")) {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) data += line.slice(5).trim();
  }
  if (!data) return;
  if (event === "status") {
    try { appendEvent("status", JSON.parse(data), "status"); }
    catch { appendEvent("status", data, "status"); }
  } else if (event === "error") {
    try { appendEvent("error", JSON.parse(data).message || data, "error"); }
    catch { appendEvent("error", data, "error"); }
  } else if (event === "result") {
    try { const parsed = JSON.parse(data); appendEvent("result", ""); renderResult(parsed); }
    catch (e) { appendEvent("result", data); }
  } else {
    appendEvent(event, data);
  }
}

function renderResult(r) {
  if (r.markdown && r.citations) {
    preview.innerHTML = marked.parse(preprocessWikilinks(r.markdown));
    if (r.citations.length) {
      const cites = document.createElement("div");
      cites.innerHTML = "<h3>Citations</h3>";
      const ul = document.createElement("ul");
      r.citations.forEach(c => {
        const li = document.createElement("li");
        const a = document.createElement("a"); a.href = "#"; a.textContent = c;
        a.onclick = e => { e.preventDefault(); openPage(c); };
        li.appendChild(a); ul.appendChild(li);
      });
      cites.appendChild(ul); preview.appendChild(cites);
    }
    wireWikiLinks(preview);
    return;
  }
  if (r.issues) {
    renderLintReport(r.issues);
    return;
  }
  if (r.perPage) {
    renderConsolidateReport(r);
    return;
  }
  if (r.edits) {
    let md = "# Ingest result\n\n";
    const srcPath = r.sourceSummary?.path;
    md += `**Source summary:** ${srcPath ? `[${srcPath}](${srcPath})` : "—"}\n\n`;
    md += "## Edits\n\n";
    for (const e of r.edits) md += `- [${e.path}](${e.path}) (${e.action})\n`;
    md += "\n## Index entry\n\n```\n" + (r.indexEntry || "") + "\n```\n";
    preview.innerHTML = marked.parse(md);
    wireWikiLinks(preview);
    return;
  }
  const pre = document.createElement("pre");
  pre.textContent = JSON.stringify(r, null, 2);
  preview.innerHTML = ""; preview.appendChild(pre);
}

$("btn-ingest").onclick = () => {
  const v = input.value.trim();
  const startChunk = parseInt($("start-chunk").value, 10) || 1;
  if (fileInput.files[0]) { runSseUpload(fileInput.files[0], startChunk); fileInput.value = ""; return; }
  if (!v) { appendEvent("error", "paste a URL or local path first, or pick a file", "error"); return; }
  const body = v.startsWith("http") ? {url: v} : {path: v};
  if (startChunk > 1) body.startChunk = startChunk;
  runSse("/api/ingest", body);
};
$("btn-query").onclick = () => {
  const v = input.value.trim();
  if (!v) { appendEvent("error", "type a question", "error"); return; }
  runSse("/api/query", {question: v});
};
$("btn-lint").onclick = () => runSse("/api/lint", null);
$("btn-consolidate").onclick = () => {
  if (inflight) return;
  const proceed = confirm("Run consolidation preview? This scans every concept page and calls the LLM per eligible page — can take several minutes.");
  if (!proceed) return;
  runSse("/api/consolidate", {apply: false});
};

function renderConsolidateReport(r) {
  const edits = r.proposedEdits || [];
  const n = edits.length;
  const applied = !!r.applied;
  let md = `# Consolidate ${applied ? "applied" : "preview"}\n\n`;
  md += `**${n} edits** across **${r.pagesProcessed}** pages · ${r.pagesSkipped} skipped (no eligible neighbors).\n\n`;
  md += "## By page\n\n";
  for (const pp of (r.perPage || [])) {
    if (!pp.edits || pp.edits.length === 0) continue;
    md += `### [${pp.path}](${pp.path})\n\n`;
    for (const e of pp.edits) md += `- ${e.body}\n`;
    md += "\n";
  }
  preview.innerHTML = marked.parse(md);
  if (!applied && n > 0) {
    const bar = document.createElement("div");
    bar.style.cssText = "position:sticky;top:0;background:var(--panel);padding:8px;border-bottom:1px solid var(--border);margin:0 0 12px 0;z-index:1;";
    const btn = document.createElement("button");
    btn.textContent = `Apply all ${n} edits to disk`;
    btn.style.cssText = "padding:6px 14px;background:var(--panel);color:var(--fg);border:1px solid var(--border);border-radius:4px;cursor:pointer;";
    btn.onmouseenter = () => btn.style.borderColor = "var(--accent)";
    btn.onmouseleave = () => btn.style.borderColor = "var(--border)";
    btn.onclick = () => {
      if (inflight) return;
      if (!confirm(`Regenerate and write ~${n} cross-link edits to disk? Count may differ slightly due to LLM non-determinism.`)) return;
      runSse("/api/consolidate", {apply: true});
    };
    bar.appendChild(btn);
    preview.insertBefore(bar, preview.firstChild);
  }
  wireWikiLinks(preview);
}
$("btn-qmd").onclick = runQmd;
$("btn-fetch").onclick = runFetch;

async function runQmd() {
  const q = input.value.trim();
  if (!q) { appendEvent("error", "type a search query", "error"); return; }
  if (inflight) return;
  out.innerHTML = "";
  appendEvent("start", "/api/qmd/query");
  lockActions();
  try {
    const res = await fetch("/api/qmd/query", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({query: q})
    });
    const body = await res.json();
    if (!res.ok) { appendEvent("error", body.error || ("HTTP " + res.status), "error"); return; }
    const results = (body && body.results) || [];
    appendEvent("result", results.length + " hits");
    renderQmdResults(q, results);
  } catch (e) {
    appendEvent("error", String(e), "error");
  } finally {
    unlockActions();
  }
}

function renderQmdResults(q, results) {
  preview.innerHTML = "";
  const h = document.createElement("h1");
  h.textContent = `QMD: ${q}`;
  preview.appendChild(h);
  if (!results.length) {
    const p = document.createElement("p");
    p.innerHTML = "<em class=\"status\">No hits.</em>";
    preview.appendChild(p);
    return;
  }
  const ul = document.createElement("ul");
  ul.className = "qmd-results";
  for (const r of results) {
    const li = document.createElement("li");
    const score = document.createElement("span");
    score.className = "score";
    score.textContent = Math.round((r.score || 0) * 100) + "%";
    const a = document.createElement("a");
    a.href = "#";
    a.textContent = r.title || r.file;
    a.onclick = e => { e.preventDefault(); openPage(r.file); };
    const pathSpan = document.createElement("span");
    pathSpan.className = "path";
    pathSpan.textContent = r.file;
    li.appendChild(score);
    li.appendChild(a);
    li.appendChild(pathSpan);
    if (r.snippet) {
      const s = document.createElement("div");
      s.className = "snippet";
      s.textContent = r.snippet;
      li.appendChild(s);
    }
    ul.appendChild(li);
  }
  preview.appendChild(ul);
}
$("btn-clear").onclick = () => {
  preview.innerHTML = `<em class="status">click a page or citation</em>`;
  currentPage = null;
  document.querySelectorAll("#tree li.active").forEach(n => n.classList.remove("active"));
  if (location.hash) history.pushState({}, "", location.pathname);
};

$("btn-reset").onclick = async () => {
  if (!confirm("Delete ALL wiki/ pages, the index, and the log?\n\nraw/ sources are kept. This cannot be undone.")) return;
  if (!confirm("Really wipe the wiki? Type-OK confirm — last chance.")) return;
  try {
    const res = await fetch("/api/wiki/reset?confirm=yes", {method: "POST"});
    const body = await res.json().catch(() => ({}));
    if (!res.ok) { appendEvent("error", "Reset failed: " + (body.error || res.status), "error"); return; }
    appendEvent("status", `wiki reset — ${body.filesDeleted} files deleted • qmd reembed: ${body.qmdReembed}`, "status");
    preview.innerHTML = `<em class="status">click a page or citation</em>`;
    currentPage = null;
    if (location.hash) history.pushState({}, "", location.pathname);
    refreshTree();
  } catch (e) {
    appendEvent("error", "Reset failed: " + e, "error");
  }
};

fileInput.onchange = () => {
  if (fileInput.files[0]) appendEvent("status", "ready: " + fileInput.files[0].name, "status");
};

/* ---------- Graph view ---------- */

let graphNetwork = null;

$("btn-graph").onclick = showGraph;
$("btn-graph-close").onclick = closeGraph;

async function showGraph() {
  const overlay = $("graph-overlay");
  overlay.classList.add("open");
  try {
    const res = await fetch("/api/wiki/graph");
    const data = await res.json();
    const nodes = data.nodes.map(n => ({
      id: n.id, label: n.label, group: n.group,
      value: Math.max(1, n.degree),
      title: `${n.id}\ndegree: ${n.degree}`
    }));
    const edges = data.edges.map(e => ({from: e.from, to: e.to, arrows: "to"}));
    const container = $("graph-canvas");
    if (graphNetwork) graphNetwork.destroy();
    graphNetwork = new vis.Network(container, {nodes, edges}, {
      nodes: {
        shape: "dot", scaling: {min: 8, max: 32},
        font: {color: "#d7dae0", size: 12, face: "sans-serif"}
      },
      edges: {color: {color: "#2a2f3a", highlight: "#7aa2f7"}, smooth: {type: "continuous"}, width: 1},
      groups: {
        entity:  {color: {background: "#7aa2f7", border: "#4a74d4"}},
        concept: {color: {background: "#bb9af7", border: "#8a6fd4"}},
        source:  {color: {background: "#9ece6a", border: "#6ea044"}},
        other:   {color: {background: "#8a93a6", border: "#5a6478"}}
      },
      physics: {stabilization: {iterations: 150}, barnesHut: {springLength: 120}},
      interaction: {hover: true, tooltipDelay: 200}
    });
    graphNetwork.on("click", params => {
      if (params.nodes.length) {
        closeGraph();
        openPage(params.nodes[0]);
      }
    });
  } catch (e) {
    $("graph-canvas").innerHTML = `<div class="error" style="padding:16px">graph failed: ${e}</div>`;
  }
}

function closeGraph() {
  $("graph-overlay").classList.remove("open");
}

refreshTree().then(() => {
  const initial = decodeURIComponent(location.hash.replace(/^#/, ""));
  if (initial) openPage(initial, {fromHash: true});
});

refreshQmdStatus();

async function runFetch() {
  const v = input.value.trim();
  if (!v) { appendEvent("error", "enter a path, docid, or glob", "error"); return; }
  if (inflight) return;
  const isGlob = v.includes("*") || v.includes("?");
  out.innerHTML = "";
  appendEvent("start", isGlob ? `/api/qmd/multi-get ${v}` : `/api/qmd/get ${v}`);
  lockActions();
  try {
    if (isGlob) {
      const res = await fetch("/api/qmd/multi-get", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({pattern: v})
      });
      const body = await res.json();
      if (!res.ok) { appendEvent("error", body.error || ("HTTP " + res.status), "error"); return; }
      const items = Array.isArray(body) ? body : [];
      appendEvent("result", items.length + " docs");
      renderMultiGet(v, items);
    } else {
      const res = await fetch(`/api/qmd/get?file=${encodeURIComponent(v)}`);
      const body = await res.json();
      if (!res.ok) { appendEvent("error", body.error || ("HTTP " + res.status), "error"); return; }
      appendEvent("result", body.uri || v);
      renderSingleDoc(body);
    }
  } catch (e) {
    appendEvent("error", String(e), "error");
  } finally {
    unlockActions();
  }
}

/** Strip the `qmd://` scheme from a resource URI to get a wiki-relative path. */
function qmdUriToPath(uri) {
  if (!uri) return "";
  return uri.startsWith("qmd://") ? uri.slice("qmd://".length) : uri;
}

function renderSingleDoc(doc) {
  const path = qmdUriToPath(doc.uri);
  const header = `<div class="status">qmd: ${path}</div>`;
  const body = doc.text ? marked.parse(preprocessWikilinks(doc.text)) : "<em class=\"status\">empty</em>";
  preview.innerHTML = header + body;
  wireWikiLinks(preview);
}

function renderMultiGet(pattern, items) {
  preview.innerHTML = "";
  const h = document.createElement("h1");
  h.textContent = `QMD glob: ${pattern}`;
  preview.appendChild(h);
  if (!items.length) {
    const p = document.createElement("p");
    p.innerHTML = "<em class=\"status\">No matches.</em>";
    preview.appendChild(p);
    return;
  }
  const ul = document.createElement("ul");
  ul.className = "qmd-results";
  for (const it of items) {
    const path = qmdUriToPath(it.uri);
    const title = extractTitle(it.text) || path;
    const li = document.createElement("li");
    const a = document.createElement("a");
    a.href = "#";
    a.textContent = title;
    a.onclick = e => { e.preventDefault(); openPage(path); };
    const pathSpan = document.createElement("span");
    pathSpan.className = "path";
    pathSpan.textContent = path;
    li.appendChild(a);
    li.appendChild(pathSpan);
    ul.appendChild(li);
  }
  preview.appendChild(ul);
}

function extractTitle(md) {
  if (!md) return null;
  const m = md.match(/^#\s+(.+?)\s*$/m);
  return m ? m[1] : null;
}

async function refreshQmdStatus() {
  const el = $("qmd-status");
  if (!el) return;
  try {
    const res = await fetch("/api/qmd/status");
    if (!res.ok) throw new Error("HTTP " + res.status);
    const s = await res.json();
    const n = s.totalDocuments ?? "?";
    el.innerHTML = "";
    el.textContent = `qmd daemon: up • ${n} docs indexed`;
    el.style.color = "var(--source)";
    const cols = (s.collections || []).map(c => `${c.name} (${c.documents} docs @ ${c.path})`).join("\n");
    el.title = `qmd MCP HTTP daemon reachable\nCollections:\n${cols || "—"}`;
  } catch (e) {
    el.innerHTML = "";
    const txt = document.createElement("span");
    txt.textContent = "qmd daemon: down ";
    el.appendChild(txt);
    const btn = document.createElement("a");
    btn.href = "#";
    btn.textContent = "[Start]";
    btn.style.color = "var(--accent)";
    btn.onclick = ev => { ev.preventDefault(); startQmdDaemon(btn); };
    el.appendChild(btn);
    el.style.color = "var(--err)";
    el.title = `qmd MCP HTTP daemon unreachable\nClick [Start] to launch it, or run: qmd mcp --http --daemon\n\n${e}`;
  }
}

async function startQmdDaemon(btn) {
  btn.textContent = "[starting…]";
  btn.style.pointerEvents = "none";
  try {
    const res = await fetch("/api/qmd/start", {method: "POST"});
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      btn.textContent = "[start failed]";
      btn.title = body.error || ("HTTP " + res.status);
      return;
    }
  } catch (e) {
    btn.textContent = "[start failed]";
    btn.title = String(e);
    return;
  }
  refreshQmdStatus();
}
