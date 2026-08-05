package com.internal.tools.pubsubgui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB connection catalog used by the Mongo Compare view. Each environment
 * (e.g. Dev / QA / Perf) has a set of named logical databases (e.g. item-config,
 * item-runtime), and each maps to a single connection URI. The physical database
 * name is embedded in the URI itself.
 */
@Component
@ConfigurationProperties(prefix = "mongo")
public class MongoProperties {

    /** Ordered environments surfaced in the UI, each with its own databases. */
    private List<Environment> environments = new ArrayList<>();

    /** Collection groups shown in the Product Clean Up view (same for all envs). */
    private List<CleanupGroup> productCleanup = new ArrayList<>();

    /** A named environment (e.g. Dev) with its list of logical databases. */
    public static class Environment {
        private String name = "";
        private List<Database> databases = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public List<Database> getDatabases() {
            return databases;
        }

        public void setDatabases(List<Database> databases) {
            this.databases = databases == null ? new ArrayList<>() : databases;
        }
    }

    /** A named logical database mapped to a MongoDB connection URI. */
    public static class Database {
        private String name = "";
        private String uri = "";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.trim();
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri == null ? "" : uri.trim();
        }
    }

    /**
     * A labelled group of collections in one logical database that a product is
     * seeded into and can be purged from by productId (e.g. Config -> item-config).
     */
    public static class CleanupGroup {
        private String label = "";
        private String database = "";
        private List<String> collections = new ArrayList<>();

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label == null ? "" : label.trim();
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database == null ? "" : database.trim();
        }

        public List<String> getCollections() {
            return collections;
        }

        public void setCollections(List<String> collections) {
            this.collections = collections == null ? new ArrayList<>() : collections.stream()
                    .filter(c -> c != null && !c.isBlank())
                    .map(String::trim)
                    .toList();
        }
    }

    public List<Environment> getEnvironments() {
        return environments;
    }

    public void setEnvironments(List<Environment> environments) {
        this.environments = environments == null ? new ArrayList<>() : environments;
    }

    public List<CleanupGroup> getProductCleanup() {
        return productCleanup;
    }

    public void setProductCleanup(List<CleanupGroup> productCleanup) {
        this.productCleanup = productCleanup == null ? new ArrayList<>() : productCleanup;
    }

    /** Resolve the connection URI for a given environment + database name. */
    public String resolveUri(String environment, String database) {
        for (Environment env : environments) {
            if (env.getName().equalsIgnoreCase(environment)) {
                for (Database db : env.getDatabases()) {
                    if (db.getName().equalsIgnoreCase(database)) {
                        return db.getUri();
                    }
                }
            }
        }
        throw new IllegalArgumentException(
                "No Mongo connection configured for environment '" + environment
                        + "' and database '" + database + "'.");
    }
}
