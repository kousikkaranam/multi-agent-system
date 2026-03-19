package com.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Web search tool — gives ALL agents real-time internet access.
 *
 * 6 search engines with automatic fallback chain:
 *   1. Serper.dev  (Google results, 2500 free queries)
 *   2. Brave Search API (2000 free/month, independent index)
 *   3. Tavily (1000 free/month, AI-optimized)
 *   4. SerpAPI (100 free/month, supports DuckDuckGo + Google)
 *   5. Google Custom Search (100 free/day)
 *   6. Bing RSS (unlimited, no key needed — always-on fallback)
 *
 * Combined free capacity: ~6,700+ searches/month with all keys set.
 * Works with ZERO config using Bing RSS fallback.
 */
@Slf4j
@Component
public class WebSearchTool {

    @Value("${search.serper-api-key:}")
    private String serperApiKey;

    @Value("${search.brave-api-key:}")
    private String braveApiKey;

    @Value("${tavily.api-key:}")
    private String tavilyApiKey;

    @Value("${search.serpapi-key:}")
    private String serpApiKey;

    @Value("${google.search.api-key:}")
    private String googleApiKey;

    @Value("${google.search.cx:}")
    private String googleSearchCx;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService searchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // Minimum engines to wait for before merging (if more are configured)
    private static final int MIN_RESULTS_BEFORE_MERGE = 2;
    // Max wait time for all engines
    private static final long MAX_WAIT_MS = 8_000;
    // After first result arrives, wait this long for more before merging
    private static final long GRACE_PERIOD_MS = 3_000;

    /**
     * Parallel multi-engine search with result merging.
     *
     * Strategy:
     * 1. Fire ALL configured engines simultaneously on virtual threads
     * 2. Collect results as they arrive (fastest first via CompletionService)
     * 3. Wait until: enough engines respond OR timeout
     * 4. MERGE all results: deduplicate by URL, rank by cross-engine agreement
     * 5. Return one comprehensive, accurate result
     *
     * If 3/6 engines return the same article → high confidence.
     * Unique results from different engines → broader coverage.
     */
    public String search(String query) {
        long start = System.currentTimeMillis();

        // Build list of configured engines
        record SearchTask(String name, Callable<SearchResult> task) {}

        List<SearchTask> tasks = new ArrayList<>();
        if (isSet(serperApiKey))  tasks.add(new SearchTask("Serper",  () -> parseResults("Serper", serperSearch(query))));
        if (isSet(braveApiKey))   tasks.add(new SearchTask("Brave",   () -> parseResults("Brave", braveSearch(query))));
        if (isSet(tavilyApiKey))  tasks.add(new SearchTask("Tavily",  () -> parseResults("Tavily", tavilySearch(query))));
        if (isSet(serpApiKey))    tasks.add(new SearchTask("SerpAPI", () -> parseResults("SerpAPI", serpApiSearch(query))));
        if (isSet(googleApiKey) && isSet(googleSearchCx))
                                  tasks.add(new SearchTask("Google",  () -> parseResults("Google", googleSearch(query))));
        // Bing RSS always runs — guaranteed fallback
        tasks.add(new SearchTask("BingRSS", () -> parseResults("BingRSS", bingRssSearch(query))));

        int totalEngines = tasks.size();
        log.info("[WebSearch] Firing {} engines in parallel for: '{}'", totalEngines,
                query.length() > 60 ? query.substring(0, 60) + "..." : query);

        // Fire all engines and use CompletionService to get results as they complete
        CompletionService<SearchResult> completionService =
                new ExecutorCompletionService<>(searchExecutor);

        for (SearchTask task : tasks) {
            completionService.submit(task.task());
        }

        // Collect results as they arrive
        List<SearchResult> collectedResults = new ArrayList<>();
        List<String> respondedEngines = new ArrayList<>();
        long deadline = start + MAX_WAIT_MS;
        long graceDeadline = 0; // Set after first result arrives

        for (int i = 0; i < totalEngines; i++) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;

            // After first result, apply grace period for more results
            if (!collectedResults.isEmpty() && graceDeadline > 0) {
                remaining = Math.min(remaining, graceDeadline - System.currentTimeMillis());
                if (remaining <= 0) break;
            }

            try {
                Future<SearchResult> future = completionService.poll(remaining, TimeUnit.MILLISECONDS);
                if (future == null) break; // Timeout

                SearchResult result = future.get();
                if (result != null && !result.entries.isEmpty()) {
                    collectedResults.add(result);
                    respondedEngines.add(result.engine);
                    log.debug("[WebSearch] {} returned {} entries in {}ms",
                            result.engine, result.entries.size(),
                            System.currentTimeMillis() - start);

                    // Set grace deadline after first result
                    if (graceDeadline == 0) {
                        graceDeadline = System.currentTimeMillis() + GRACE_PERIOD_MS;
                    }

                    // If we have enough results and multiple engines agree, stop waiting
                    if (collectedResults.size() >= MIN_RESULTS_BEFORE_MERGE
                            && collectedResults.size() >= Math.min(3, totalEngines)) {
                        break;
                    }
                }
            } catch (Exception e) {
                // Engine failed — continue collecting from others
            }
        }

        long elapsed = System.currentTimeMillis() - start;

        if (collectedResults.isEmpty()) {
            log.error("[WebSearch] All {} engines failed for '{}' in {}ms", totalEngines, query, elapsed);
            return "Search failed. Please try again.";
        }

        // MERGE results from all engines
        String merged = mergeResults(collectedResults);
        log.info("[WebSearch] '{}' → merged from {} engines [{}] in {}ms",
                query.length() > 40 ? query.substring(0, 40) + "..." : query,
                respondedEngines.size(), String.join(", ", respondedEngines), elapsed);

        return merged;
    }

    // ─── Result Merging (the accuracy engine) ───

    /**
     * Merge results from multiple search engines into one comprehensive answer.
     * - Deduplicates by URL (same page from different engines = high confidence)
     * - Ranks entries: appeared in more engines = shown first
     * - Keeps unique entries from each engine for broader coverage
     */
    private String mergeResults(List<SearchResult> results) {
        // Track entries by normalized URL → entry with cross-engine count
        Map<String, MergedEntry> urlMap = new LinkedHashMap<>();
        List<MergedEntry> noUrlEntries = new ArrayList<>();

        for (SearchResult sr : results) {
            for (SearchEntry entry : sr.entries) {
                String key = normalizeUrl(entry.url);
                if (key != null && !key.isBlank()) {
                    urlMap.compute(key, (k, existing) -> {
                        if (existing == null) {
                            return new MergedEntry(entry.title, entry.url, entry.snippet, 1, sr.engine);
                        }
                        existing.engineCount++;
                        existing.engines += ", " + sr.engine;
                        // Keep longer snippet (more informative)
                        if (entry.snippet != null && entry.snippet.length() > existing.snippet.length()) {
                            existing.snippet = entry.snippet;
                        }
                        return existing;
                    });
                } else if (entry.title != null && !entry.title.isBlank()) {
                    // AI summaries or entries without URLs
                    noUrlEntries.add(new MergedEntry(entry.title, "", entry.snippet, 1, sr.engine));
                }
            }
        }

        // Sort: entries appearing in MORE engines rank higher (cross-validated = accurate)
        List<MergedEntry> sorted = new ArrayList<>(urlMap.values());
        sorted.sort((a, b) -> {
            if (b.engineCount != a.engineCount) return b.engineCount - a.engineCount;
            return b.snippet.length() - a.snippet.length(); // Longer snippet = more info
        });

        // Build final output
        StringBuilder sb = new StringBuilder();
        sb.append("### Web Search Results (Live — ").append(results.size()).append(" sources)\n\n");

        // AI summaries first (if any engine provided one)
        for (MergedEntry entry : noUrlEntries) {
            if (entry.snippet != null && entry.snippet.length() > 50) {
                sb.append("**Summary:** ").append(entry.snippet).append("\n\n");
                break; // Only one summary
            }
        }

        // Ranked results
        int count = 0;
        for (MergedEntry entry : sorted) {
            if (count >= 8) break; // Cap at 8 results
            count++;
            sb.append("**").append(count).append(". ").append(entry.title).append("**");
            if (entry.engineCount > 1) {
                sb.append(" (").append(entry.engineCount).append(" sources)");
            }
            sb.append("\n");
            if (entry.url != null && !entry.url.isBlank()) {
                sb.append(entry.url).append("\n");
            }
            if (entry.snippet != null && !entry.snippet.isBlank()) {
                String snippet = entry.snippet.length() > 400
                        ? entry.snippet.substring(0, 400) + "..." : entry.snippet;
                sb.append(snippet).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Parse raw engine output into structured SearchResult for merging.
     */
    private SearchResult parseResults(String engine, String rawOutput) {
        List<SearchEntry> entries = new ArrayList<>();
        if (rawOutput == null || rawOutput.isBlank()) return new SearchResult(engine, entries);

        // Parse the markdown-formatted results into structured entries
        String[] lines = rawOutput.split("\n");
        String currentTitle = null;
        String currentUrl = null;
        StringBuilder currentSnippet = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("**") && line.contains(".")) {
                // Save previous entry
                if (currentTitle != null) {
                    entries.add(new SearchEntry(currentTitle, currentUrl, currentSnippet.toString().trim()));
                }
                // New entry: "**1. Title**" or "**Answer:** text"
                currentTitle = line.replaceAll("\\*\\*", "").replaceAll("^\\d+\\.\\s*", "").trim();
                if (currentTitle.endsWith("(") || currentTitle.isEmpty()) currentTitle = null;
                currentUrl = null;
                currentSnippet = new StringBuilder();
            } else if (line.startsWith("**Summary:**") || line.startsWith("**Answer:**")) {
                String summary = line.replaceAll("\\*\\*[^*]+\\*\\*\\s*", "").trim();
                entries.add(new SearchEntry("AI Summary", "", summary));
            } else if (line.startsWith("http://") || line.startsWith("https://")) {
                currentUrl = line.trim();
            } else if (!line.startsWith("###") && !line.isBlank() && currentTitle != null) {
                if (currentSnippet.length() > 0) currentSnippet.append(" ");
                currentSnippet.append(line);
            }
        }
        // Save last entry
        if (currentTitle != null) {
            entries.add(new SearchEntry(currentTitle, currentUrl, currentSnippet.toString().trim()));
        }

        return new SearchResult(engine, entries);
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        return url.toLowerCase()
                .replaceAll("^https?://", "")
                .replaceAll("^www\\.", "")
                .replaceAll("/+$", "");
    }

    private boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    // ─── Data structures for merging ───

    record SearchEntry(String title, String url, String snippet) {}
    record SearchResult(String engine, List<SearchEntry> entries) {}

    static class MergedEntry {
        String title;
        String url;
        String snippet;
        int engineCount;
        String engines;

        MergedEntry(String title, String url, String snippet, int engineCount, String engine) {
            this.title = title;
            this.url = url;
            this.snippet = snippet != null ? snippet : "";
            this.engineCount = engineCount;
            this.engines = engine;
        }
    }

    /**
     * Fetch and extract text content from a URL.
     */
    public String fetchUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; MultiAgentSystem/1.0)")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body();
                String text = body.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                        .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                        .replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
                if (text.length() > 8000) {
                    text = text.substring(0, 8000) + "\n... [truncated]";
                }
                log.info("[WebFetch] {} → {} chars", url, text.length());
                return text;
            } else {
                return "HTTP " + response.statusCode() + " error fetching URL";
            }
        } catch (Exception e) {
            log.error("[WebFetch] Failed for '{}': {}", url, e.getMessage());
            return "Failed to fetch URL: " + e.getMessage();
        }
    }

    // ─── 1. Serper.dev (Google SERP, 2500 free queries) ───

    private String serperSearch(String query) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("q", query, "num", 5));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://google.serper.dev/search"))
                .header("X-API-KEY", serperApiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        StringBuilder sb = new StringBuilder();
        sb.append("### Search Results (Live — Google)\n\n");

        // Knowledge graph answer
        if (root.has("answerBox")) {
            JsonNode box = root.get("answerBox");
            if (box.has("answer")) sb.append("**Answer:** ").append(box.get("answer").asText()).append("\n\n");
            else if (box.has("snippet")) sb.append("**Answer:** ").append(box.get("snippet").asText()).append("\n\n");
        }

        // Organic results
        JsonNode organic = root.get("organic");
        if (organic != null && organic.isArray()) {
            for (int i = 0; i < Math.min(organic.size(), 5); i++) {
                JsonNode r = organic.get(i);
                sb.append("**").append(i + 1).append(". ").append(safeText(r, "title")).append("**\n");
                sb.append(safeText(r, "link")).append("\n");
                sb.append(safeText(r, "snippet")).append("\n\n");
            }
        }

        return sb.toString();
    }

    // ─── 2. Brave Search API (2000 free/month) ───

    private String braveSearch(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://api.search.brave.com/res/v1/web/search?q=" + encoded + "&count=5";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-Subscription-Token", braveApiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        StringBuilder sb = new StringBuilder();
        sb.append("### Search Results (Live — Brave)\n\n");

        JsonNode results = root.path("web").path("results");
        if (results.isArray()) {
            for (int i = 0; i < Math.min(results.size(), 5); i++) {
                JsonNode r = results.get(i);
                sb.append("**").append(i + 1).append(". ").append(safeText(r, "title")).append("**\n");
                sb.append(safeText(r, "url")).append("\n");
                sb.append(safeText(r, "description")).append("\n\n");
            }
        }

        return sb.toString();
    }

    // ─── 3. Tavily (1000 free/month, AI-optimized) ───

    private String tavilySearch(String query) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "api_key", tavilyApiKey,
                "query", query,
                "search_depth", "basic",
                "max_results", 5,
                "include_answer", true
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        StringBuilder sb = new StringBuilder();

        if (root.has("answer") && !root.get("answer").isNull()) {
            sb.append("### AI Summary\n").append(root.get("answer").asText()).append("\n\n");
        }

        JsonNode results = root.get("results");
        if (results != null && results.isArray()) {
            sb.append("### Search Results (Live — Tavily)\n");
            for (int i = 0; i < Math.min(results.size(), 5); i++) {
                JsonNode r = results.get(i);
                sb.append("**").append(i + 1).append(". ").append(safeText(r, "title")).append("**\n");
                sb.append(safeText(r, "url")).append("\n");
                String content = safeText(r, "content");
                if (content.length() > 300) content = content.substring(0, 300) + "...";
                sb.append(content).append("\n\n");
            }
        }

        return sb.toString();
    }

    // ─── 4. SerpAPI (100 free/month, DuckDuckGo + Google engines) ───

    private String serpApiSearch(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://serpapi.com/search.json?engine=duckduckgo&q=" + encoded
                + "&kl=us-en&api_key=" + serpApiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        StringBuilder sb = new StringBuilder();
        sb.append("### Search Results (Live — DuckDuckGo via SerpAPI)\n\n");

        JsonNode organic = root.get("organic_results");
        if (organic != null && organic.isArray()) {
            for (int i = 0; i < Math.min(organic.size(), 5); i++) {
                JsonNode r = organic.get(i);
                sb.append("**").append(i + 1).append(". ").append(safeText(r, "title")).append("**\n");
                sb.append(safeText(r, "link")).append("\n");
                sb.append(safeText(r, "snippet")).append("\n\n");
            }
        }

        return sb.toString();
    }

    // ─── 5. Google Custom Search (100 free/day) ───

    private String googleSearch(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://www.googleapis.com/customsearch/v1?key=" + googleApiKey
                + "&cx=" + googleSearchCx + "&q=" + encoded + "&num=5";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        StringBuilder sb = new StringBuilder();
        sb.append("### Search Results (Live — Google)\n\n");

        JsonNode items = root.get("items");
        if (items != null && items.isArray()) {
            for (int i = 0; i < Math.min(items.size(), 5); i++) {
                JsonNode item = items.get(i);
                sb.append("**").append(i + 1).append(". ").append(safeText(item, "title")).append("**\n");
                sb.append(safeText(item, "link")).append("\n");
                sb.append(safeText(item, "snippet")).append("\n\n");
            }
        }

        return sb.toString();
    }

    // ─── 6. Bing RSS (unlimited, no key — always-on fallback) ───

    private String bingRssSearch(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://www.bing.com/search?format=rss&q=" + encoded + "&count=6";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String xml = response.body();

        StringBuilder sb = new StringBuilder();
        sb.append("### Search Results (Live — Bing)\n\n");

        String[] items = xml.split("<item>");
        int count = 0;
        for (int i = 1; i < items.length && count < 6; i++) {
            String item = items[i];
            String title = extractXmlTag(item, "title");
            String link = extractXmlTag(item, "link");
            String description = extractXmlTag(item, "description");

            if (title != null && !title.isBlank()) {
                count++;
                sb.append("**").append(count).append(". ").append(title).append("**\n");
                if (link != null) sb.append(link).append("\n");
                if (description != null && !description.isBlank()) {
                    String clean = description
                            .replaceAll("&amp;", "&").replaceAll("&lt;", "<")
                            .replaceAll("&gt;", ">").replaceAll("&#\\d+;", " ")
                            .replaceAll("<[^>]+>", "").trim();
                    if (clean.length() > 300) clean = clean.substring(0, 300) + "...";
                    sb.append(clean).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    // ─── Utilities ───

    private String extractXmlTag(String xml, String tag) {
        int start = xml.indexOf("<" + tag + ">");
        int end = xml.indexOf("</" + tag + ">");
        if (start >= 0 && end > start) {
            return xml.substring(start + tag.length() + 2, end).trim();
        }
        return null;
    }

    private String safeText(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
    }
}
