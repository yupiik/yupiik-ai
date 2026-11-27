/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.signature;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.internal.JsonStrings;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * The JSON Canonicalization Scheme of <a href="https://www.rfc-editor.org/rfc/rfc8785">RFC 8785</a>, which section
 * 8.4.1 of the A2A specification mandates before signing an agent card.
 * <p>
 * It serializes a <em>parsed</em> structure - {@code Map}, {@code List}, {@code String}, {@code Number},
 * {@code Boolean}, {@code null} - and not a model object, so the same code produces the bytes to sign and the bytes to
 * verify whatever the mapper did in between.
 * <p>
 * The scheme is only useful if two implementations agree byte for byte, so where this one cannot guarantee that it
 * refuses to guess: see {@link #canonicalize(Object)} on numbers.
 */
@ApplicationScoped
public class JsonCanonicalizer {
    /**
     * @param json a parsed JSON structure.
     * @return its canonical form: members sorted, no whitespace, minimal escaping.
     * @throws IllegalStateException on a value the scheme cannot represent unambiguously here, i.e. a non integral
     *                               number: RFC 8785 wants the ECMAScript {@code Number::toString} shortest round trip
     *                               form, which is not what {@code Double#toString} produces, so emitting it would risk
     *                               a signature no other implementation can verify.
     */
    public String canonicalize(final Object json) {
        final var out = new StringBuilder();
        write(json, out, "$");
        return out.toString();
    }

    protected void write(final Object value, final StringBuilder out, final String path) {
        switch (value) {
            case null -> out.append("null");
            case Boolean bool -> out.append(bool.booleanValue());
            case String string -> string(string, out);
            case Number number -> out.append(number(number, path));
            case Map<?, ?> map -> object(map, out, path);
            case Iterable<?> list -> array(list, out, path);
            case Object[] array -> array(List.of(array), out, path);
            default ->
                throw new IllegalStateException(
                        "Can't canonicalize " + value.getClass().getName() + " at " + path
                                + ", the structure must only hold maps, lists, strings, numbers and booleans");
        }
    }

    protected void object(final Map<?, ?> map, final StringBuilder out, final String path) {
        // RFC 8785 section 3.2.3: members are sorted on the UTF-16 code units of their name, which is exactly what
        // String#compareTo compares
        final var names = map.keySet().stream().map(String::valueOf).sorted().toList();

        out.append('{');
        var first = true;
        for (final var name : names) {
            if (!first) {
                out.append(',');
            }
            first = false;
            string(name, out);
            out.append(':');
            write(map.get(name), out, path + '.' + name);
        }
        out.append('}');
    }

    protected void array(final Iterable<?> list, final StringBuilder out, final String path) {
        out.append('[');
        var first = true;
        var index = 0;
        for (final var item : list) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(item, out, path + '[' + index++ + ']');
        }
        out.append(']');
    }

    /**
     * RFC 8785 section 3.2.2.2: only what has to be escaped is escaped, with the short forms where they exist and
     * lowercase hexadecimal otherwise. Everything else - accents, emoji, anything above the control range - stays
     * literal and is carried by the UTF-8 encoding.
     */
    protected void string(final String value, final StringBuilder out) {
        for (int i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            if (Character.isSurrogate(c)) {
                if (!Character.isHighSurrogate(c)
                        || i + 1 == value.length()
                        || !Character.isLowSurrogate(value.charAt(++i))) {
                    throw new IllegalStateException("Can't canonicalize an unpaired UTF-16 surrogate");
                }
            }
        }
        out.append(JsonStrings.escapeChars(value));
    }

    /**
     * RFC 8785 section 3.2.2.3 asks for the ECMAScript {@code Number::toString} form. Integral values have only one such
     * form - the plain digits - so they are emitted; anything else is refused rather than guessed.
     * <p>
     * In an agent card a number can only appear inside {@code capabilities.extensions[].params}, so this is a corner an
     * application can simply stay out of.
     */
    protected String number(final Number number, final String path) {
        if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte) {
            return number.toString();
        }
        if (number instanceof BigInteger bigInteger) {
            return bigInteger.toString();
        }
        final var value = new BigDecimal(number.toString());
        try {
            return value.toBigIntegerExact().toString();
        } catch (final ArithmeticException ae) {
            throw new IllegalStateException("Can't canonicalize the non integral number " + number + " at " + path
                    + ": RFC 8785 requires the ECMAScript Number::toString form, which this implementation does "
                    + "not compute. Keep fractional numbers out of the signed content, or send them as strings.");
        }
    }
}
