package com.internal.tools.pubsubgui.automation.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Automation view's AI failure analysis. Everything is blank by default and
 * env-var overridable — no secrets are committed. When no provider is usable, the analyzer falls
 * back to an offline heuristic explanation.
 *
 * <ul>
 *   <li>{@code provider}: {@code openai} | {@code cursor} | {@code heuristic}.</li>
 *   <li>OpenAI-compatible: {@code base-url} (e.g. https://api.openai.com/v1), {@code api-key}, {@code model}.</li>
 *   <li>Cursor: {@code cursor-api-key} (Dashboard -> Integrations) used against the Cloud Agents REST API.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "automation.ai")
public class AiProperties {

    private String provider = "heuristic";
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "gpt-4o-mini";
    private String cursorApiKey = "";
    private String cursorBaseUrl = "https://api.cursor.com";
    /** Optional Cursor model id (see GET /v1/models). Blank = let Cursor pick the default. */
    private String cursorModel = "";
    private int timeoutSeconds = 30;
    /** Max wall-clock seconds to wait for a Cursor cloud-agent run to reach a terminal state. */
    private int cursorMaxWaitSeconds = 150;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider == null || provider.isBlank() ? "heuristic" : provider.trim().toLowerCase();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null || model.isBlank() ? "gpt-4o-mini" : model.trim();
    }

    public String getCursorApiKey() {
        return cursorApiKey;
    }

    public void setCursorApiKey(String cursorApiKey) {
        this.cursorApiKey = cursorApiKey == null ? "" : cursorApiKey.trim();
    }

    public String getCursorBaseUrl() {
        return cursorBaseUrl;
    }

    public void setCursorBaseUrl(String cursorBaseUrl) {
        this.cursorBaseUrl = cursorBaseUrl == null || cursorBaseUrl.isBlank()
                ? "https://api.cursor.com" : cursorBaseUrl.trim();
    }

    public String getCursorModel() {
        return cursorModel;
    }

    public void setCursorModel(String cursorModel) {
        this.cursorModel = cursorModel == null ? "" : cursorModel.trim();
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
    }

    public int getCursorMaxWaitSeconds() {
        return cursorMaxWaitSeconds;
    }

    public void setCursorMaxWaitSeconds(int cursorMaxWaitSeconds) {
        this.cursorMaxWaitSeconds = cursorMaxWaitSeconds <= 0 ? 150 : cursorMaxWaitSeconds;
    }

    /** True when the OpenAI-compatible provider has the minimum config to make a call. */
    public boolean openAiUsable() {
        return "openai".equals(provider) && !baseUrl.isBlank() && !apiKey.isBlank();
    }

    /** True when the Cursor provider has an API key configured. */
    public boolean cursorUsable() {
        return "cursor".equals(provider) && !cursorApiKey.isBlank();
    }

    /** The effective provider after accounting for missing credentials (falls back to heuristic). */
    public String effectiveProvider() {
        if (openAiUsable()) {
            return "openai";
        }
        if (cursorUsable()) {
            return "cursor";
        }
        return "heuristic";
    }
}
