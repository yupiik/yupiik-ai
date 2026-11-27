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

import io.yupiik.fusion.a2a.model.StreamResponse;
import io.yupiik.fusion.a2a.service.A2ATask;
import io.yupiik.fusion.a2a.service.PushNotifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link PushNotifier} which records instead of posting, so a test can assert what <em>would</em> be delivered without
 * standing up a webhook - {@code PushNotificationTest} covers the delivery itself.
 */
public class RecordingPushNotifier extends PushNotifier {
    public RecordingPushNotifier() {
        super(null, null, null);
    }

    private final List<StreamResponse> events = new CopyOnWriteArrayList<>();

    private RuntimeException failure;

    /**
     * Makes every delivery blow up, which is what a caller must be shielded from: a broken notifier is not a reason for
     * a task to lose its outcome.
     *
     * @param failure what {@link #notify} throws from now on.
     */
    public void failWith(final RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public void notify(final A2ATask task, final StreamResponse event) {
        events.add(event);
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * @return the events published on the tasks of this agent, in order.
     */
    public List<StreamResponse> events() {
        return List.copyOf(events);
    }
}
