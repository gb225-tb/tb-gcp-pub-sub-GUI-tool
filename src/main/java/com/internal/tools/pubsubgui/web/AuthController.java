package com.internal.tools.pubsubgui.web;

import com.google.auth.oauth2.GoogleCredentials;
import com.internal.tools.pubsubgui.config.PubSubClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives the Application Default Credentials (ADC) sign-in flow for the UI.
 *
 * <p>When the tool runs locally (the common internal case) and ADC is missing,
 * the UI can ask this controller to run {@code gcloud auth application-default
 * login}. gcloud opens the user's browser for Google sign-in; once it completes
 * the credentials land on disk and the tool's auth gate (which polls
 * {@code /api/auth/status}) lets the user back into the tool.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final PubSubClientFactory clients;
    private final AtomicBoolean loginRunning = new AtomicBoolean(false);
    private volatile String lastError;

    public AuthController(PubSubClientFactory clients) {
        this.clients = clients;
    }

    @GetMapping("/status")
    public Mono<Map<String, Object>> status() {
        return Mono.fromCallable(() -> {
            boolean emulator = clients.isEmulator();
            boolean authenticated = emulator || hasApplicationDefaultCredentials();
            boolean loginAvailable = !emulator && clients.properties().isAllowGcloudLogin();

            Map<String, Object> out = new HashMap<>();
            out.put("authenticated", authenticated);
            out.put("emulator", emulator);
            out.put("loginAvailable", loginAvailable);
            out.put("loginInProgress", loginRunning.get());
            out.put("lastError", lastError);
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/login")
    public Mono<Map<String, Object>> login() {
        return Mono.fromCallable(() -> {
            if (clients.isEmulator()) {
                return Map.<String, Object>of("started", false, "message", "Emulator mode — no sign-in required.");
            }
            if (!clients.properties().isAllowGcloudLogin()) {
                return Map.<String, Object>of("started", false,
                        "message", "In-app sign-in is disabled. Run 'gcloud auth application-default login' manually.");
            }
            if (!loginRunning.compareAndSet(false, true)) {
                return Map.<String, Object>of("started", true, "message", "Sign-in already in progress.");
            }
            lastError = null;
            startGcloudLogin();
            return Map.<String, Object>of("started", true,
                    "message", "A browser window should open for Google sign-in. Complete it, then return here.");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void startGcloudLogin() {
        Thread t = new Thread(() -> {
            try {
                log.info("Launching 'gcloud auth application-default login'…");
                Process p = new ProcessBuilder("gcloud", "auth", "application-default", "login")
                        .inheritIO()
                        .start();
                int code = p.waitFor();
                if (code != 0) {
                    lastError = "gcloud exited with code " + code;
                    log.warn("gcloud ADC login exited with code {}", code);
                } else {
                    log.info("gcloud ADC login completed.");
                }
            } catch (IOException e) {
                lastError = "Could not run gcloud — is the Google Cloud SDK installed and on PATH? (" + e.getMessage() + ")";
                log.warn("Failed to launch gcloud", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "Sign-in was interrupted.";
            } finally {
                loginRunning.set(false);
            }
        }, "gcloud-adc-login");
        t.setDaemon(true);
        t.start();
    }

    private boolean hasApplicationDefaultCredentials() {
        try {
            GoogleCredentials.getApplicationDefault();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
