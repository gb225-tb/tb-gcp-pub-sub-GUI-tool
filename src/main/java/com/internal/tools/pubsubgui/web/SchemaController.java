package com.internal.tools.pubsubgui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the message JSON Schemas (draft-07) bundled under
 * {@code classpath:/schemas/} in a simplified, flattened shape the Bulk-Posting
 * UI uses to coerce each column to its declared type. This is what keeps the
 * tool from turning schema-declared strings (e.g. Division, ProductColorCode,
 * the *Flag fields) into integers, which the Dataflow consumer rejects.
 */
@RestController
@RequestMapping("/api/schemas")
public class SchemaController {

    private final ObjectMapper mapper = new ObjectMapper();
    private List<Map<String, Object>> cache = List.of();

    @PostConstruct
    void load() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:/schemas/*.json");
            for (Resource r : resources) {
                String filename = r.getFilename();
                if (filename == null) {
                    continue;
                }
                try (InputStream in = r.getInputStream()) {
                    JsonNode root = mapper.readTree(in);
                    out.add(describe(filename.replaceFirst("\\.json$", ""), root));
                } catch (Exception ignore) {
                    // Skip an unreadable/invalid schema rather than failing startup.
                }
            }
        } catch (Exception ignore) {
            // No schemas directory on the classpath — leave the cache empty.
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("title")).toLowerCase()));
        this.cache = out;
    }

    /** All bundled schemas, flattened to {@code {id,title,coercible,fields[...]}}. */
    @GetMapping
    public List<Map<String, Object>> list() {
        return cache;
    }

    // Flatten one JSON Schema into the shape the client understands.
    private Map<String, Object> describe(String id, JsonNode root) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("id", id);
        schema.put("title", text(root, "title", id));
        schema.put("description", text(root, "description", ""));

        List<String> required = new ArrayList<>();
        JsonNode req = root.get("required");
        if (req != null && req.isArray()) {
            req.forEach(n -> required.add(n.asText()));
        }
        schema.put("required", required);

        JsonNode addl = root.get("additionalProperties");
        schema.put("additionalProperties", addl == null || !addl.isBoolean() || addl.asBoolean());

        JsonNode props = root.get("properties");
        boolean coercible = props != null && props.isObject() && "object".equals(text(root, "type", ""));
        List<Map<String, Object>> fields = new ArrayList<>();
        if (coercible) {
            props.fields().forEachRemaining(entry -> fields.add(field(entry.getKey(), entry.getValue(), required)));
        }
        schema.put("coercible", coercible);
        schema.put("fields", fields);
        return schema;
    }

    private Map<String, Object> field(String name, JsonNode def, List<String> required) {
        List<String> types = new ArrayList<>();
        JsonNode t = def.get("type");
        if (t != null) {
            if (t.isTextual()) {
                types.add(t.asText());
            } else if (t.isArray()) {
                t.forEach(n -> types.add(n.asText()));
            }
        }
        boolean nullable = types.remove("null");

        List<String> enumVals = null;
        JsonNode en = def.get("enum");
        if (en != null && en.isArray()) {
            enumVals = new ArrayList<>();
            for (JsonNode n : en) {
                if (!n.isNull()) {
                    enumVals.add(n.asText());
                } else {
                    nullable = true;
                }
            }
        }

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("types", types);
        f.put("nullable", nullable);
        f.put("required", required.contains(name));
        f.put("enum", enumVals);
        return f;
    }

    private static String text(JsonNode node, String key, String def) {
        JsonNode v = node.get(key);
        return v != null && v.isTextual() ? v.asText() : def;
    }
}
