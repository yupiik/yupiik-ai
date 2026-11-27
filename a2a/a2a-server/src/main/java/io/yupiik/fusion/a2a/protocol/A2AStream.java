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
package io.yupiik.fusion.a2a.protocol;

import io.yupiik.fusion.a2a.model.JsonRpcStreamMessage;
import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.model.Task;
import io.yupiik.fusion.json.JsonMapper;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One subscriber of a task: the {@link SseBus} writing to the client plus the framing its binding requires.
 * <p>
 * The JSON-RPC binding repeats the envelope of the originating request on every event - so the same {@code id} - while
 * the HTTP+JSON one sends the bare {@link StreamResponse}, which is the only difference between the two streams.
 * <p>
 * A task can have several streams at once - two clients watching, or one which reconnected with
 * {@code SubscribeToTask} - and each of them gets the same events in the same order.
 */
public class A2AStream {
    private final SseBus bus;
    private final JsonMapper jsons;
    private final Object jsonRpcId;
    private final boolean jsonRpc;
    private final AtomicBoolean started = new AtomicBoolean();

    private A2AStream(final SseBus bus, final JsonMapper jsons, final Object jsonRpcId, final boolean jsonRpc) {
        this.bus = bus;
        this.jsons = jsons;
        this.jsonRpcId = jsonRpcId;
        this.jsonRpc = jsonRpc;
    }

    /**
     * @param bus   the channel writing to the client.
     * @param jsons the mapper to serialize the events with.
     * @param id   the identifier of the {@code SendStreamingMessage}/{@code SubscribeToTask} JSON-RPC request.
     * @return a stream framing its events in a JSON-RPC envelope.
     */
    public static A2AStream jsonRpc(final SseBus bus, final JsonMapper jsons, final Object id) {
        return new A2AStream(bus, jsons, id, true);
    }

    /**
     * @param bus   the channel writing to the client.
     * @param jsons the mapper to serialize the events with.
     * @return a stream sending bare {@link StreamResponse} events, as the HTTP+JSON binding requires.
     */
    public static A2AStream plain(final SseBus bus, final JsonMapper jsons) {
        return new A2AStream(bus, jsons, null, false);
    }

    /**
     * @return the body to answer with.
     */
    public SseBus bus() {
        return bus;
    }

    /**
     * Sends the initial {@code Task} event, which a task stream must begin with, unless something was already sent on
     * this stream.
     *
     * @param task the current state of the task.
     */
    public void emitInitial(final Task task) {
        if (started.compareAndSet(false, true)) {
            emit(StreamResponse.of(task));
        }
    }

    /**
     * Marks the stream as started without sending the initial {@code Task} event, which is what a message only stream
     * needs: it carries exactly one message and closes.
     */
    public void markStarted() {
        started.set(true);
    }

    /**
     * Sends an event and closes the stream when it is the last one, i.e. a direct message or a settled status.
     *
     * @param event what happened.
     */
    public void emit(final StreamResponse event) {
        bus.publish(jsons.toString(jsonRpc ? JsonRpcStreamMessage.of(jsonRpcId, event) : event));
        if (event.isLast()) {
            bus.close();
        }
    }

    /**
     * Ends the stream without sending anything more.
     */
    public void close() {
        bus.close();
    }

    /**
     * @return {@code true} once the stream is over, i.e. it must be deregistered from the task.
     */
    public boolean isClosed() {
        return bus.isClosed();
    }
}
