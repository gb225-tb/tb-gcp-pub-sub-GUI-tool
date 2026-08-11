package com.internal.tools.pubsubgui.automation.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeRequest;
import com.internal.tools.pubsubgui.automation.model.AiAnalyzeResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Analyzer backed by the Cursor Cloud Agents API v1 (https://api.cursor.com), authenticated with a
 * Cursor API key ({@code CURSOR_API_KEY}). It launches a <em>no-repo</em> agent (omitting {@code repos}
 * and {@code env}) whose prompt is the failure analysis instruction, then polls the run until it
 * reaches a terminal state and returns the run's final {@code result} text.
 *
 * <p>The Cloud Agents API is async (create agent -> enqueue run -> poll), so a single analysis can take
 * from a few seconds up to a couple of minutes. Any failure throws so the dispatcher falls back to the
 * offline heuristic analyzer.
 *
 * @see <a href="https://cursor.com/docs/cloud-agent/api/endpoints">Cloud Agents API endpoints</a>
 */
final class CursorAgentAnalyzer implements AiAnalyzer {

    private static final Set<String> TERMINAL_OK = Set.of("FINISHED");
    private static final Set<String> TERMINAL_BAD = Set.of("ERROR", "FAILED", "CANCELLED", "EXPIRED");

    private final AiProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    CursorAgentAnalyzer(AiProperties props) {
        this.props = props;
    }

    @Override
    public AiAnalyzeResponse analyze(AiAnalyzeRequest request) {
        try {
            String base = props.getCursorBaseUrl().replaceAll("/+$", "");
            String prompt = AiPromptBuilder.SYSTEM + "\n\n" + AiPromptBuilder.user(request);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .build();

            // 1) Create a no-repo agent with the analysis prompt (repos/env omitted).
            ObjectNode payload = mapper.createObjectNode();
            payload.putObject("prompt").put("text", prompt);
            if (!props.getCursorModel().isBlank()) {
                payload.putObject("model").put("id", props.getCursorModel());
            }
            JsonNode created = send(client, HttpRequest.newBuilder(URI.create(base + "/v1/agents"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getCursorApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build(), "create agent");

            String agentId = created.path("agent").path("id").asText(null);
            String runId = created.path("run").path("id").asText(
                    created.path("agent").path("latestRunId").asText(null));
            if (agentId == null || runId == null) {
                throw new IllegalStateException("Cursor create response missing agent/run id");
            }

            // 2) Poll the run until it reaches a terminal status, then read its final result text.
            String runUrl = base + "/v1/agents/" + agentId + "/runs/" + runId;
            Instant deadline = Instant.now().plusSeconds(props.getCursorMaxWaitSeconds());
            while (Instant.now().isBefore(deadline)) {
                Thread.sleep(2500);
                JsonNode run;
                try {
                    run = send(client, HttpRequest.newBuilder(URI.create(runUrl))
                            .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                            .header("Authorization", "Bearer " + props.getCursorApiKey())
                            .GET().build(), "get run");
                } catch (RuntimeException transientError) {
                    continue; // tolerate transient poll errors until the deadline
                }
                String status = run.path("status").asText("");
                if (TERMINAL_OK.contains(status.toUpperCase())) {
                    String result = run.path("result").asText(null);
                    if (result == null || result.isBlank()) {
                        throw new IllegalStateException("Cursor run FINISHED without a result");
                    }
                    return new AiAnalyzeResponse("cursor", true, result.trim());
                }
                if (TERMINAL_BAD.contains(status.toUpperCase())) {
                    throw new IllegalStateException("Cursor run ended with status " + status);
                }
            }
            throw new IllegalStateException("Cursor run did not finish within "
                    + props.getCursorMaxWaitSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Cursor analysis interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("Cursor analysis failed: " + e.getMessage(), e);
        }
    }

    private JsonNode send(HttpClient client, HttpRequest request, String what) throws Exception {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Cursor " + what + " HTTP " + response.statusCode() + ": "
                    + truncate(response.body()));
        }
        return mapper.readTree(response.body());
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
