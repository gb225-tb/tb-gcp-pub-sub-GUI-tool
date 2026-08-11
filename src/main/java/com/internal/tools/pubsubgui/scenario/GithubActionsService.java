package com.internal.tools.pubsubgui.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internal.tools.pubsubgui.scenario.config.GithubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Triggers batch Dataflow runs through GitHub Actions {@code workflow_dispatch} and polls the run to
 * completion. Uses {@link java.net.http.HttpClient} with a Bearer token from {@link GithubProperties}.
 * {@code workflow_dispatch} itself returns no run id, so after dispatching we locate the newest run of
 * that workflow on the ref created after the dispatch time and poll it by id.
 */
@Service
public class GithubActionsService {

    private static final Logger log = LoggerFactory.getLogger(GithubActionsService.class);
    private static final String API_VERSION = "2022-11-28";

    private final GithubProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public GithubActionsService(GithubProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", props.isConfigured());
        out.put("ref", props.getRef());
        return out;
    }

    /** A single workflow run's live state. */
    public record RunInfo(long id, String status, String conclusion, String htmlUrl, String createdAt) {
    }

    /**
     * Dispatch a workflow. Returns the dispatch epoch-millis (used as the "created after" cursor when
     * locating the resulting run).
     */
    public long dispatch(String repo, String workflowFile, String ref, Map<String, String> inputs) {
        requireToken();
        long dispatchedAt = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", ref == null || ref.isBlank() ? props.getRef() : ref);
        body.put("inputs", inputs == null ? Map.of() : inputs);
        String json = toJson(body);
        String url = props.getApiBase() + "/repos/" + repo + "/actions/workflows/" + workflowFile + "/dispatches";
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
        if (res.statusCode() != 204) {
            throw new IllegalStateException("workflow_dispatch failed (HTTP " + res.statusCode() + "): " + res.body());
        }
        log.info("scenario github | dispatched {} in {} (ref={}, inputs={})", workflowFile, repo,
                body.get("ref"), inputs);
        return dispatchedAt;
    }

    /**
     * Find the newest {@code workflow_dispatch} run of {@code workflowFile} on {@code ref}. Returns
     * null when none is visible yet (GitHub takes a moment to register the run after a dispatch).
     */
    public RunInfo findLatestRun(String repo, String workflowFile, String ref) {
        requireToken();
        String branch = ref == null || ref.isBlank() ? props.getRef() : ref;
        String url = props.getApiBase() + "/repos/" + repo + "/actions/workflows/" + workflowFile
                + "/runs?event=workflow_dispatch&branch=" + branch + "&per_page=5";
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(url)).GET());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("list runs failed (HTTP " + res.statusCode() + "): " + res.body());
        }
        JsonNode runs = readTree(res.body()).path("workflow_runs");
        if (!runs.isArray() || runs.isEmpty()) {
            return null;
        }
        JsonNode newest = runs.get(0);
        return new RunInfo(
                newest.path("id").asLong(),
                newest.path("status").asText(null),
                newest.path("conclusion").asText(null),
                newest.path("html_url").asText(null),
                newest.path("created_at").asText(null));
    }

    /** Fetch a run by id. */
    public RunInfo getRun(String repo, long runId) {
        requireToken();
        String url = props.getApiBase() + "/repos/" + repo + "/actions/runs/" + runId;
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(url)).GET());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("get run failed (HTTP " + res.statusCode() + "): " + res.body());
        }
        JsonNode n = readTree(res.body());
        return new RunInfo(
                n.path("id").asLong(),
                n.path("status").asText(null),
                n.path("conclusion").asText(null),
                n.path("html_url").asText(null),
                n.path("created_at").asText(null));
    }

    // --------------------------------------------------------------------- helpers

    private void requireToken() {
        if (!props.isConfigured()) {
            throw new IllegalStateException("GitHub token is not configured (set GITHUB_TOKEN).");
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        HttpRequest request = builder
                .header("Authorization", "Bearer " + props.getToken())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .timeout(Duration.ofSeconds(30))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("GitHub API call failed: " + e.getMessage(), e);
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize GitHub request body", e);
        }
    }

    private JsonNode readTree(String body) {
        try {
            return mapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse GitHub response", e);
        }
    }
}
