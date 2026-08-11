package com.internal.tools.pubsubgui.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebFilter;

import java.net.URI;

/**
 * Serves the SPA correctly when the app runs under a context path. Spring's welcome-page handler only
 * matches the base path <em>with</em> a trailing slash (e.g. {@code /catalog-pubsub-gui/}); a request to
 * the bare base path ({@code /catalog-pubsub-gui}) otherwise falls through to the static resource
 * handler and 404s with "No static resource .". This filter 302-redirects the bare base path to the
 * trailing-slash form so opening either URL loads the UI.
 */
@Configuration
public class SpaRoutingConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public WebFilter basePathTrailingSlashRedirect(Environment env) {
        String base = normalize(env.getProperty("spring.webflux.base-path", ""));
        return (exchange, chain) -> {
            if (!base.isEmpty()) {
                String path = exchange.getRequest().getPath().value();
                if (path.equals(base)) {
                    exchange.getResponse().setStatusCode(HttpStatus.FOUND);
                    exchange.getResponse().getHeaders().setLocation(URI.create(base + "/"));
                    return exchange.getResponse().setComplete();
                }
            }
            return chain.filter(exchange);
        };
    }

    private static String normalize(String basePath) {
        if (basePath == null || basePath.isBlank() || "/".equals(basePath)) {
            return "";
        }
        String p = basePath.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
