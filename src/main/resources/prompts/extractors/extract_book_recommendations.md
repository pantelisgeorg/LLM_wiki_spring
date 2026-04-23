### Extractor: extract_book_recommendations

Catch every external work the source points at — books, papers, tools, videos, datasets, blog posts. Each one becomes a line in a `## References` section of the source summary page.

Format each reference as:

- `[Title](<url-if-given-else-omit>) — <author/creator if named> — <1-line reason the source mentions it>`

If the URL isn't present in the source, omit the link syntax and just list the title in plain text. Do not fabricate URLs.

For works that the source discusses substantively (not just name-drops), also create an entity page — the work becomes a node in the wiki in its own right. For bare mentions, the reference line is sufficient.
