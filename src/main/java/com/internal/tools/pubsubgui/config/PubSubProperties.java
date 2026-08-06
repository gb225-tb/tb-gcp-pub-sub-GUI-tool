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
     * Ordered environments (Dev/QA/Perf) shown in the Pub/Sub view's environment
     * dropdown. Selecting one switches the active GCP project and reveals its
     * Inbound / Config / Runtime topic groups. Every topic in every environment
     * is implicitly allowed.
     */
    private List<Environment> environments = new ArrayList<>();

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

    /** A named environment with its own GCP project and ordered topic groups. */
    public static class Environment {
        private String name = "";
        private String projectId = "";
        private List<TopicGroup> topicGroups = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId == null ? "" : projectId.trim();
        }

        public List<TopicGroup> getTopicGroups() {
            return topicGroups;
        }

        public void setTopicGroups(List<TopicGroup> topicGroups) {
            this.topicGroups = topicGroups == null ? new ArrayList<>() : topicGroups;
        }

        /** Every topic across this environment's groups, de-duplicated and ordered. */
        public List<String> topics() {
            LinkedHashSet<String> all = new LinkedHashSet<>();
            for (TopicGroup group : topicGroups) {
                all.addAll(group.getTopics());
            }
            return new ArrayList<>(all);
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

    public List<Environment> getEnvironments() {
        return environments;
    }

    public void setEnvironments(List<Environment> environments) {
        this.environments = environments == null ? new ArrayList<>() : environments;
    }

    /**
     * All allowed topics: the explicit allow-list, every legacy grouped topic and
     * every topic from every environment, de-duplicated and ordered.
     */
    public List<String> allTopics() {
        LinkedHashSet<String> all = new LinkedHashSet<>(allowedTopics);
        for (TopicGroup group : topicGroups) {
            all.addAll(group.getTopics());
        }
        for (Environment env : environments) {
            all.addAll(env.topics());
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
        return !allowedTopics.isEmpty() || !topicGroups.isEmpty() || !environments.isEmpty();
    }

    public boolean isTopicAllowed(String topicId) {
        if (!isRestricted()) {
            return true;
        }
        return allTopics().contains(topicId);
    }
}
