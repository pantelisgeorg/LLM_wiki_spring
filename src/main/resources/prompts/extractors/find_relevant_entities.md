### Extractor: find_relevant_entities

**Person bias is the #1 failure mode of this extractor.** When a chunk centers on a thinker (e.g. Thales, Anaximander), models reflexively produce only that person + maybe one or two other people, and miss every other entity type. Counter that explicitly: before you commit to your entity list for this chunk, run the following procedure in order.

## Procedure (do this before writing any entity page)

1. **First pass — non-person entities.** Read the chunk and list candidates from these categories first, BEFORE adding any people:
   - **Schools / traditions / movements** named in the chunk (e.g. "the Milesian school", "οι Μιλήσιοι", "Stoicism", "the Vienna Circle"). These are entities, not concepts.
   - **Eras / periods** the chunk situates itself in (e.g. "the pre-Socratic period", "the 6th century BCE", "the Hellenistic age").
   - **Works** cited by name, especially if cited more than once (e.g. Aristotle's *Μετά τα φυσικά*, Plato's *Θεαίτητος*, Homer's *Ιλιάδα*).
   - **Events** the chunk references (e.g. "the eclipse of 585 BCE", "the Ionian Revolt", "the founding of Miletus").
   - **Places** that carry weight in the chunk's argument (e.g. Μίλητος, Αλεξάνδρεια — not just a name-drop, somewhere the chunk's claims actually anchor).
   - **Organizations / artifacts** (companies, labs, manuscripts, specific instruments).

2. **Second pass — people.** Only after the first pass, list the people. Include the chunk's main subject AND every cited authority (Aristotle attesting Thales, Theophrastus interpreting him, Plato quoting him) — see the "cited authorities" tier below.

3. **Pick the final set.** From the combined list, pick up to 8 entities total. Aim for **at least 2 non-person entities** when the chunk supports them — survey/historical sources almost always do. If after step 1 you have zero non-person candidates, the chunk genuinely doesn't support them and that's fine. But don't skip the first pass.

## Candidate types (full list)

"Entity" here means anything a reader could plausibly type into a search box as a proper noun — not just people. Candidates include:

- **Schools / traditions / movements** — named intellectual lineages (the **Milesian school**, the **Eleatics**, **Stoicism**, **Scholasticism**, **the Romantic movement**, **the Vienna Circle**). These are entities, not concepts — they have membership, founders, geographic centers, and dates.
- **Eras / periods** — named spans of time the source treats as units (the **pre-Socratic period**, the **Hellenistic age**, the **Enlightenment**, the **interwar period**). Give them a page when the source organizes its argument around them.
- **Works** — named books, papers, treatises, dialogues, articles, manuscripts, artworks the source quotes from or cites (e.g. Aristotle's *Metaphysics*, Plato's *Theaetetus*, Homer's *Iliad*). If a work is cited by name more than once, give it its own entity page.
- **Events** — named historical events the source refers to (the **Battle of Marathon**, the **Ionian Revolt**, the **fall of Constantinople**, the **Council of Nicaea**). One sentence on what happened + why this source mentions it is enough.
- **Places** — if the source's topic makes a specific location material (Miletus, Athens, Alexandria, …).
- **Organizations** — companies, labs, research groups, institutions, religious orders, political bodies.
- **Artifacts** — named physical objects with cultural weight (the **Antikythera mechanism**, the **Rosetta Stone**, specific named manuscripts or codices).
- **Tools / systems / models** — named software, products, APIs, trained models, frameworks.
- **Datasets / benchmarks** — named data artifacts.
- **People** — authors, speakers, researchers, practitioners, **historical figures cited as authorities** (e.g. "Aristotle says in *Metaphysics*…", "according to Plato in *Theaetetus*…"), thinkers the source attributes claims to, or anyone whose work/quotes the source draws on. A figure cited multiple times as a source-of-record is a substantial entity, not a passing mention. *(Listed last deliberately — see Procedure above.)*

**Rule of thumb for distinguishing entity from concept:** if you can naturally write "the X" or "X's [members/founders/date/location]" → entity (the Milesian school, the Stoa). If you can naturally write "X is when…" or "X explains…" → concept (causation, free will). When unsure, prefer entity — entities have stronger cross-link affinities and the graph benefits more from them.

**Page depth is tiered by role — do NOT spend the same effort on every entity.** Length budget is per-entity, not per-page-globally:

- **Main subject(s) of the chunk** (Thales in a chunk about Thales): 1200–2000 chars. Opening sentence, 2–4 paragraphs covering thesis, claims, connections, structural role.
- **Cited authorities** (Aristotle attesting Thales, Plato quoting him, Theophrastus interpreting him): **300–700 chars is enough**. One sentence on who they are + 2–3 sentences on what they said about this chunk's subject. Use shorter pages so you can include MORE such entities without blowing the budget.
- **Mentioned but undeveloped** (a place named once, a figure cited in passing without a claim): skip OR a single sentence stub.

The point: a single chunk's output should be **many short entity pages plus one or two long ones**, not just one or two long pages. **The failure mode is missing entities, not under-developed entities.** When in doubt, include the entity with a 300-char page rather than skipping.

Every entity page ends with a `## Sources` section listing `[[wiki/sources/<slug>.md]]`. Cross-link to other pages via `[[wiki/entities/...]]` / `[[wiki/concepts/...]]` when natural.

**Pad nothing — use only material grounded in this source.** But also: do not skip Aristotle just because the chunk is "about Thales." If the chunk cites Aristotle's *Metaphysics* attributing the water-as-archē claim to Thales, **Aristotle gets a page** — short, but he gets one.

A chunk that introduces 4 figures should produce 4 entity pages (1 long + 3 short), not 1 page. Survey / historical sources yield many entities per chunk specifically because they cite many authorities — that's the whole point.

**Concrete example (Greek pre-Socratic philosophy chunk):** a chunk centered on Anaximander should produce something like:
- Αναξίμανδρος (long, main subject)
- Μιλήσιοι / Μιλήσια σχολή (short — the tradition he belongs to)
- Προσωκρατική περίοδος (short — the era this chunk situates itself in)
- Αριστοτέλης (short — cited as attestation source)
- Θεόφραστος (short — cited as interpreter)
- Φυσικά (short — the Aristotelian work the chunk quotes)
- Ίωνες / Ιωνία (short — geographic anchor) — if mentioned

That's 5–7 entities from one chunk, of which only 2 are people-as-main-subject. The rest are the connective tissue — school, era, attesting authority, cited work, place — that lets the graph actually form. **A chunk that produces only "Αναξίμανδρος" is a failure of this extractor, not an honest reading of the source.**
