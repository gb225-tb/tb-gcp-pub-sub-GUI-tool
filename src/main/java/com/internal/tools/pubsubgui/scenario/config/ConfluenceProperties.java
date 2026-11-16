package com.internal.tools.pubsubgui.scenario.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Confluence target for the RFW-12316 results page. Email and API token come from the environment
 * or the local secrets file only (never committed). Page is created under {@code parentId} in
 * {@code spaceKey} and updated in place on later runs.
 */
@Component
@ConfigurationProperties(prefix = "confluence")
public class ConfluenceProperties {

    private String baseUrl = "https://tailored-prod.atlassian.net/wiki";
    private String spaceKey = "2026Replatform";
    private String parentId = "1116504070";
    private String pageTitle = "RFW-12316 Item ingestion scenario results";
    private String email = "";
    private String apiToken = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://tailored-prod.atlassian.net/wiki" : baseUrl.trim().replaceAll("/+$", "");
    }

    public String getSpaceKey() {
        return spaceKey;
    }

    public void setSpaceKey(String spaceKey) {
        this.spaceKey = spaceKey == null || spaceKey.isBlank() ? "2026Replatform" : spaceKey.trim();
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId == null || parentId.isBlank() ? "1116504070" : parentId.trim();
    }

    public String getPageTitle() {
        return pageTitle;
    }

    public void setPageTitle(String pageTitle) {
        this.pageTitle = pageTitle == null || pageTitle.isBlank()
                ? "RFW-12316 Item ingestion scenario results" : pageTitle.trim();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email == null ? "" : email.trim();
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken == null ? "" : apiToken.trim();
    }

    public boolean configured() {
        return !email.isBlank() && !apiToken.isBlank();
    }
}
