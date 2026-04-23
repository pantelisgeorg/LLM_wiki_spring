### Gate: check_falsifiability

An edit fails this gate if it states a claim in unfalsifiable form — one that couldn't be proven wrong by any observation.

Red flags:
- **Always / never** with no conditions ("this method is always better").
- **Best / optimal** without a comparison class and metric.
- **Work / effective** without conditions of use.
- **Hedged infinity** — "could in principle handle any scale" with no evidence.

Remediation preference:
1. **Tighten** — add the conditions the source actually states ("…on MMLU", "…for texts under 8K tokens", "…when latency is not a constraint").
2. If the source doesn't state conditions, demote to opinion: "The authors argue that X" rather than "X is true".
3. **Drop** — only if the claim is load-bearing and has no supporting detail in the source.

Do not apply this gate to obvious framing statements ("this paper is about X") — only to substantive claims about the world.
