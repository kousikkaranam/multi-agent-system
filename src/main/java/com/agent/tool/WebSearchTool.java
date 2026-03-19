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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web search tool — gives ALL agents real-time internet access.
 *
 * This is the Jarvis capability: any agent can search the internet for
 * real-time data (stocks, news, weather, sports, anything).
 *
 * Supports two backends:
 * 1. Tavily Search (if TAVILY_API_KEY is set) — best quality, designed for AI
 * 2. DuckDuckGo Instant Answer API (free fallback) — no API key needed
 *
 * Also provides web page fetching to read specific URLs.
 */
@Slf4j
@Component
public class WebSearchTool {

    @Value("${tavily.api-key:}")
    private String tavilyApiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Search the web for a query. Returns structured results.
     */
    public String search(String query) {
        long start = System.currentTimeMillis();
        try {
            String result;
            if (tavilyApiKey != null && !tavilyApiKey.isBlank()) {
                result = tavilySearch(query);
            } else {
                result = duckDuckGoSearch(query);
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("[WebSearch] '{}' → {} chars in {}ms", query, result.length(), elapsed);
            return result;
        } catch (Exception e) {
            log.error("[WebSearch] Failed for '{}': {}", query, e.getMessage());
            return "Search failed: " + e.getMessage();
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
                // Strip HTML tags for clean text
                String text = body.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
                        .replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
                        .replaceAll("<[^>]+>", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
                // Truncate to reasonable size
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

    // ─── Tavily Search (primary — best quality for AI agents) ───

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

        // Tavily's AI-generated answer (concise summary)
        if (root.has("answer") && !root.get("answer").isNull()) {
            sb.append("### AI Summary\n").append(root.get("answer").asText()).append("\n\n");
        }

        // Individual search results
        JsonNode results = root.get("results");
        if (results != null && results.isArray()) {
            sb.append("### Search Results\n");
            for (int i = 0; i < Math.min(results.size(), 5); i++) {
                JsonNode r = results.get(i);
                sb.append("**").append(i + 1).append(". ").append(r.get("title").asText()).append("**\n");
                sb.append(r.get("url").asText()).append("\n");
                if (r.has("content")) {
                    String content = r.get("content").asText();
                    if (content.length() > 300) content = content.substring(0, 300) + "...";
                    sb.append(content).append("\n\n");
                }
            }
        }

        return sb.toString();
    }

    // ─── DuckDuckGo Search (free fallback — no API key needed) ───

    private String duckDuckGoSearch(String query) throws Exception {
        // DuckDuckGo Instant Answer API (free, no key)
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://api.duckduckgo.com/?q=" + encoded + "&format=json&no_html=1&skip_disambig=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "MultiAgentSystem/1.0")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        StringBuilder sb = new StringBuilder();

        // Abstract (main answer)
        String abstractText = root.has("Abstract") ? root.get("Abstract").asText() : "";
        String abstractUrl = root.has("AbstractURL") ? root.get("AbstractURL").asText() : "";
        if (!abstractText.isBlank()) {
            sb.append("### Answer\n").append(abstractText).append("\n");
            if (!abstractUrl.isBlank()) sb.append("Source: ").append(abstractUrl).append("\n\n");
        }

        // Related topics
        JsonNode topics = root.get("RelatedTopics");
        if (topics != null && topics.isArray() && !topics.isEmpty()) {
            sb.append("### Related Information\n");
            int count = 0;
            for (JsonNode topic : topics) {
                if (topic.has("Text") && count < 5) {
                    sb.append("- ").append(topic.get("Text").asText()).append("\n");
                    count++;
                }
            }
        }

        // If DuckDuckGo returned nothing useful, do an HTML scrape fallback
        if (sb.isEmpty()) {
            sb.append("### Web Search Results\n");
            sb.append(scrapeSearchResults(query));
        }

        return sb.toString();
    }

    /**
     * Fallback: scrape DuckDuckGo HTML search results when the API returns nothing.
     */
    private String scrapeSearchResults(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://html.duckduckgo.com/html/?q=" + encoded;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String html = response.body();

        // Extract result snippets from DuckDuckGo HTML
        List<String> results = new ArrayList<>();
        String[] parts = html.split("class=\"result__snippet\"");
        for (int i = 1; i < Math.min(parts.length, 6); i++) {
            String snippet = parts[i];
            int endIdx = snippet.indexOf("</a>");
            if (endIdx > 0) {
                String text = snippet.substring(0, endIdx)
                        .replaceAll("<[^>]+>", "")
                        .replaceAll("\\s+", " ")
                        .trim();
                if (!text.isBlank() && text.length() > 20) {
                    results.add(text);
                }
            }
        }

        if (results.isEmpty()) {
            return "No results found. Try a more specific query.";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            sb.append(i + 1).append(". ").append(results.get(i)).append("\n\n");
        }
        return sb.toString();
    }
}
