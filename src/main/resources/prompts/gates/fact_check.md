### Gate: fact_check

An edit fails this gate if it asserts a fact that the source (as summarized in the candidate) doesn't actually support. Watch for hallucinated specifics: numbers, dates, proper names, attributions that appear on the wiki page but not in anything the source would plausibly contain.

Red flags:
- Numerical precision the source wouldn't have ("78.2% accuracy" when the source summary only mentioned "high accuracy").
- Attributions to a specific named person when the source summary is anonymous.
- Dates with day/month specificity in an edit for a source that's clearly a general essay.
- Named datasets / benchmarks / versions that the summary doesn't mention.

Remediation preference:
1. **Tighten** — replace the fabricated specific with what the source summary actually supports ("high accuracy" instead of "78.2%").
2. If the specific is load-bearing and the source summary provides no grounding, **drop the edit**.

Because the gate only sees the candidate IngestResult (not the raw source), be conservative: only flag specifics that are obviously ungrounded by the candidate's own content.
