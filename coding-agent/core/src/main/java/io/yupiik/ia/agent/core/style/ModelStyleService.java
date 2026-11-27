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
package io.yupiik.ia.agent.core.style;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderConfiguration;
import io.yupiik.ia.agent.core.configuration.ProviderModelConfiguration;
import io.yupiik.ia.agent.core.llm.ChatClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the display color and weight (bold) for a model name shown in the CLI
 * prompt / banner and in the TUI sidebar / status bar.
 *
 * <p>Explicit configuration wins, then the provider tone, then an implicit
 * value-based palette so distinct (provider, model) pairs are visually
 * distinguishable independently of the provider list order:</p>
 * <ol>
 *   <li>an exact/contains entry of the provider {@code models} list provides {@code color} and/or {@code bold};</li>
 *   <li>else the provider {@code color} base tone is used as the family hue;</li>
 *   <li>else the hue is derived deterministically from the provider {@code id}
 *       so a provider always keeps its color whatever its position;</li>
 *   <li>the model shades that tone from the model name hash: every model gets a
 *       stable, deterministic lightness within the provider family and the first
 *       two listed models are bold.</li>
 * </ol>
 * <p>Every resolved color is contrast-adjusted against the effective terminal
 * background (dark/light, taken from the persisted {@code theme} file when
 * present) so the model name stays readable.</p>
 */
@ApplicationScoped
public class ModelStyleService {
    static final double CONTRAST_TARGET = 4.5;

    private final CodingAgentConfiguration configuration;
    private final ChatClient chatClient;
    private volatile String cachedTheme;
    private boolean themeInvalidated = true;

    public ModelStyleService(final CodingAgentConfiguration configuration, final ChatClient chatClient) {
        this.configuration = configuration;
        this.chatClient = chatClient;
    }

    protected ModelStyleService() {
        this.configuration = null;
        this.chatClient = null;
    }

    /** A resolved display style for a model name. */
    public record ModelStyle(String hex, boolean bold) {
        /** ANSI SGR prefix (truecolor foreground + optional bold) for the CLI prompt/banner. */
        public String ansiPrefix() {
            final var sb = new StringBuilder();
            if (hex != null && !hex.isBlank()) {
                final var rgb = parseHex(hex);
                sb.append("\u001b[38;2;")
                        .append(rgb[0])
                        .append(';')
                        .append(rgb[1])
                        .append(';')
                        .append(rgb[2])
                        .append('m');
            }
            if (bold) {
                sb.append("\u001b[1m");
            }
            return sb.toString();
        }
    }

    /** The style for the current chat client model (uses the active provider and its override, if any). */
    public ModelStyle current() {
        final var provider = chatClient.currentProvider();
        return resolve(provider, chatClient.model());
    }

    /**
     * Resolve the style for {@code model} of {@code provider}, contending with the
     * persisted theme background. When {@code provider} is null (eg. no configuration
     * at all) a neutral bold is returned.
     */
    public ModelStyle resolve(final ProviderConfiguration provider, final String model) {
        return resolve(provider, model, effectiveBackground());
    }

    /**
     * Resolve the style for {@code model} of {@code provider} against an explicit
     * background ({@code true} = dark). The TUI passes its in-memory effective theme
     * so a {@code /theme} switch restyles the model name immediately.
     */
    public ModelStyle resolve(final ProviderConfiguration provider, final String model, final boolean dark) {
        if (provider == null) {
            return new ModelStyle(dark ? "#d9d9d9" : "#404040", true);
        }
        final var entry = match(provider.models(), model);
        final var index = listedIndex(provider.models(), model);

        final var style = entry == null ? null : entry.style();
        final var explicitBold = style == null ? null : style.resolvedBold();
        final boolean bold = explicitBold != null ? explicitBold : index < 2;

        double hue;
        double chroma;
        double lightness;
        if (style != null && style.color() != null && !style.color().isBlank()) {
            // an explicit model color wins completely (hue, chroma and lightness)
            final var hsl = toHsl(parseHex(style.color()));
            hue = hsl[0];
            chroma = hsl[1];
            lightness = hsl[2];
        } else if (provider.color() != null && !provider.color().isBlank()) {
            final var hsl = toHsl(parseHex(provider.color()));
            hue = hsl[0];
            chroma = hsl[1];
            // the family tone carries the provider hue; each model shades it deterministically
            lightness = modelLightness(model);
        } else {
            hue = providerHue(provider);
            chroma = 0.72;
            lightness = modelLightness(model);
        }

        final var hex = toHex(hslToRgb(hue, chroma, lightness));
        final var adjusted = adjustContrast(hex, dark);
        return new ModelStyle(adjusted, bold);
    }

    private String adjustContrast(String hex, final boolean dark) {
        final var rgb = parseHex(hex);
        final double r = rgb[0] / 255.0;
        final double g = rgb[1] / 255.0;
        final double b = rgb[2] / 255.0;
        final var lum = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
        final var bg = dark ? 0.0 : 1.0;
        var l = lum;
        var contrast = contrastRatio(l, bg);
        // progressively brighten on dark (or darken on light) until readable
        for (int i = 0; i < 40 && contrast < CONTRAST_TARGET; i++) {
            l = dark ? Math.min(1.0, l + 0.04) : Math.max(0.0, l - 0.04);
            contrast = contrastRatio(l, bg);
        }
        if (Math.abs(l - lum) < 0.001) {
            return hex;
        }
        final var hsl = toHsl(rgb);
        return toHex(hslToRgb(hsl[0], hsl[1], l));
    }

    private static double contrastRatio(final double l1, final double l2) {
        final var lighter = Math.max(l1, l2);
        final var darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double linear(final double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double[] toHsl(final int[] rgb) {
        final double r = rgb[0] / 255.0;
        final double g = rgb[1] / 255.0;
        final double b = rgb[2] / 255.0;
        final double max = Math.max(r, Math.max(g, b));
        final double min = Math.min(r, Math.min(g, b));
        final double l = (max + min) / 2.0;
        double h = 0;
        double s = 0;
        final double d = max - min;
        if (d > 0) {
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) {
                h = (g - b) / d + (g < b ? 6 : 0);
            } else if (max == g) {
                h = (b - r) / d + 2;
            } else {
                h = (r - g) / d + 4;
            }
            h *= 60;
        }
        return new double[] {h, s, l};
    }

    private static int[] hslToRgb(final double h, final double s, final double l) {
        final double hue = ((h % 360) + 360) % 360 / 360;
        final double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        final double p = 2 * l - q;
        final double r = hueToRgb(p, q, hue + 1.0 / 3.0);
        final double g = hueToRgb(p, q, hue);
        final double b = hueToRgb(p, q, hue - 1.0 / 3.0);
        return new int[] {Math.round((float) (r * 255)), Math.round((float) (g * 255)), Math.round((float) (b * 255))};
    }

    private static double hueToRgb(final double p, final double q, double t) {
        if (t < 0) {
            t += 1;
        }
        if (t > 1) {
            t -= 1;
        }
        if (t < 1.0 / 6.0) {
            return p + (q - p) * 6 * t;
        }
        if (t < 1.0 / 2.0) {
            return q;
        }
        if (t < 2.0 / 3.0) {
            return p + (q - p) * (2.0 / 3.0 - t) * 6;
        }
        return p;
    }

    private static String toHex(final int[] rgb) {
        final var clamp = new int[] {
            Math.max(0, Math.min(255, rgb[0])), Math.max(0, Math.min(255, rgb[1])), Math.max(0, Math.min(255, rgb[2]))
        };
        return String.format(Locale.ROOT, "#%02x%02x%02x", clamp[0], clamp[1], clamp[2]);
    }

    private static int[] parseHex(final String hex) {
        final var clean = hex.strip();
        final var start = clean.startsWith("#") ? 1 : 0;
        return new int[] {
            Integer.parseInt(clean.substring(start, start + 2), 16),
            Integer.parseInt(clean.substring(start + 2, start + 4), 16),
            Integer.parseInt(clean.substring(start + 4, start + 6), 16)
        };
    }

    /** Deterministic hue (degrees) derived from the provider id, stable regardless of list position. */
    private static double providerHue(final ProviderConfiguration provider) {
        final var seed =
                provider == null || provider.id() == null ? 0 : provider.id().hashCode();
        return (seed & 0x7FFFFFFF) % 360;
    }

    /** Deterministic lightness from the model name so its shade never depends on the providers list order. */
    private static double modelLightness(final String model) {
        final var seed = model == null ? 0 : model.hashCode();
        final var shade = (seed & 0x7FFFFFFF) % 9;
        return 0.40 + (shade / 8.0) * 0.34;
    }

    private static int listedIndex(final List<ProviderModelConfiguration> models, final String model) {
        if (models == null || model == null) {
            return -1;
        }
        for (int i = 0; i < models.size(); i++) {
            if (model.equals(models.get(i).model())) {
                return i;
            }
            if (contains(models.get(i).model(), model)) {
                return i;
            }
        }
        return -1;
    }

    private static ProviderModelConfiguration match(final List<ProviderModelConfiguration> models, final String model) {
        if (models == null || model == null) {
            return null;
        }
        ProviderModelConfiguration contains = null;
        for (final var entry : models) {
            if (model.equals(entry.model())) {
                return entry;
            }
            if (contains == null && contains(entry.model(), model)) {
                contains = entry;
            }
        }
        return contains;
    }

    /** True when {@code model} contains the {@code pattern} (asterisks are ignored) and the pattern is not blank. */
    private static boolean contains(final String pattern, final String model) {
        final var needle = pattern == null ? "" : pattern.replace("*", "");
        return !needle.isEmpty() && model.contains(needle);
    }

    private boolean effectiveBackground() {
        final String theme = theme();
        return !"light".equalsIgnoreCase(theme);
    }

    /** The persisted theme ('dark' or 'light') from the storage home theme file, 'dark' when absent/unknown. */
    protected String theme() {
        final String cached = cachedTheme;
        if (cached != null && !themeInvalidated) {
            return cached;
        }
        String value = "dark";
        if (configuration != null) {
            try {
                final Path file = Path.of(configuration.resolveStorageHome(), "theme");
                if (Files.isRegularFile(file)) {
                    final var content = Files.readString(file).trim();
                    if ("light".equalsIgnoreCase(content) || "dark".equalsIgnoreCase(content)) {
                        value = content.toLowerCase(Locale.ROOT);
                    }
                }
            } catch (final IOException e) {
                value = "dark";
            }
        }
        cachedTheme = value;
        themeInvalidated = false;
        return value;
    }

    /** Clear the cached theme (used by the TUI on /theme switches so it reflects immediately within one check). */
    public void invalidateTheme() {
        themeInvalidated = true;
    }
}
