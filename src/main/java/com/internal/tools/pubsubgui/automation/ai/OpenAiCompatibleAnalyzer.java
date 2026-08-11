package com.internal.tools.pubsubgui.automation.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeRequest;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Calls any OpenAI-compatible {@code POST {base-url}/chat/completions} endpoint (OpenAI, Azure OpenAI
 * gateways, local proxies, etc.). Throws on any non-2xx / parse failure so the dispatcher can fall
 * back to the heuristic analyzer.
 */
final class OpenAiCompatibleAnalyzer implements AiAnalyzer {

    private final AiProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    OpenAiCompatibleAnalyzer(AiProperties props) {
        this.props = props;
    }

    @Override
    public AiAnalyzeResponse analyze(AiAnalyzeRequest request) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", props.getModel());
            payload.put("temperature", 0.2);
            ArrayNode messages = payload.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", AiPromptBuilder.SYSTEM);
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", AiPromptBuilder.user(request));

            String url = props.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .build()
                    .send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("LLM HTTP " + response.statusCode() + ": "
                        + truncate(response.body()));
            }
            JsonNode root = mapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("LLM returned no content");
            }
            return new AiAnalyzeResponse("openai", true, content.trim());
        } catch (Exception e) {
            throw new RuntimeException("OpenAI-compatible analysis failed: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
