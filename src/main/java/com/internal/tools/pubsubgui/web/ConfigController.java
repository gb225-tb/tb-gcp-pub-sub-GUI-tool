package com.internal.tools.pubsubgui.web;

import com.internal.tools.pubsubgui.config.PubSubClientFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final PubSubClientFactory clients;

    public ConfigController(PubSubClientFactory clients) {
        this.clients = clients;
    }

    @GetMapping
    public Mono<Map<String, Object>> config() {
        return Mono.fromCallable(() -> {
            Map<String, Object> out = new HashMap<>();
            String defaultProject = "";
            try {
                defaultProject = clients.resolveProjectId(null);
            } catch (RuntimeException ignored) {
                // no default available; UI will require the user to enter one
            }
            out.put("defaultProjectId", defaultProject);
            out.put("emulator", clients.isEmulator());
            out.put("emulatorHost", clients.getEmulatorHost());
            out.put("restricted", clients.properties().isRestricted());
            out.put("allowedTopics", clients.properties().getAllowedTopics());
            out.put("topicGroups", clients.properties().getTopicGroups().stream()
                    .map(g -> Map.of("name", g.getName(), "topics", g.getTopics()))
                    .toList());
            return out;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
