/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.documentation;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.pretty.PrettyJsonMapper;
import io.yupiik.fusion.json.serialization.JsonCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.logging.Logger;

/**
 * Minisite {@code preAction} generating the JSON-Schema of the {@code configuration.json} file.
 * All {@code @RootConfiguration} of the documentation classloader are merged in a single schema,
 * command configurations (nested in their command class) being skipped, each root being nested
 * under its flattened key prefix ({@code yca} for {@code CodingAgentConfiguration},
 * {@code yca.tui} for {@code TuiConfiguration}), the schema modeling the whole file, i.e. the
 * {@code yca} root prefix being an object level of the schema, an optional string {@code $schema}
 * property being tolerated.
 * The typed schemas come from the fusion {@code META-INF/fusion/json/schemas.json} entries of the
 * {@code @JsonModel} configuration records, nested objects being discovered by following the
 * {@code $ref}s, and the documentation/defaults are merged from
 * {@code META-INF/fusion/configuration/documentation.json}.
 * Build tool only.
 */
public class ConfigurationJsonSchemaGenerator implements Runnable {
    private final Logger logger = Logger.getLogger(ConfigurationJsonSchemaGenerator.class.getName());

    private final Path sourceBase;
    private final Map<String, String> configuration;

    public ConfigurationJsonSchemaGenerator(final Path sourceBase, final Map<String, String> configuration) {
        this.sourceBase = sourceBase;
        this.configuration = configuration == null ? Map.of() : configuration;
    }

    @Override
    public void run() {
        final var loader = Thread.currentThread().getContextClassLoader();
        try (final var container = ConfiguringContainer.of()
                .register(new FusionModule() {
                    @Override
                    public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                        return (runtimeContainer, bean) -> isJsonType(bean.type());
                    }

                    @Override
                    public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                        return (runtimeContainer, listener) -> false;
                    }
                })
                .start()) {
            final var mapper = container.lookup(JsonMapper.class).instance();
            try (final var prettyMapper = new PrettyJsonMapper(mapper)) {
                final var schemas = new LinkedHashMap<String, Object>();
                resources(mapper, loader, "META-INF/fusion/json/schemas.json").forEach(resource -> {
                    if (resource.get("schemas") instanceof Map<?, ?> content) {
                        merge(schemas, content);
                    }
                });
                final var docs = new LinkedHashMap<String, Object>();
                final var roots = new LinkedHashSet<String>();
                resources(mapper, loader, "META-INF/fusion/configuration/documentation.json")
                        .forEach(resource -> {
                            if (resource.get("classes") instanceof Map<?, ?> content) {
                                merge(docs, content);
                            }
                            if (resource.get("roots") instanceof List<?> content) {
                                content.forEach(name -> roots.add(name.toString()));
                            }
                        });
                if (schemas.isEmpty() || docs.isEmpty()) {
                    throw new IllegalStateException(
                            "No fusion json schema/documentation found, compile first (mvn process-classes).");
                }

                final var kept = keptRoots(docs, roots);
                final var primary = kept.getFirst();
                final var defs = new LinkedHashMap<String, Object>();
                final var properties = new LinkedHashMap<String, Object>();
                final var peerProperties = new LinkedHashMap<String, Object>();
                for (int i = 0; i < kept.size(); i++) {
                    final var root = kept.get(i);
                    final var name = defName(root.fqcn());
                    if (i == 0) { // the primary root properties are the schema top level ones, no dedicated def
                        properties.putAll(map(convert(mapper, schemas, docs, root.fqcn(), defs, root.prefix())
                                .get("properties")));
                        continue;
                    }
                    if (!defs.containsKey(name)) {
                        defs.put(name, Map.of()); // reserve before recursing to keep discovery order and break cycles
                        defs.put(name, convert(mapper, schemas, docs, root.fqcn(), defs, root.prefix()));
                    }
                    if (!primary.prefix().isEmpty()
                            && !root.prefix().equals(primary.prefix())
                            && !root.prefix().startsWith(primary.prefix() + '.')) {
                        insert(
                                peerProperties,
                                List.of(root.prefix().split("\\.")),
                                Map.of("$ref", "#/$defs/" + name),
                                defs);
                        continue;
                    }
                    final var segments = relativeSegments(primary.prefix(), root.prefix());
                    final var rootProperties = map(map(defs.get(name)).get("properties"));
                    if (segments.isEmpty()) {
                        properties.putAll(rootProperties);
                    } else {
                        insert(properties, segments, Map.of("$ref", "#/$defs/" + name), defs);
                    }
                }

                final var schema = new LinkedHashMap<String, Object>();
                schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
                schema.put(
                        "$id",
                        configuration.getOrDefault(
                                "schemaId",
                                "https://yupiik.github.io/yupiik-ai/jsonschema/configuration.jsonschema.json"));
                schema.put("title", configuration.getOrDefault("title", "YCA configuration"));
                schema.put(
                        "description",
                        configuration.getOrDefault(
                                "description",
                                "Configuration file loaded from ~/.yupiik/coding-agent/configuration.json "
                                        + "(override the location with the yca.configuration.json key)."));
                schema.put("type", "object");
                final var rootProperties = new LinkedHashMap<String, Object>();
                if (primary.prefix().isEmpty()) {
                    rootProperties.putAll(properties);
                } else {
                    // The loader accepts both short and fully prefixed configuration keys.
                    rootProperties.putAll(properties);
                    final var wrapper = new LinkedHashMap<String, Object>();
                    wrapper.put("type", "object");
                    wrapper.put("properties", properties);
                    wrapper.put("additionalProperties", false);
                    insert(rootProperties, List.of(primary.prefix().split("\\.")), wrapper, defs);
                }
                rootProperties.putAll(peerProperties);
                rootProperties.put("$schema", Map.of("type", "string")); // editor hint key, tolerate it
                rootProperties.put(
                        "$inherit",
                        Map.of(
                                "type",
                                "boolean",
                                "description",
                                "Merge project configuration over the global configuration when true."));
                schema.put("properties", rootProperties);
                schema.put("additionalProperties", false);
                schema.put("$defs", defs);

                final var pretty = prettyMapper.toString(schema);
                // download artifact (site root) and copy included by configuration.adoc
                for (final var target : List.of(
                        configuration.getOrDefault("output", "assets/jsonschema/configuration.jsonschema.json"),
                        configuration.getOrDefault(
                                "partialOutput",
                                "content/coding-agent/_partials/generated/configuration.jsonschema.json"))) {
                    final var output = sourceBase.resolve(target);
                    Files.createDirectories(output.getParent());
                    Files.writeString(output, pretty);
                    logger.info(() -> "Generated '" + output + "'");
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Map<String, Object>> resources(
            final JsonMapper mapper, final ClassLoader loader, final String resource) throws IOException {
        final var urls = loader.getResources(resource);
        final var parsed = new ArrayList<Map<String, Object>>();
        while (urls.hasMoreElements()) {
            try (final var in = urls.nextElement().openStream()) {
                if (mapper.fromBytes(Object.class, in.readAllBytes()) instanceof Map<?, ?> content) {
                    parsed.add(map(content));
                }
            }
        }
        return parsed;
    }

    private List<Root> keptRoots(final Map<String, Object> docs, final Set<String> roots) {
        final var excluded = Arrays.stream(
                        configuration.getOrDefault("excludedRoots", "").split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
        final var kept = new ArrayList<Root>();
        for (final var fqcn : roots) {
            if (fqcn.contains("$") || excluded.contains(fqcn)) {
                continue; // command configurations are nested in their command class
            }
            if (!(docs.get(fqcn) instanceof List<?> docProperties)) {
                throw new IllegalStateException("Missing configuration documentation '" + fqcn + "'");
            }
            final var names = docProperties.stream()
                    .map(property -> propertyName((Map<?, ?>) property))
                    .toList();
            kept.add(new Root(fqcn, commonPrefix(names)));
        }
        if (kept.isEmpty()) {
            throw new IllegalStateException("No configuration root kept to generate the json schema.");
        }
        final var primaryRoot = configuration.getOrDefault(
                "primaryRoot", "io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration");
        kept.sort(Comparator.comparing((final Root root) -> !root.fqcn().equals(primaryRoot))
                .thenComparingInt(root -> segmentCount(root.prefix()))
                .thenComparing(Root::fqcn));
        return kept;
    }

    private Map<String, Object> convert(
            final JsonMapper mapper,
            final Map<String, Object> schemas,
            final Map<String, Object> docs,
            final String schemaKey,
            final Map<String, Object> defs,
            final String stripPrefix) {
        if (!(schemas.get(schemaKey) instanceof Map<?, ?> schema)) {
            throw new IllegalStateException("Missing json schema '" + schemaKey + "', is it a @JsonModel?");
        }
        if (!(docs.get(schemaKey) instanceof List<?> docProperties)) {
            throw new IllegalStateException("Missing configuration documentation '" + schemaKey + "'");
        }

        final var rewritten = rewrite(mapper, schemas, docs, defs, schema);
        rewritten.put("title", defName(schemaKey));
        final var properties = map(rewritten.get("properties"));
        for (final var raw : docProperties) {
            final var doc = (Map<?, ?>) raw;
            var name = propertyName(doc);
            if (stripPrefix != null) {
                if (!name.startsWith(stripPrefix + '.')) {
                    throw new IllegalStateException("Property '" + name + "' of '" + schemaKey
                            + "' does not start with the flattened prefix '" + stripPrefix + "'.");
                }
                name = name.substring(stripPrefix.length() + 1);
            }
            if (!(properties.get(name) instanceof Map<?, ?> value)) {
                throw new IllegalStateException("Property '" + name + "' of '" + schemaKey
                        + "' has no counterpart in the json model, align the json model on the configuration.");
            }
            final var property = map(value);
            property.put("description", doc.get("documentation"));
            final var component = componentType(
                    schemaKey, doc.containsKey("javaName") ? doc.get("javaName").toString() : name);
            // Custom configuration parsers accept aliases/case variants (and sometimes fallback
            // values), unlike the strict JSON enum codec. Keep canonical values as editor examples.
            if (component.isEnum()) {
                try {
                    component.getMethod("fromConfigurationString", String.class);
                    if (property.containsKey("enum")) {
                        property.put("examples", property.remove("enum"));
                    }
                } catch (final NoSuchMethodException ignored) {
                    // Ordinary enums use the strict generated configuration parser.
                }
            }
            defaultValue(mapper, doc.get("defaultValue"), property).ifPresent(v -> property.put("default", v));
        }
        return rewritten;
    }

    private Class<?> componentType(final String className, final String name) {
        var binaryName = className;
        while (true) {
            try {
                return Arrays.stream(Class.forName(
                                        binaryName,
                                        false,
                                        Thread.currentThread().getContextClassLoader())
                                .getRecordComponents())
                        .filter(component -> component.getName().equals(name))
                        .map(RecordComponent::getType)
                        .findFirst()
                        .orElse(Object.class);
            } catch (final ClassNotFoundException e) {
                // Processor metadata uses source names for nested classes, whereas the loader needs '$'.
                final var separator = binaryName.lastIndexOf('.');
                if (separator < 0) {
                    throw new IllegalStateException("Missing configuration class '" + className + "'", e);
                }
                binaryName = binaryName.substring(0, separator) + '$' + binaryName.substring(separator + 1);
            }
        }
    }

    private Map<String, Object> rewrite(
            final JsonMapper mapper,
            final Map<String, Object> schemas,
            final Map<String, Object> docs,
            final Map<String, Object> defs,
            final Map<?, ?> node) {
        final var result = new LinkedHashMap<String, Object>();
        node.forEach((key, value) -> {
            switch (key.toString()) {
                case "nullable", "$id" -> {}
                case "$ref" -> {
                    final var fqcn = value.toString().substring("#/schemas/".length());
                    final var name = defName(fqcn);
                    if (!defs.containsKey(name)) {
                        defs.put(name, Map.of()); // reserve before recursing to keep discovery order and break cycles
                        defs.put(name, convert(mapper, schemas, docs, fqcn, defs, null));
                    }
                    result.put("$ref", "#/$defs/" + name);
                }
                case "properties" -> {
                    final var properties = new LinkedHashMap<String, Object>();
                    map(value)
                            .forEach((name, property) ->
                                    properties.put(name, rewrite(mapper, schemas, docs, defs, map(property))));
                    result.put("properties", properties);
                }
                case "items" -> result.put("items", rewrite(mapper, schemas, docs, defs, map(value)));
                default -> result.put(key.toString(), value);
            }
        });
        // fusion marks double/long properties with $id=number but types doubles as integer
        if ("number".equals(node.get("$id")) && !"int64".equals(node.get("format"))) {
            result.put("type", "number");
        }
        // fusion binds Map<String, String> configuration entries from a single flat string parsed
        // as java.util.Properties (ConfigurationFactoryGenerator#mapOf), the JSON object form not
        // being consumable, so such properties are plain string in the schema, not objects.
        if ("object".equals(node.get("type"))
                && node.get("additionalProperties") instanceof Map<?, ?> additional
                && "string".equals(additional.get("type"))
                && !additional.containsKey("$ref")) {
            result.remove("additionalProperties");
            result.put("type", "string");
        }
        if ("object".equals(result.get("type"))) {
            result.put("additionalProperties", false);
        }
        return result;
    }

    private String defName(final String fqcn) {
        final var simple = fqcn.substring(Math.max(fqcn.lastIndexOf('.'), fqcn.lastIndexOf('$')) + 1);
        return simple.endsWith("Configuration") && simple.length() > "Configuration".length()
                ? simple.substring(0, simple.length() - "Configuration".length())
                : simple;
    }

    private String propertyName(final Map<?, ?> doc) {
        var name = doc.get("name").toString();
        if (name.startsWith("-.")) {
            name = name.substring("-.".length());
        }
        if (name.endsWith(".$index")) {
            name = name.substring(0, name.length() - ".$index".length());
        }
        return name;
    }

    private Optional<Object> defaultValue(
            final JsonMapper mapper, final Object value, final Map<String, Object> property) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Boolean || value instanceof Number) {
            return Optional.of(value);
        }
        if (value instanceof String string) {
            if (string.startsWith("\"") && string.endsWith("\"")) {
                return Optional.of(string.substring(1, string.length() - 1));
            }
            final var listPrefix = "java.util.List.of(";
            if (string.startsWith(listPrefix) && string.endsWith(")")) {
                try {
                    return Optional.of(mapper.fromString(
                            Object.class, '[' + string.substring(listPrefix.length(), string.length() - 1) + ']'));
                } catch (final RuntimeException e) {
                    // constructor-based defaults (eg java.util.List.of(new SomeConfiguration(...)))
                    // are valid configuration code but not JSON literals: emit no schema default
                    return Optional.empty();
                }
            }
            if (property.getOrDefault("enum", property.get("examples")) instanceof List<?> values
                    && string.contains(".")) {
                final var constant = string.substring(string.lastIndexOf('.') + 1);
                return values.contains(constant) ? Optional.of(constant) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private String commonPrefix(final List<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        var prefix = names.getFirst();
        for (final var name : names) {
            while (!name.equals(prefix) && !name.startsWith(prefix + '.')) {
                final var idx = prefix.lastIndexOf('.');
                if (idx < 0) {
                    return "";
                }
                prefix = prefix.substring(0, idx);
            }
        }
        for (final var name : names) { // the prefix must not consume a whole property name
            if (name.equals(prefix)) {
                final var idx = prefix.lastIndexOf('.');
                return idx < 0 ? "" : prefix.substring(0, idx);
            }
        }
        return prefix;
    }

    private int segmentCount(final String prefix) {
        return prefix.isEmpty() ? 0 : prefix.split("\\.", -1).length;
    }

    private List<String> relativeSegments(final String primaryPrefix, final String prefix) {
        if (prefix.isEmpty() || prefix.equals(primaryPrefix)) {
            return List.of();
        }
        if (!primaryPrefix.isEmpty() && prefix.startsWith(primaryPrefix + '.')) {
            return List.of(prefix.substring(primaryPrefix.length() + 1).split("\\."));
        }
        return List.of(prefix.split("\\."));
    }

    private void insert(
            final Map<String, Object> properties,
            final List<String> segments,
            final Map<String, Object> leaf,
            final Map<String, Object> defs) {
        var current = properties;
        for (final var segment : segments.subList(0, segments.size() - 1)) {
            var holder = current.containsKey(segment) ? map(current.get(segment)) : null;
            if (holder != null && holder.get("$ref") instanceof String ref && ref.startsWith("#/$defs/")) {
                // A separately bound root may extend a type already referenced by the primary root.
                // Expand this occurrence only, preserving its properties without changing the shared definition.
                holder = new LinkedHashMap<>(map(defs.get(ref.substring("#/$defs/".length()))));
                holder.put("properties", new LinkedHashMap<>(map(holder.get("properties"))));
                current.put(segment, holder);
            }
            if (holder == null || !(holder.get("properties") instanceof Map<?, ?>)) {
                final var intermediate = new LinkedHashMap<String, Object>();
                intermediate.put("type", "object");
                intermediate.put("properties", new LinkedHashMap<String, Object>());
                intermediate.put("additionalProperties", false);
                current.put(segment, intermediate);
            }
            current = map(map(current.get(segment)).get("properties"));
        }
        current.put(segments.getLast(), leaf);
    }

    @SuppressWarnings("unchecked")
    private void merge(final Map<String, Object> target, final Map<?, ?> source) {
        for (final var entry : source.entrySet()) {
            target.put((String) entry.getKey(), entry.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(final Object value) {
        return (Map<String, Object>) value;
    }

    private static boolean isJsonType(final Type type) {
        final var raw = type instanceof ParameterizedType parameterized ? parameterized.getRawType() : type;
        return raw instanceof Class<?> clazz
                && (JsonCodec.class.isAssignableFrom(clazz)
                        || JsonMapper.class.isAssignableFrom(clazz)
                        || Configuration.class.isAssignableFrom(clazz)
                        || RuntimeContainer.class.isAssignableFrom(clazz)
                        || Emitter.class.isAssignableFrom(clazz));
    }

    private record Root(String fqcn, String prefix) {}
}
