package com.internal.tools.pubsubgui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-environment Constructor (constructor.io) connection catalog used by the Categories view to
 * count products in a category via the Browse API. Mirrors the {@code constructor:} block in
 * tb-catalog-data-processor: {@code apiUrl} defaults to the public host, while {@code indexKey} and
 * {@code authToken} are blank by default and supplied via env vars (secrets are never committed).
 *
 * <p>The tool only reads from Constructor — it never mutates the index.
 */
@Component
@ConfigurationProperties(prefix = "constructor")
public class ConstructorProperties {

    /** Ordered environments surfaced in the UI, each with its own Constructor index credentials. */
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

    /** A named environment with its Constructor index key + API token + endpoints. */
    public static class Environment {
        private String name = "";
        private String apiUrl = "https://ac.cnstrc.com";
        private String indexKey = "";
        private String authToken = "";
        /** Browse-by-group path; the category id is appended (e.g. /browse/group_id/{id}). */
        private String browsePath = "/browse/group_id";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = (apiUrl == null || apiUrl.isBlank()) ? "https://ac.cnstrc.com" : apiUrl.trim();
        }

        public String getIndexKey() {
            return indexKey;
        }

        public void setIndexKey(String indexKey) {
            this.indexKey = indexKey == null ? "" : indexKey.trim();
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken == null ? "" : authToken.trim();
        }

        public String getBrowsePath() {
            return browsePath;
        }

        public void setBrowsePath(String browsePath) {
            this.browsePath = (browsePath == null || browsePath.isBlank()) ? "/browse/group_id" : browsePath.trim();
        }

        /** True when an index key is present (Browse API requires at least the key). */
        public boolean isConfigured() {
            return !indexKey.isBlank();
        }
    }
}
