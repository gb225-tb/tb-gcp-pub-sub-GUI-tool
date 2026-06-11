package com.internal.tools.pubsubgui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "pubsub")
public class PubSubProperties {

    /** Default project id used when a request does not specify one. */
    private String projectId = "";

    /** Emulator host (host:port). Empty means talk to real GCP. */
    private String emulatorHost = "";

    /**
     * The only topics this tool is allowed to see and operate on. When empty,
     * no restriction is applied (legacy behaviour). When set, the tool will
     * only ever list / publish / subscribe to these topic ids.
     */
    private List<String> allowedTopics = new ArrayList<>();

    /**
     * Ordered flow groups shown as tabs in the UI (e.g. Inbound,
     * Config to Runtime, Runtime to CT), each with its own list of topics.
     * Topics in any group are implicitly allowed.
     */
    private List<TopicGroup> topicGroups = new ArrayList<>();

    /**
     * When true, the tool may shell out to {@code gcloud auth application-default
     * login} on behalf of the user (only sensible for a locally-run instance).
     */
    private boolean allowGcloudLogin = true;

    /** A named, ordered group of topics surfaced as a UI tab. */
    public static class TopicGroup {
        private String name = "";
        private List<String> topics = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public List<String> getTopics() {
            return topics;
        }

        public void setTopics(List<String> topics) {
            this.topics = topics == null ? new ArrayList<>() : topics.stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(String::trim)
                    .toList();
        }
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId == null ? "" : projectId.trim();
    }

    public String getEmulatorHost() {
        return emulatorHost;
    }

    public void setEmulatorHost(String emulatorHost) {
        this.emulatorHost = emulatorHost == null ? "" : emulatorHost.trim();
    }

    public boolean isEmulator() {
        return !emulatorHost.isEmpty();
    }

    public List<String> getAllowedTopics() {
        return allowedTopics;
    }

    public void setAllowedTopics(List<String> allowedTopics) {
        this.allowedTopics = allowedTopics == null ? new ArrayList<>() : allowedTopics.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(String::trim)
                .toList();
    }

    public List<TopicGroup> getTopicGroups() {
        return topicGroups;
    }

    public void setTopicGroups(List<TopicGroup> topicGroups) {
        this.topicGroups = topicGroups == null ? new ArrayList<>() : topicGroups;
    }

    /** All allowed topics: the explicit allow-list plus every grouped topic, de-duplicated and ordered. */
    public List<String> allTopics() {
        LinkedHashSet<String> all = new LinkedHashSet<>(allowedTopics);
        for (TopicGroup group : topicGroups) {
            all.addAll(group.getTopics());
        }
        return new ArrayList<>(all);
    }

    public boolean isAllowGcloudLogin() {
        return allowGcloudLogin;
    }

    public void setAllowGcloudLogin(boolean allowGcloudLogin) {
        this.allowGcloudLogin = allowGcloudLogin;
    }

    public boolean isRestricted() {
        return !allowedTopics.isEmpty() || !topicGroups.isEmpty();
    }

    public boolean isTopicAllowed(String topicId) {
        if (!isRestricted()) {
            return true;
        }
        return allTopics().contains(topicId);
    }
}
