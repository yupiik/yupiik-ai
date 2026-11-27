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

import io.yupiik.fusion.a2a.configuration.A2AConfiguration;

/**
 * Builds {@link A2AConfiguration}s for the tests.
 * <p>
 * {@link A2AConfiguration} is a record of twenty three components, so a test setting one of them would otherwise be
 * twenty two irrelevant arguments long - and unreadable about the one thing it is really asserting.
 * <p>
 * The defaults are the documented ones, {@code publicUrl} excepted: it is set, so an interface URL does not depend on a
 * request unless a test says so.
 */
public final class Configurations {
    private String name = "test-agent";
    private String description = "An agent used by the tests.";
    private String version = "1.0.0";
    private String documentationUrl;
    private String iconUrl;
    private String providerOrganization;
    private String providerUrl;
    private String defaultInputModes = "text/plain,application/json";
    private String defaultOutputModes = "text/plain,application/json";
    private String publicUrl = "https://agent.example.com";
    private String tenant;
    private boolean streaming = true;
    private boolean pushNotifications = true;
    private boolean extendedAgentCard = false;
    private boolean restBinding = true;
    private String taskTimeout = "PT5M";
    private String taskRetention = "PT30M";
    private String pushTimeout = "PT30S";
    private int agentCardMaxAge = 300;
    private String signatureKey;
    private String signatureAlgorithm = "ES256";
    private String signatureKid;
    private String signatureJku;

    private Configurations() {
        // no-op
    }

    /**
     * @return a builder holding the documented defaults.
     */
    public static Configurations configuration() {
        return new Configurations();
    }

    /**
     * @return the documented defaults, i.e. what an agent which configures nothing gets.
     */
    public static A2AConfiguration defaults() {
        return configuration().build();
    }

    public Configurations name(final String name) {
        this.name = name;
        return this;
    }

    public Configurations description(final String description) {
        this.description = description;
        return this;
    }

    public Configurations version(final String version) {
        this.version = version;
        return this;
    }

    public Configurations documentationUrl(final String documentationUrl) {
        this.documentationUrl = documentationUrl;
        return this;
    }

    public Configurations iconUrl(final String iconUrl) {
        this.iconUrl = iconUrl;
        return this;
    }

    public Configurations provider(final String organization, final String url) {
        this.providerOrganization = organization;
        this.providerUrl = url;
        return this;
    }

    public Configurations inputModes(final String defaultInputModes) {
        this.defaultInputModes = defaultInputModes;
        return this;
    }

    public Configurations outputModes(final String defaultOutputModes) {
        this.defaultOutputModes = defaultOutputModes;
        return this;
    }

    /**
     * @param publicUrl the base URL to advertise, {@code null} to have it derived from each request.
     */
    public Configurations publicUrl(final String publicUrl) {
        this.publicUrl = publicUrl;
        return this;
    }

    public Configurations tenant(final String tenant) {
        this.tenant = tenant;
        return this;
    }

    public Configurations streaming(final boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    public Configurations pushNotifications(final boolean pushNotifications) {
        this.pushNotifications = pushNotifications;
        return this;
    }

    public Configurations extendedAgentCard(final boolean extendedAgentCard) {
        this.extendedAgentCard = extendedAgentCard;
        return this;
    }

    public Configurations restBinding(final boolean restBinding) {
        this.restBinding = restBinding;
        return this;
    }

    /**
     * @param taskTimeout how long a blocking {@code SendMessage} waits, an ISO-8601 duration such as {@code PT0.1S}.
     */
    public Configurations taskTimeout(final String taskTimeout) {
        this.taskTimeout = taskTimeout;
        return this;
    }

    /**
     * @param taskRetention how long a settled task is kept, an ISO-8601 duration - or {@code 0} to keep them forever.
     */
    public Configurations taskRetention(final String taskRetention) {
        this.taskRetention = taskRetention;
        return this;
    }

    /**
     * @param pushTimeout the timeout of a webhook call, an ISO-8601 duration such as {@code PT1S}.
     */
    public Configurations pushTimeout(final String pushTimeout) {
        this.pushTimeout = pushTimeout;
        return this;
    }

    public Configurations agentCardMaxAge(final int agentCardMaxAge) {
        this.agentCardMaxAge = agentCardMaxAge;
        return this;
    }

    public Configurations signature(final String key, final String algorithm) {
        this.signatureKey = key;
        this.signatureAlgorithm = algorithm;
        return this;
    }

    public Configurations signatureKid(final String signatureKid) {
        this.signatureKid = signatureKid;
        return this;
    }

    public Configurations signatureJku(final String signatureJku) {
        this.signatureJku = signatureJku;
        return this;
    }

    public A2AConfiguration build() {
        return new A2AConfiguration(
                name,
                description,
                version,
                documentationUrl,
                iconUrl,
                providerOrganization,
                providerUrl,
                defaultInputModes,
                defaultOutputModes,
                publicUrl,
                tenant,
                streaming,
                pushNotifications,
                extendedAgentCard,
                restBinding,
                taskTimeout,
                taskRetention,
                pushTimeout,
                agentCardMaxAge,
                signatureKey,
                signatureAlgorithm,
                signatureKid,
                signatureJku);
    }
}
