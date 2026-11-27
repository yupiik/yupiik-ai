package io.yupiik.ai.http.server;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/** Settings consumed only when the protocol host owns its fallback JRE listener. */
@RootConfiguration("fusion.http-server")
public record HttpHostingConfiguration(
        @Property(
                defaultValue = "\"127.0.0.1\"",
                documentation =
                        "Bind address of the fallback JRE HTTP listener. An injected WebServer owns its own configuration.")
        String host,

        @Property(
                defaultValue = "8080",
                documentation =
                        "Port of the fallback JRE HTTP listener; zero requests an ephemeral port. An injected WebServer owns its own configuration.")
        int port) {}
