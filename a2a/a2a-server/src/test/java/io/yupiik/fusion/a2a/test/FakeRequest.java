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
package io.yupiik.fusion.a2a.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toMap;

import io.yupiik.fusion.http.server.api.Body;
import io.yupiik.fusion.http.server.api.Cookie;
import io.yupiik.fusion.http.server.api.Request;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

/**
 * An HTTP request built by hand, so the bindings can be driven without a server.
 * <p>
 * It exists for the cases a running agent cannot reach: a configuration the module cannot have two of - a disabled
 * binding, a tenant - and the request shapes a client library refuses to send, a {@code Content-Length} lying about the
 * body for example.
 */
public class FakeRequest implements Request {
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Map<String, String> parameters = new LinkedHashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();

    private String scheme = "http";
    private String method = "GET";
    private String path = "/";
    private String query;
    private String body;
    private RuntimeException bodyFailure;
    private boolean bodyReadsAsNull;
    private Object unwrapped;

    public static FakeRequest of(final String method, final String path) {
        final var request = new FakeRequest();
        request.method = method;
        request.path = path;
        return request;
    }

    public FakeRequest scheme(final String scheme) {
        this.scheme = scheme;
        return this;
    }

    public FakeRequest query(final String query) {
        this.query = query;
        return this;
    }

    /**
     * @param name  the header name, it is matched ignoring the case as HTTP requires.
     * @param value its value.
     */
    public FakeRequest header(final String name, final String value) {
        headers.put(name.toLowerCase(Locale.ROOT), value);
        return this;
    }

    public FakeRequest parameter(final String name, final String value) {
        parameters.put(name, value);
        return this;
    }

    /**
     * @param body the payload, its {@code Content-Length} is set accordingly - which is what tells the bindings there is
     *             a body to read at all.
     */
    public FakeRequest body(final String body) {
        this.body = body;
        return header("content-length", String.valueOf(body.getBytes(UTF_8).length));
    }

    /**
     * A request which announces a body and then hands over nothing, i.e. what a binding has to cope with when the
     * container gives up on the payload halfway.
     */
    public FakeRequest bodyReadingAsNull() {
        this.bodyReadsAsNull = true;
        return header("content-length", "12");
    }

    /**
     * A request whose body cannot be reached at all.
     *
     * @param failure what {@link #fullBody()} throws.
     */
    public FakeRequest failingBody(final RuntimeException failure) {
        this.bodyFailure = failure;
        return header("content-length", "12");
    }

    /**
     * @param unwrapped what {@link #unwrap(Class)} answers, {@code null} for a request with nothing behind it.
     */
    public FakeRequest unwrapping(final Object unwrapped) {
        this.unwrapped = unwrapped;
        return this;
    }

    @Override
    public String scheme() {
        return scheme;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public String query() {
        return query;
    }

    @Override
    public Body fullBody() {
        if (bodyFailure != null) {
            throw bodyFailure;
        }
        return new StringBody(bodyReadsAsNull ? null : body == null ? "" : body);
    }

    @Override
    public Stream<Cookie> cookies() {
        return Stream.empty();
    }

    @Override
    public String parameter(final String name) {
        return parameters.get(name);
    }

    @Override
    public Map<String, String[]> parameters() {
        return parameters.entrySet().stream().collect(toMap(Map.Entry::getKey, it -> new String[] {it.getValue()}));
    }

    @Override
    public String header(final String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public Map<String, List<String>> headers() {
        return headers.entrySet().stream().collect(toMap(Map.Entry::getKey, it -> List.of(it.getValue())));
    }

    @Override
    public <T> T attribute(final String key, final Class<T> type) {
        return type.cast(attributes.get(key));
    }

    @Override
    public <T> void setAttribute(final String key, final T value) {
        attributes.put(key, value);
    }

    /**
     * Answers what {@link #unwrapping(Object)} was given, {@code null} by default: there is usually no container object
     * behind this request, which is also what a caller has to cope with when the call did not come from a servlet
     * container.
     */
    @Override
    public <T> T unwrap(final Class<T> type) {
        return unwrapped != null && type.isInstance(unwrapped) ? type.cast(unwrapped) : unwrapOrNull(type);
    }

    private record StringBody(String content) implements Body {
        @Override
        public Body cached() {
            return this;
        }

        @Override
        public CompletionStage<String> string() {
            return completedFuture(content);
        }

        @Override
        public CompletionStage<byte[]> bytes() {
            return completedFuture(content == null ? null : content.getBytes(UTF_8));
        }

        @Override
        public String parameter(final String name) {
            return null;
        }

        @Override
        public void subscribe(final Flow.Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override
                public void request(final long n) {
                    if (done || n <= 0) {
                        return;
                    }
                    done = true;
                    if (content != null) {
                        subscriber.onNext(ByteBuffer.wrap(content.getBytes(UTF_8)));
                    }
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                    done = true;
                }
            });
        }
    }
}
