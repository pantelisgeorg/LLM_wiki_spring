package com.wiki.service;

import com.wiki.dto.LoadedSource;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Turns any supported source (.md/.txt/.pdf/.html/url/image) into plain text.
 * Mutates raw/ — preserves original bytes or cleaned conversion locally.
 */
@Service
public class RawSourceLoader {
    private static final Logger log = LoggerFactory.getLogger(RawSourceLoader.class);

    private final WikiStore store;
    private final ChatClient chatClient;
    private final Tika tika = new Tika();

    public RawSourceLoader(WikiStore store, ChatClient.Builder chatClientBuilder) {
        this.store = store;
        this.chatClient = chatClientBuilder.build();
    }

    public LoadedSource loadFromFile(Path file) throws IOException {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase();

        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return new LoadedSource(stripExt(name), text, file.toString());
        }

        if (lower.endsWith(".pdf")) {
            String text = parseWithTika(Files.readAllBytes(file));
            return new LoadedSource(stripExt(name), text, file.toString());
        }

        if (isImage(lower)) {
            String text = captionImage(Files.readAllBytes(file), mimeOf(lower));
            return new LoadedSource(stripExt(name), text, file.toString());
        }

        String text = parseWithTika(Files.readAllBytes(file));
        return new LoadedSource(stripExt(name), text, file.toString());
    }

    public LoadedSource loadFromUrl(String url) throws IOException {
        Document doc = Jsoup.connect(url).userAgent("llm-wiki/0.1").get();
        String title = doc.title().isBlank() ? slugFromUrl(url) : doc.title();

        String cleanedHtml = Jsoup.clean(doc.body().html(), Safelist.basic());
        String text;
        try {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            parser.parse(new ByteArrayInputStream(cleanedHtml.getBytes(StandardCharsets.UTF_8)),
                    handler, new Metadata());
            text = handler.toString();
        } catch (Exception e) {
            throw new IOException("Failed to parse URL content via Tika", e);
        }

        String slug = slugify(title);
        String markdown = "# " + title + "\n\nSource: " + url + "\n\n" + text.trim() + "\n";
        Path saved = store.saveRawText("web", slug, ".md", markdown);
        log.info("Saved web source to {}", saved);
        return new LoadedSource(title, markdown, saved.toString());
    }

    public LoadedSource loadFromBytes(String filename, byte[] bytes) throws IOException {
        String lower = filename.toLowerCase();
        String slug = slugify(stripExt(filename));

        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            Path saved = store.saveRawBytes("articles", slug, extOf(filename), bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return new LoadedSource(stripExt(filename), text, saved.toString());
        }
        if (lower.endsWith(".pdf")) {
            Path saved = store.saveRawBytes("pdfs", slug, ".pdf", bytes);
            String text = parseWithTika(bytes);
            return new LoadedSource(stripExt(filename), text, saved.toString());
        }
        if (isImage(lower)) {
            Path saved = store.saveRawBytes("assets", slug, extOf(filename), bytes);
            String text = captionImage(bytes, mimeOf(lower));
            return new LoadedSource(stripExt(filename), text, saved.toString());
        }
        Path saved = store.saveRawBytes("articles", slug, extOf(filename), bytes);
        String text = parseWithTika(bytes);
        return new LoadedSource(stripExt(filename), text, saved.toString());
    }

    private String parseWithTika(byte[] bytes) throws IOException {
        try {
            return tika.parseToString(new ByteArrayInputStream(bytes)).trim();
        } catch (Exception e) {
            throw new IOException("Tika parse failed", e);
        }
    }

    private String captionImage(byte[] bytes, String mime) {
        Media media = new Media(MimeType.valueOf(mime), new ByteArrayResource(bytes));
        UserMessage userMessage = new UserMessage(
                "Describe this image in detail. If there is any visible text, transcribe it verbatim. "
                        + "Output format: first a one-paragraph caption, then a '## Text' section with any transcribed text.",
                List.of(media));
        String result = chatClient.prompt(new Prompt(userMessage)).call().content();
        return result == null ? "" : result;
    }

    private static boolean isImage(String lower) {
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp");
    }

    private static String mimeOf(String lower) {
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i);
    }

    public static String slugify(String s) {
        String slug = s.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.length() > 80) slug = slug.substring(0, 80);
        return slug.isBlank() ? "untitled" : slug;
    }

    private static String slugFromUrl(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost() == null ? "" : u.getHost().replace("www.", "");
            String path = u.getPath() == null ? "" : u.getPath();
            return slugify(host + "-" + path);
        } catch (Exception e) {
            return slugify(url);
        }
    }
}
