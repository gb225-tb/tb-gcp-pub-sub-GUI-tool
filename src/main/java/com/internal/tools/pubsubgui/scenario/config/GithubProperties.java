package com.internal.tools.pubsubgui.scenario.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GitHub Actions credentials/config used to trigger batch Dataflow runs via {@code workflow_dispatch}.
 * The token is blank by default (batch scenarios are disabled in the UI until it is supplied); it
 * needs {@code actions:write} + {@code contents:read} on the processor repos.
 */
@Component
@ConfigurationProperties(prefix = "github")
public class GithubProperties {

    private String token = "";
    private String ref = "develop";
    private String apiBase = "https://api.github.com";

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token.trim();
    }

    public String getRef() {
        return ref;
    }

    public void setRef(String ref) {
        this.ref = ref == null || ref.isBlank() ? "develop" : ref.trim();
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase == null || apiBase.isBlank()
                ? "https://api.github.com" : apiBase.trim().replaceAll("/+$", "");
    }

    public boolean isConfigured() {
        return !token.isBlank();
    }
}
