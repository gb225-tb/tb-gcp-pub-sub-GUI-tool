package com.internal.tools.pubsubgui.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralizes creation of MongoDB clients for the configured connections. One
 * {@link MongoClient} is created and cached per unique connection URI; the
 * target database is resolved from the database name embedded in the URI.
 */
@Component
public class MongoClientFactory {

    private static final Logger log = LoggerFactory.getLogger(MongoClientFactory.class);

    private final MongoProperties properties;

    /** One cached client per connection URI. */
    private final Map<String, MongoClient> clients = new ConcurrentHashMap<>();

    public MongoClientFactory(MongoProperties properties) {
        this.properties = properties;
    }

    public MongoProperties properties() {
        return properties;
    }

    /** Resolve the {@link MongoDatabase} for a configured environment + database. */
    public MongoDatabase database(String environment, String database) {
        String uri = properties.resolveUri(environment, database);
        ConnectionString connectionString = new ConnectionString(uri);
        String dbName = connectionString.getDatabase();
        if (dbName == null || dbName.isBlank()) {
            throw new IllegalStateException(
                    "Connection URI for environment '" + environment + "' / database '"
                            + database + "' does not include a database name.");
        }
        return client(uri, connectionString).getDatabase(dbName);
    }

    private MongoClient client(String uri, ConnectionString connectionString) {
        return clients.computeIfAbsent(uri, key -> {
            log.info("Creating MongoClient for host(s) {} / db {}",
                    connectionString.getHosts(), connectionString.getDatabase());
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(connectionString)
                    .build();
            return MongoClients.create(settings);
        });
    }

    @PreDestroy
    public void shutdown() {
        for (MongoClient client : clients.values()) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing MongoClient", e);
            }
        }
        clients.clear();
    }
}
