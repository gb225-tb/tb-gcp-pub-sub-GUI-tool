package com.internal.tools.pubsubgui.hcl.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Loads the classpath HCL YAML files into their typed POJOs:
 * {@code hcl/hcl-queries.yml} -&gt; {@link HclConfig.Queries},
 * {@code hcl/hcl-attributes.yml} -&gt; {@link HclConfig.AttributeMappings}.
 *
 * <p>Ported from tb-catalog-data-processor. The GUI tool supplies the per-environment
 * {@link HclConfig} (DB2 + migration literals) from {@code application.yml} instead of a migration YAML.
 */
public final class HclConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(HclConfigLoader.class);

    public static final String QUERIES_RESOURCE = "hcl/hcl-queries.yml";
    public static final String ATTRIBUTES_RESOURCE = "hcl/hcl-attributes.yml";

    private HclConfigLoader() {
    }

    public static HclConfig.Queries loadQueries() {
        return loadFromClasspath(QUERIES_RESOURCE, HclConfig.Queries.class);
    }

    public static HclConfig.AttributeMappings loadAttributes() {
        return loadFromClasspath(ATTRIBUTES_RESOURCE, HclConfig.AttributeMappings.class);
    }

    private static <T> T loadFromClasspath(String resourceName, Class<T> type) {
        InputStream input = classpath(resourceName);
        if (Objects.isNull(input)) {
            throw new IllegalArgumentException("HCL YAML resource not found on classpath: " + resourceName);
        }
        return parse(input, resourceName, type);
    }

    private static InputStream classpath(String resourceName) {
        return HclConfigLoader.class.getClassLoader().getResourceAsStream(resourceName);
    }

    private static <T> T parse(InputStream input, String source, Class<T> type) {
        try (InputStream in = input) {
            Yaml yaml = new Yaml(new Constructor(type, new LoaderOptions()));
            T config = yaml.load(in);
            if (Objects.isNull(config)) {
                throw new IllegalStateException("Parsed HCL YAML is null — check file: " + source);
            }
            log.info("hcl config loaded | source={} | type={}", source, type.getSimpleName());
            return config;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read HCL YAML from: " + source, e);
        } catch (RuntimeException e) {
            throw new RuntimeException("Failed to parse HCL YAML from: " + source, e);
        }
    }
}
