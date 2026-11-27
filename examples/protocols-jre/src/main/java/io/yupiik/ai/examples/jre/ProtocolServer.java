package io.yupiik.ai.examples.jre;

import io.yupiik.ai.http.server.OwnedOrBorrowedHttpServer;
import io.yupiik.fusion.framework.api.lifecycle.Start;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;

/** Startup checks only: Fusion's Launcher owns awaiting and container shutdown. */
@DefaultScoped
public class ProtocolServer {
    public void onStart(@OnEvent final Start start, final OwnedOrBorrowedHttpServer host) {
        System.out.println("Listening on http://" + host.host() + ":" + host.port());
    }
}
