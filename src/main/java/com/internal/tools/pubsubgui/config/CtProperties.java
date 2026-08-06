package com.internal.tools.pubsubgui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-environment commercetools (CT) connection catalog used by the CT Data Explorer. Each
 * environment (Dev / QA / Perf) carries its own CT project + OAuth client credentials. The tool
 * only reads from CT (GraphQL) — it never mutates products.
 *
 * <p>Credentials are blank by default and supplied via env vars, mirroring the reference project
 * (tb-catalog-data-processor) which never commits secrets. {@code authUrl}/{@code apiUrl} default to
 * the US-central GCP CT endpoints.
 */
@Component
@ConfigurationProperties(prefix = "ct")
public class CtProperties {

    /** Ordered environments surfaced in the UI, each with its own CT project/credentials. */
    private List<Environment> environments = new ArrayList<>();

    public List<Environment> getEnvironments() {
        return environments;
    }

    public void setEnvironments(List<Environment> environments) {
        this.environments = environments == null ? new ArrayList<>() : environments;
    }

    /** Returns the environment by name, or {@code null} if unknown. */
    public Environment environment(String name) {
        if (name == null) {
            return null;
        }
        for (Environment env : environments) {
            if (env.getName().equalsIgnoreCase(name.trim())) {
                return env;
            }
        }
        return null;
    }

    /** A named environment with its CT project key + OAuth client credentials + endpoints. */
    public static class Environment {
        private String name = "";
        private String projectKey = "";
        private String clientId = "";
        private String clientSecret = "";
        private String authUrl = "https://auth.us-central1.gcp.commercetools.com";
        private String apiUrl = "https://api.us-central1.gcp.commercetools.com";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public String getProjectKey() {
            return projectKey;
        }

        public void setProjectKey(String projectKey) {
            this.projectKey = projectKey == null ? "" : projectKey.trim();
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId == null ? "" : clientId.trim();
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        }

        public String getAuthUrl() {
            return authUrl;
        }

        public void setAuthUrl(String authUrl) {
            this.authUrl = (authUrl == null || authUrl.isBlank())
                    ? "https://auth.us-central1.gcp.commercetools.com" : authUrl.trim();
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = (apiUrl == null || apiUrl.isBlank())
                    ? "https://api.us-central1.gcp.commercetools.com" : apiUrl.trim();
        }

        /** True when project key + client id + secret are all present (feature usable). */
        public boolean isConfigured() {
            return !projectKey.isBlank() && !clientId.isBlank() && !clientSecret.isBlank();
        }
    }
}
