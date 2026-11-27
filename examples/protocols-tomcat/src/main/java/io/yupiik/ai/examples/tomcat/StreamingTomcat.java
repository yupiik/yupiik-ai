package io.yupiik.ai.examples.tomcat;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.http.server.api.WebServer;
import io.yupiik.fusion.http.server.impl.tomcat.TomcatWebServerConfiguration;
import java.util.ArrayList;

/** Typed, explicit opt-in customization; no servlet references in either protocol library. */
@DefaultScoped
public class StreamingTomcat {
    public void configure(@OnEvent final WebServer.Configuration event) {
        final var tomcat = event.unwrap(TomcatWebServerConfiguration.class);
        final var customizers = new ArrayList<>(tomcat.getTomcatCustomizers());
        customizers.add(server -> server.getConnector().setProperty("asyncTimeout", "0"));
        tomcat.setTomcatCustomizers(customizers);
    }
}
