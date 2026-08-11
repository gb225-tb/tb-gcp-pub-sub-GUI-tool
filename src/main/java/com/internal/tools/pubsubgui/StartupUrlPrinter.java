package com.internal.tools.pubsubgui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * Prints the ready-to-open application URL(s) once the reactive server has started, so the operator
 * doesn't have to reconstruct the port + context path from config.
 */
@Component
public class StartupUrlPrinter implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupUrlPrinter.class);

    private final Environment env;

    public StartupUrlPrinter(Environment env) {
        this.env = env;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String port = env.getProperty("local.server.port",
                env.getProperty("server.port", "8080"));
        String basePath = normalize(env.getProperty("spring.webflux.base-path", ""));
        String app = env.getProperty("spring.application.name", "application");

        String local = "http://localhost:" + port + basePath;
        String network = null;
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            if (host != null && !host.isBlank()) {
                network = "http://" + host + ":" + port + basePath;
            }
        } catch (Exception ignore) {
            // best-effort; local URL is always printed
        }

        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator());
        sb.append("  ────────────────────────────────────────────────────────").append(System.lineSeparator());
        sb.append("  ").append(app).append(" is ready").append(System.lineSeparator());
        sb.append("  Local:   ").append(local).append(System.lineSeparator());
        if (network != null && !network.equals(local)) {
            sb.append("  Network: ").append(network).append(System.lineSeparator());
        }
        sb.append("  ────────────────────────────────────────────────────────");
        log.info(sb.toString());
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
