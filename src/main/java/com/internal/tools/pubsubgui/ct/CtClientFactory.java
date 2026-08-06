package com.internal.tools.pubsubgui.ct;

import com.commercetools.api.client.ProjectApiRoot;
import com.commercetools.api.defaultconfig.ApiRootBuilder;
import com.internal.tools.pubsubgui.config.CtProperties;
import io.vrap.rmf.base.client.oauth2.ClientCredentials;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and caches one {@link ProjectApiRoot} per environment for the CT Data Explorer. Ports the
 * client-build logic from tb-catalog-data-processor's {@code CommerceToolsClientFactory}:
 * {@link ClientCredentials} + {@link ApiRootBuilder#defaultClient} against {@code authUrl+/oauth/token}
 * and {@code apiUrl}, with a transport retry middleware for 429/5xx.
 */
@Component
public class CtClientFactory {

    private static final String OAUTH_TOKEN_PATH = "/oauth/token";

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_DELAY_MS = 200L;
    private static final long MAX_DELAY_MS = 10_000L;
    private static final List<Integer> RETRYABLE_STATUS_CODES = List.of(429, 500, 502, 503, 504);
    private static final List<Class<? extends Throwable>> RETRYABLE_EXCEPTIONS =
            List.of(java.io.IOException.class, java.util.concurrent.TimeoutException.class);

    private final CtProperties properties;
    private final Map<String, ProjectApiRoot> clients = new ConcurrentHashMap<>();

    public CtClientFactory(CtProperties properties) {
        this.properties = properties;
    }

    /** Returns the (cached) CT client for the environment, validating that it is configured. */
    public ProjectApiRoot clientFor(CtProperties.Environment env) {
        if (!env.isConfigured()) {
            throw new IllegalStateException(
                    "CommerceTools is not configured for environment '" + env.getName()
                            + "' — set the CT_" + env.getName().toUpperCase()
                            + "_PROJECT_KEY / CLIENT_ID / CLIENT_SECRET env vars.");
        }
        return clients.computeIfAbsent(env.getName(), k -> build(env));
    }

    private ProjectApiRoot build(CtProperties.Environment env) {
        ClientCredentials credentials = ClientCredentials.of()
                .withClientId(env.getClientId())
                .withClientSecret(env.getClientSecret())
                .build();

        return ApiRootBuilder.of()
                .defaultClient(credentials, tokenUrl(env.getAuthUrl()), env.getApiUrl())
                .withRetryMiddleware(MAX_RETRIES, INITIAL_DELAY_MS, MAX_DELAY_MS, RETRYABLE_STATUS_CODES,
                        RETRYABLE_EXCEPTIONS, builder -> builder)
                .build(env.getProjectKey());
    }

    /** Appends {@code /oauth/token} to the auth base, tolerating a trailing slash or existing path. */
    public static String tokenUrl(String authUrl) {
        if (Objects.isNull(authUrl) || authUrl.isBlank()) {
            return authUrl;
        }
        String base = authUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith(OAUTH_TOKEN_PATH) ? base : base + OAUTH_TOKEN_PATH;
    }
}
