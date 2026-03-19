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
import java.util.Map;

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

    /**
     * Search the web using the best available engine.
     * Automatically falls through the chain if one fails.
     */
    public String search(String query) {
        long start = System.currentTimeMillis();

        // Try engines in priority order
        String[][] engines = {
                {serperApiKey, "Serper"},
                {braveApiKey, "Brave"},
                {tavilyApiKey, "Tavily"},
                {serpApiKey, "SerpAPI"},
                {googleApiKey, "Google"},
        };

        for (String[] engine : engines) {
            if (engine[0] != null && !engine[0].isBlank()) {
                try {
                    String result = switch (engine[1]) {
                        case "Serper" -> serperSearch(query);
                        case "Brave" -> braveSearch(query);
                        case "Tavily" -> tavilySearch(query);
                        case "SerpAPI" -> serpApiSearch(query);
                        case "Google" -> googleSearch(query);
                        default -> null;
                    };
                    if (result != null && result.length() > 50) {
                        long elapsed = System.currentTimeMillis() - start;
                        log.info("[WebSearch] '{}' via {} → {} chars in {}ms",
                                query, engine[1], result.length(), elapsed);
                        return result;
                    }
                } catch (Exception e) {
                    log.warn("[WebSearch] {} failed: {}, trying next...", engine[1], e.getMessage());
                }
            }
        }

        // Ultimate fallback: Bing RSS (no key needed)
        try {
            String result = bingRssSearch(query);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[WebSearch] '{}' via BingRSS → {} chars in {}ms", query, result.length(), elapsed);
            return result;
        } catch (Exception e) {
            log.error("[WebSearch] All engines failed for '{}': {}", query, e.getMessage());
            return "Search failed. Please try again.";
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
