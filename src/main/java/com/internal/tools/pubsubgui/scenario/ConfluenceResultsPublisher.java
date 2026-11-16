package com.internal.tools.pubsubgui.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internal.tools.pubsubgui.automation.model.RunSummary;
import com.internal.tools.pubsubgui.automation.model.ScenarioResult;
import com.internal.tools.pubsubgui.scenario.config.ConfluenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Creates or updates the RFW-12316 results page under the configured Confluence folder.
 * Returns null when credentials are not set, so a run still finishes without them.
 */
@Component
public class ConfluenceResultsPublisher {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceResultsPublisher.class);

    private final ConfluenceProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private String status = "Confluence credentials are not configured";

    public ConfluenceResultsPublisher(ConfluenceProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public String status() {
        return status;
    }

    /** Page URL, or null when publish was skipped or failed. */
    public String publish(RunSummary summary) {
        if (!props.configured()) {
            status = "Confluence credentials are not configured";
            return null;
        }
        try {
            String title = props.getPageTitle();
            String body = pageBody(summary);
            JsonNode existing = findPage(title);
            JsonNode saved;
            if (existing == null) {
                saved = createPage(title, body);
            } else {
                saved = updatePage(existing, title, body);
            }
            String link = saved.path("_links").path("webui").asText("");
            if (link.isBlank()) {
                status = "Confluence accepted the page but returned no link";
                return props.getBaseUrl();
            }
            String url = props.getBaseUrl() + link;
            status = url;
            return url;
        } catch (Exception e) {
            log.warn("confluence | publish failed | {}", e.getMessage());
            status = "Confluence publish failed: " + e.getMessage();
            return null;
        }
    }

    private JsonNode findPage(String title) throws Exception {
        String url = props.getBaseUrl() + "/rest/api/content?spaceKey="
                + encode(props.getSpaceKey()) + "&title=" + encode(title) + "&expand=version";
        JsonNode root = send("GET", url, null);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        return results.get(0);
    }

    private JsonNode createPage(String title, String body) throws Exception {
        String json = "{"
                + "\"type\":\"page\","
                + "\"title\":" + mapper.writeValueAsString(title) + ","
                + "\"space\":{\"key\":" + mapper.writeValueAsString(props.getSpaceKey()) + "},"
                + "\"ancestors\":[{\"id\":" + mapper.writeValueAsString(props.getParentId()) + "}],"
                + "\"body\":{\"storage\":{\"value\":" + mapper.writeValueAsString(body)
                + ",\"representation\":\"storage\"}}"
                + "}";
        try {
            return send("POST", props.getBaseUrl() + "/rest/api/content", json);
        } catch (IllegalStateException first) {
            String withoutParent = json.replace(
                    "\"ancestors\":[{\"id\":" + mapper.writeValueAsString(props.getParentId()) + "}],", "");
            log.warn("confluence | create under folder {} failed, retrying at the space root | {}",
                    props.getParentId(), first.getMessage());
            return send("POST", props.getBaseUrl() + "/rest/api/content", withoutParent);
        }
    }

    private JsonNode updatePage(JsonNode existing, String title, String body) throws Exception {
        int next = existing.path("version").path("number").asInt(1) + 1;
        String id = existing.path("id").asText();
        String json = "{"
                + "\"id\":" + mapper.writeValueAsString(id) + ","
                + "\"type\":\"page\","
                + "\"title\":" + mapper.writeValueAsString(title) + ","
                + "\"version\":{\"number\":" + next + "},"
                + "\"body\":{\"storage\":{\"value\":" + mapper.writeValueAsString(body)
                + ",\"representation\":\"storage\"}}"
                + "}";
        return send("PUT", props.getBaseUrl() + "/rest/api/content/" + id, json);
    }

    private JsonNode send(String method, String url, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", basic())
                .header("Accept", "application/json");
        if (json == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + trim(response.body()));
        }
        return mapper.readTree(response.body());
    }

    private String pageBody(RunSummary summary) {
        StringBuilder html = new StringBuilder();
        html.append("<p>RFW-12316 item ingestion results from Catalog Utility against Perf. ")
                .append("Run started ").append(escape(summary.startedAt()))
                .append(". ").append(summary.passed()).append(" passed, ")
                .append(summary.failed()).append(" failed, ")
                .append(summary.errored()).append(" errored.</p>");
        html.append("<table><tbody><tr>")
                .append("<th>Scenario</th><th>Result</th><th>Expected</th><th>Actual</th></tr>");
        for (ScenarioResult row : summary.results()) {
            html.append("<tr><td>").append(escape(row.scenario().id())).append(" ")
                    .append(escape(row.scenario().title())).append("</td><td>")
                    .append(escape(row.result().status().name())).append("</td><td>")
                    .append(escape(row.result().expected())).append("</td><td>")
                    .append(escape(firstNonBlank(row.result().actual(), row.result().message())))
                    .append("</td></tr>");
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    private String basic() {
        String raw = props.getEmail() + ":" + props.getApiToken();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b == null ? "" : b;
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
