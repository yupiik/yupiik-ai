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
package io.yupiik.fusion.a2a.demo;

import io.yupiik.fusion.a2a.api.A2AExamples;
import io.yupiik.fusion.a2a.api.A2AOutputModes;
import io.yupiik.fusion.a2a.api.A2ASkill;
import io.yupiik.fusion.a2a.api.A2ATags;
import io.yupiik.fusion.a2a.demo.model.Report;
import io.yupiik.fusion.a2a.model.Artifact;
import io.yupiik.fusion.a2a.model.Message;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpc;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpcParam;

/**
 * A tour of what a skill can look like: the declarative path, where a skill is a plain {@code @JsonRpc} method, the
 * return type says what happens to the task, and the annotations say what the agent card advertises.
 */
@ApplicationScoped
public class DemoSkills {
    /**
     * The simplest form: text in, text out. It completes the task with one text artifact.
     */
    @A2ASkill
    @A2ATags("demo,greeting")
    @A2AExamples("Greet Fusion.")
    @A2AOutputModes("text/plain")
    @JsonRpc(value = "demo/greet", documentation = "Greets someone by name.")
    public Artifact greet(@JsonRpcParam(required = true, documentation = "Who to greet.") final String name) {
        if (name.isBlank()) {
            // any exception fails the task, and the caller reads the reason in the status message
            throw new IllegalArgumentException("name should not be blank");
        }
        return Artifact.text("greeting", "hello " + name + "!");
    }

    /**
     * Returning a model gets it wrapped in a structured artifact, so the caller receives typed JSON.
     */
    @A2ASkill
    @A2ATags("demo,weather,report")
    @A2AExamples("What is the weather in Paris?")
    @A2AOutputModes("application/json")
    @JsonRpc(value = "demo/report", documentation = "Reports the weather of a city.")
    public Report report(@JsonRpcParam(required = true, documentation = "The city to report on.") final String city) {
        return new Report(city, "sunny", 24);
    }

    /**
     * Returning a {@link Message} answers without producing any output and without leaving a task behind, which is what
     * a clarification question or a small talk exchange should do.
     */
    @A2ASkill
    @A2ATags("demo,chat")
    @A2AExamples("Hello there!")
    @A2AOutputModes("text/plain")
    @JsonRpc(value = "demo/chat", documentation = "Answers a question without tracking any task.")
    public Message chat(@JsonRpcParam(documentation = "The whole incoming message.") final Message message) {
        return Message.agent("you said: " + message.text());
    }
}
